import { execFileSync } from 'node:child_process'
import { createHmac, randomUUID } from 'node:crypto'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { test, expect, request, type APIRequestContext, type Browser } from '@playwright/test'
import { composeArgs, directory, origin } from './verify'

type Staff = { id: number; username: string; displayName: string }
type Fixture = { password: string; otp: string }

function docker(...args: string[]) {
  return execFileSync('docker', [...composeArgs, ...args], { cwd: '..', encoding: 'utf8', stdio: 'pipe' })
}

function sql(statement: string) {
  return docker('exec', '-T', 'postgres', 'psql', '-XqAt', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres',
    '-d', 'care_preprod', '-c', statement).trim()
}

function totp(secret: string) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  const bits = [...secret].map((char) => alphabet.indexOf(char).toString(2).padStart(5, '0')).join('')
  const key = Buffer.from(bits.match(/.{8}/g)!.map((byte) => Number.parseInt(byte, 2)))
  const counter = Buffer.alloc(8)
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30_000)))
  const digest = createHmac('sha1', key).update(counter).digest()
  const offset = digest[digest.length - 1]! & 15
  return String((digest.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).padStart(6, '0')
}

async function staffLogin(browser: Browser, username: string, allowed = true) {
  const context = await browser.newContext({ baseURL: origin, ignoreHTTPSErrors: true })
  const page = await context.newPage()
  const people = JSON.parse(readFileSync(resolve(directory, 'staff.json'), 'utf8')) as Record<string, Fixture>
  await page.goto('/')
  await page.getByRole('link', { name: '工作人员统一登录' }).click()
  await page.locator('#username').fill(username)
  await page.locator('#password').fill(people[username]!.password)
  await page.locator('#kc-login').click()
  await expect(page.locator('#otp')).toBeVisible()
  const marker = resolve(directory, 'otp-used-' + username)
  const previous = existsSync(marker) ? Number(readFileSync(marker, 'utf8')) : 0
  const start = Math.floor(Date.now() / 30_000) * 30_000
  const delay = Math.max(previous + 30_200 - Date.now(), Date.now() - start > 28_000 ? 2_200 : 0)
  if (delay > 0) await new Promise((done) => setTimeout(done, delay))
  writeFileSync(marker, String(Math.floor(Date.now() / 30_000) * 30_000))
  await page.locator('#otp').fill(totp(people[username]!.otp))
  await page.locator('#kc-login').click()
  await expect(page).toHaveURL(allowed ? origin + '/workspace' : origin + '/?staff-login=denied')
  return { context, page, api: context.request }
}

async function csrf(api: APIRequestContext): Promise<Record<string, string>> {
  const response = await api.get('/api/v1/session/csrf')
  expect(response.ok()).toBeTruthy()
  const token = await response.json()
  return { [token.header]: token.token }
}

async function post(api: APIRequestContext, path: string, data?: unknown, key?: string) {
  const headers = await csrf(api)
  if (key) headers['Idempotency-Key'] = key
  return api.post('/api/v1' + path, { headers, data })
}

async function patient(index: number, password: string) {
  const context = await request.newContext({ baseURL: origin, ignoreHTTPSErrors: true })
  const response = await context.post('/api/v1/session/login', {
    form: { username: 'load-' + index, password }, headers: await csrf(context),
  })
  expect(response.status()).toBe(204)
  return context
}

async function configureDoctor(admin: APIRequestContext, departments: number[], primary: number) {
  const { staff } = await (await admin.get('/api/v1/admin/staff')).json() as { staff: Staff[] }
  const doctor = staff.find((person) => person.username === 'doctor-a')!
  expect((await post(admin, '/admin/staff/' + doctor.id, {
    displayName: doctor.displayName, roles: ['DOCTOR'], enabled: true,
    departmentIds: departments, doctorDepartmentId: primary,
  })).status()).toBe(204)
}

function slotInput(capacity: number) {
  return {
    doctorId: Number(sql("select d.id from doctor d join app_user u on u.id = d.user_id where u.username = 'doctor-a'")),
    startsAt: new Date(Date.now() + 3_600_000).toISOString(),
    endsAt: new Date(Date.now() + 7_200_000).toISOString(), capacity,
  }
}

test('AC-10–12 OIDC MFA, scoped handoff and API replica failover', async ({ browser }) => {
  const names = ['administrator', 'operator-a', 'operator-b', 'followup-a', 'reviewer', 'followup-b']
  const [admin, operator, other, owner, reviewer, replacement] = await Promise.all(names.map((name) => staffLogin(browser, name)))
  await staffLogin(browser, 'unregistered', false)
  await expect(admin.page.getByRole('heading', { name: '科室与人员管理' })).toBeVisible()
  const screenshot = process.env.CARE_PREPROD_SCREENSHOT
  if (screenshot) await admin.page.screenshot({ path: screenshot, fullPage: true })
  expect((await admin.context.cookies()).some((cookie) => cookie.name === 'SESSION' && cookie.secure && cookie.httpOnly)).toBe(true)
  expect((await operator.api.get('/api/v1/admin/staff')).status()).toBe(403)
  expect((await admin.api.get('/actuator/prometheus')).status()).toBe(404)

  const operatorAccount = (await (await operator.api.get('/api/v1/session')).json())
  const department = operatorAccount.departmentIds[0]
  const otherDept = (await (await other.api.get('/api/v1/session')).json()).departmentIds[0]
  await configureDoctor(admin.api, [department], department)
  const input = slotInput(20)
  expect((await post(other.api, '/schedule-slots', input)).status()).toBe(404)
  const slot = await post(operator.api, '/schedule-slots', input)
  expect(slot.status()).toBe(201)
  const original = await slot.json()

  const password = readFileSync(resolve(directory, 'patient_password'), 'utf8')
  const [p1, p2] = await Promise.all([patient(1, password), patient(2, password)])
  expect((await p1.post('/api/v1/appointments', { data: { slotId: original.id } })).status()).toBe(403)
  const key = randomUUID()
  const booked = await post(p1, '/appointments', { slotId: original.id }, key)
  expect(booked.status()).toBe(201)
  const appointment = await booked.json()
  expect(await (await post(p1, '/appointments', { slotId: original.id }, key)).json()).toEqual(appointment)
  expect((await p2.get('/api/v1/appointments/' + appointment.id)).status()).toBe(404)

  await configureDoctor(admin.api, [department, otherDept], otherDept)
  expect((await (await p1.get('/api/v1/appointments/' + appointment.id)).json()).department).toBe('Synthetic A')
  const stop = '/schedule-slots/' + original.id + '/stop'
  expect((await post(other.api, stop, { reason: 'Synthetic test' })).status()).toBe(404)
  expect((await post(operator.api, stop, { reason: 'Synthetic test' })).status()).toBe(204)
  expect((await (await other.api.get('/api/v1/work-items/page?departmentId=' + department)).json()).work).toEqual([])
  const stopContact = '/appointments/' + appointment.id + '/stop-contacts'
  const stopList = '/api/v1/appointments/stopped?appointmentId=' + appointment.id
  expect(await (await other.api.get(stopList)).json()).toEqual([])
  expect((await other.api.get('/api/v1' + stopContact)).status()).toBe(404)
  expect((await post(other.api, stopContact, { outcome: 'REACHED' })).status()).toBe(404)
  const callback = {
    outcome: 'CALL_BACK', callbackOwnerId: operatorAccount.id, callbackDueAt: new Date(Date.now() + 3_600_000).toISOString(),
  }
  expect((await post(operator.api, stopContact, { outcome: 'CALL_BACK' })).status()).toBe(400)
  expect((await post(operator.api, stopContact, callback)).status()).toBe(204)
  expect(await (await operator.api.get(stopList)).json()).toMatchObject([
    { id: appointment.id, contactOutcome: 'CALL_BACK', callbackOwnerId: operatorAccount.id, callbackStatus: 'OPEN' },
  ])
  const callbackProgress = '/api/v1/appointments/' + appointment.id + '/callback-status'
  expect((await p2.get(callbackProgress)).status()).toBe(404)
  expect(await (await p1.get(callbackProgress)).json()).toMatchObject({ status: 'SCHEDULED' })
  await expect.poll(async () => (await (await p1.get('/api/v1/inbox/highlights')).json()).some(
    (item: { kind: string; objectId: number }) => item.kind === 'APPOINTMENT_CALLBACK_UPDATED' && item.objectId === appointment.id),
  { timeout: 12_000 }).toBe(true)

  const ownerId = (await (await owner.api.get('/api/v1/session')).json()).id
  const reviewerId = (await (await reviewer.api.get('/api/v1/session')).json()).id
  const newOwner = (await (await replacement.api.get('/api/v1/session')).json()).id
  const record = { patientReference: 'SYN-1', patientName: 'Synthetic Patient 1',
    reference: randomUUID(), careDate: '2026-01-01', departmentId: department }
  expect((await post(owner.api, '/episodes', { ...record, departmentId: otherDept })).status()).toBe(404)
  const episodeResponse = await post(owner.api, '/episodes', record)
  expect(episodeResponse.status()).toBe(201)
  const episodeId = (await episodeResponse.json()).id
  const documents = '/api/v1/episodes/' + episodeId + '/documents'
  expect((await replacement.api.get(documents)).status()).toBe(404)
  expect((await admin.api.get(documents)).status()).toBe(404)
  const oversized = await owner.api.post(documents, {
    headers: await csrf(owner.api),
    multipart: { title: 'Synthetic oversized attachment', file: {
      name: 'sample.txt', mimeType: 'text/plain', buffer: Buffer.alloc(5_600_000, 65),
    } },
  })
  expect(oversized.status()).toBe(413)

  const unauthorizedAgent = docker('exec', '-T', 'worker', 'curl', '-s', '-o', '/dev/null', '-w', '%{http_code}',
    '-H', 'Content-Type: application/json', '--data', '{}', 'http://agent:8000/internal/v1/followup-drafts')
  expect(unauthorizedAgent.trim()).toBe('401')
  expect((await post(owner.api, '/episodes/' + episodeId + '/documents', {
    title: 'Synthetic source', text: '[timeout] Synthetic input within the staff department.',
  })).status()).toBe(201)
  const agentJob = await post(owner.api, '/episodes/' + episodeId + '/agent-jobs')
  expect(agentJob.ok()).toBe(true)
  const jobPath = '/api/v1/agent-jobs/' + (await agentJob.json()).id
  await expect.poll(async () => {
    const response = await owner.api.get(jobPath)
    return response.ok() ? (await response.json()).failureCode : response.status()
  }, { timeout: 15_000 }).toBe('MODEL_TIMEOUT')

  const created = await post(owner.api, '/followups', {
    episodeId, reviewerId, draft: { kind: 'PENDING_RESULT', title: 'Synthetic follow-up',
      instructions: 'Check synthetic result', dueAt: new Date(Date.now() + 3_600_000).toISOString(),
      manualReference: 'Synthetic discharge record', segmentIds: [] },
  })
  expect(created.status()).toBe(201)
  const task = (await created.json()).id
  const actions = '/followups/' + task + '/actions'
  expect((await post(owner.api, actions, { action: 'SUBMIT', version: 1 })).status()).toBe(204)
  expect((await post(reviewer.api, actions, { action: 'APPROVE', version: 1 })).status()).toBe(204)
  const staffPath = '/admin/staff/' + ownerId
  const staffInput = { displayName: 'followup-a', roles: ['FOLLOWUP'], departmentIds: [department] }
  expect((await post(admin.api, staffPath, { ...staffInput, enabled: true, roles: ['FOLLOWUP', 'REVIEWER'] })).status()).toBe(204)
  expect((await post(admin.api, '/admin/followups/' + task + '/reassign',
    { ownerId: newOwner, reviewerId: ownerId })).status()).toBe(409)
  expect((await post(admin.api, staffPath, { ...staffInput, enabled: false })).status()).toBe(204)
  try {
    expect((await owner.api.get('/api/v1/session')).status()).toBe(401)
    const cursor = task > 1 ? '&afterId=' + (task - 1) : ''
    const queue = await (await admin.api.get('/api/v1/admin/assignments?departmentId=' + department + cursor)).json()
    expect(queue.assignments).toEqual(expect.arrayContaining([expect.objectContaining({ id: task, ownerUnavailable: true })]))
    expect((await post(admin.api, '/admin/followups/' + task + '/reassign', { ownerId: newOwner })).status()).toBe(204)
    expect((await replacement.api.get(documents)).status()).toBe(200)
    expect((await post(reviewer.api, actions, { action: 'DISPATCH', version: 1 })).status()).toBe(204)
    await expect.poll(() => sql("select count(*) from inbox_message m join outbox_event o on o.id = m.event_id "
      + "where o.object_id = " + Number(task) + " and o.kind = 'FOLLOWUP_PUBLISHED'")).toBe('1')
  } finally {
    expect((await post(admin.api, staffPath, { ...staffInput, enabled: true })).status()).toBe(204)
  }

  const [first] = docker('ps', '-q', 'api').trim().split(/\s+/)
  expect(first).toBeTruthy()
  execFileSync('docker', ['stop', first!], { stdio: 'pipe' })
  try {
    await expect.poll(async () => (await p1.get('/api/v1/session', { timeout: 11_000 })).status(),
      { timeout: 20_000 }).toBe(200)
    await expect.poll(async () => (await p1.get('/api/v1/appointments/' + appointment.id, { timeout: 11_000 })).status(),
      { timeout: 20_000 }).toBe(200)
  } finally {
    execFileSync('docker', ['start', first!], { stdio: 'pipe' })
  }
})

test('AC-11 hotspot consumes only the available seats', async ({ browser }) => {
  const operator = await staffLogin(browser, 'operator-a')
  const admin = await staffLogin(browser, 'administrator')
  const department = (await (await operator.api.get('/api/v1/session')).json()).departmentIds[0]
  await configureDoctor(admin.api, [department], department)
  const count = Number(process.env.CARE_PREPROD_HOTSPOT_CLIENTS ?? 300)
  const capacity = count >= 300 ? 50 : 5
  const activeSlot = await post(operator.api, '/schedule-slots', slotInput(capacity))
  expect(activeSlot.status()).toBe(201)
  const { id } = await activeSlot.json()
  const password = readFileSync(resolve(directory, 'patient_password'), 'utf8')
  const sessions: { api: APIRequestContext; headers: Record<string, string> }[] = []
  try {
    for (let first = 1; first <= count; first += 10) {
      const started = Date.now()
      sessions.push(...await Promise.all(Array.from({ length: Math.min(10, count - first + 1) }, async (_, index) => {
        const api = await patient(first + index, password)
        return { api, headers: { ...await csrf(api), 'Idempotency-Key': randomUUID() } }
      })))
      const spacing = 350 - (Date.now() - started)
      if (spacing > 0) await new Promise((done) => setTimeout(done, spacing))
    }
    const booked = await Promise.all(sessions.map(({ api, headers }) =>
      api.post('/api/v1/appointments', { headers, data: { slotId: id } })))
    expect(booked.filter((response) => response.status() === 201)).toHaveLength(capacity)
    expect(booked.filter((response) => response.status() === 409)).toHaveLength(count - capacity)
    expect(sql('select remaining || ' + "':' || (select count(*) from appointment where slot_id = " + Number(id)
      + " and status = 'BOOKED') from schedule_slot where id = " + Number(id))).toBe('0:' + capacity)
  } finally {
    await Promise.all(sessions.map(({ api }) => api.dispose()))
  }
})

test('AC-14 Worker outage preserves pending notifications and database recovery preserves the booking', async ({ browser }) => {
  const operator = await staffLogin(browser, 'operator-a')
  const activeSlot = await post(operator.api, '/schedule-slots', slotInput(5))
  expect(activeSlot.status()).toBe(201)
  const slot = await activeSlot.json()
  const password = readFileSync(resolve(directory, 'patient_password'), 'utf8')
  const api = await patient(3, password)
  let appointment: { id: number }
  docker('stop', 'worker')
  try {
    const response = await post(api, '/appointments', { slotId: slot.id }, randomUUID())
    expect(response.status()).toBe(201)
    appointment = await response.json()
    expect(sql("select state from outbox_event where kind = 'APPOINTMENT_BOOKED' and object_id = " + appointment.id))
      .toBe('PENDING')
  } finally {
    docker('start', 'worker')
  }
  const predicate = " where o.kind = 'APPOINTMENT_BOOKED' and o.object_id = " + appointment!.id
  await expect.poll(() => sql('select count(*) from inbox_message m join outbox_event o on o.id = m.event_id'
    + predicate), { timeout: 30_000 }).toBe('1')
  docker('stop', 'postgres')
  try {
    expect(docker('exec', '-T', 'api', 'curl', '-s', '-o', '/dev/null', '-w', '%{http_code}',
      'http://127.0.0.1:8081/actuator/health/readiness').trim()).toBe('503')
    expect(docker('exec', '-T', 'api', 'curl', '-s', '-o', '/dev/null', '-w', '%{http_code}',
      'http://127.0.0.1:8081/actuator/health/liveness').trim()).toBe('200')
  } finally {
    docker('start', 'postgres')
  }
  await expect.poll(async () => (await api.get('/api/v1/appointments/' + appointment!.id)).status(), { timeout: 30_000 })
    .toBe(200)
  expect(sql('select count(*) from inbox_message m join outbox_event o on o.id = m.event_id' + predicate)).toBe('1')
})
