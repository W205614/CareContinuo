import { expect, test } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { book, createSlot, get, loginInBrowser, post, runSql, seedBookedAppointment, signedIn } from './fixtures'

test('AC-02 final seat is allocated once and the winning request replays', async () => {
  const [ops, first, second] = await Promise.all(['operations', 'patient-a', 'patient-b'].map(signedIn))
  const contract = await get(ops, '/openapi')
  expect(contract.status()).toBe(200)
  expect((await contract.json()).paths['/api/v1/appointments'].post).toBeDefined()
  const slot = await createSlot(ops, 90)
  const key = randomUUID()
  const responses = await Promise.all([book(first, slot, key), book(second, slot, key)])
  expect(responses.map((response) => response.status()).sort()).toEqual([201, 409])
  const winner = responses[0].ok() ? first : second
  const loser = responses[0].ok() ? second : first
  const result = await responses.find((response) => response.ok())!.json()
  expect(await (await book(winner, slot, key)).json()).toEqual(result)
  expect((await get(loser, `/appointments/${result.id}`)).status()).toBe(404)
  const slots = (await (await get(ops, '/schedule')).json()).slots
  expect(slots.find((item: { id: number }) => item.id === slot).remaining).toBe(0)

  const multiple = await createSlot(ops, 95, 2)
  const ownRequests = await Promise.all([book(winner, multiple), book(winner, multiple)])
  expect(ownRequests.map((response) => response.status()).sort()).toEqual([201, 409])
  expect((await book(winner, multiple, key)).status()).toBe(409)
})

test('AC-03 rescheduling rolls back and patient/doctor can check in and complete', async ({ browser }) => {
  const [ops, patient, peer, doctor] = await Promise.all(['operations', 'patient-a', 'patient-b', 'doctor'].map(signedIn))
  const original = await createSlot(ops, 105)
  const occupied = await createSlot(ops, 110)
  const replacement = await createSlot(ops, 115)
  const first = await (await book(patient, original)).json()
  expect((await book(peer, occupied)).status()).toBe(201)
  expect((await post(patient, `/appointments/${first.id}/reschedule`, { slotId: occupied }, randomUUID())).status()).toBe(409)
  expect((await (await get(patient, `/appointments/${first.id}`)).json()).status).toBe('BOOKED')
  expect((await book(peer, original)).status()).toBe(409)

  const move = randomUUID()
  const moved = await (await post(patient, `/appointments/${first.id}/reschedule`, { slotId: replacement }, move)).json()
  expect(moved.status).toBe('BOOKED')
  expect(await (await post(patient, `/appointments/${first.id}/reschedule`, { slotId: replacement }, move)).json()).toEqual(moved)
  expect((await book(peer, original)).status()).toBe(201)
  expect((await post(patient, `/appointments/${moved.id}/check-in`)).status()).toBe(409)
  expect((await post(doctor, `/appointments/${moved.id}/start`)).status()).toBe(409)
  const cancel = randomUUID()
  for (let i = 0; i < 2; i++) expect((await post(patient, `/appointments/${moved.id}/cancel`, undefined, cancel)).status()).toBe(200)
  expect((await book(peer, replacement)).status()).toBe(201)

  const checkIn = seedBookedAppointment(3)
  const patientPage = await browser.newPage()
  await loginInBrowser(patientPage, 'patient-a')
  const patientRow = patientPage.locator(`[data-appointment-id="${checkIn}"]`)
  await patientRow.getByRole('button', { name: '签到', exact: true }).click()
  await expect(patientRow).toContainText('已签到')
  await patientPage.close()

  const doctorPage = await browser.newPage()
  await loginInBrowser(doctorPage, 'doctor')
  const doctorRow = doctorPage.locator(`[data-appointment-id="${checkIn}"]`)
  await doctorRow.getByRole('button', { name: '开始接诊' }).click()
  await doctorRow.getByRole('button', { name: '录入接诊结果' }).click()
  await doctorRow.getByLabel('模拟接诊记录').fill('合成门诊接诊记录')
  await doctorRow.getByLabel('复诊安排').fill('由医护核实复诊日期')
  await doctorRow.getByRole('button', { name: '确认完成' }).click()
  await expect(doctorRow).toContainText('已完成')
  await doctorPage.close()

  const missed = seedBookedAppointment(-20)
  await expect.poll(async () => (await (await get(patient, `/appointments/${missed}`)).json()).status,
    { timeout: 12_000 }).toBe('NO_SHOW')
  expect((await post(peer, `/appointments/${missed}/correct-attendance`, { reason: '越权' })).status()).toBe(404)
  expect((await post(doctor, `/appointments/${missed}/correct-attendance`, { reason: '核实患者已到诊' })).status()).toBe(204)
  expect(runSql(`select count(*) from appointment_action where appointment_id = ${missed} and reason is not null;`)).toBe('1')
})

test('AC-04 stopping an active schedule includes previously confirmed reservations', async ({ page }) => {
  const [ops, patient, peer] = await Promise.all(['operations', 'patient-a', 'patient-b'].map(signedIn))
  const slot = await createSlot(ops, 130, 2)
  const one = await (await book(patient, slot)).json()
  const [stop, competing] = await Promise.all([
    post(ops, `/schedule-slots/${slot}/stop`, { reason: '合成停诊演示' }), book(peer, slot),
  ])
  expect(stop.status()).toBe(204)
  expect([201, 409]).toContain(competing.status())
  expect((await book(peer, slot)).status()).toBe(409)
  const affected = await (await get(ops, '/appointments')).json()
  expect(affected.find((item: { id: number }) => item.id === one.id).status).toBe('STOPPED')
  if (competing.ok()) {
    const two = await competing.json()
    expect(affected.find((item: { id: number }) => item.id === two.id).status).toBe('STOPPED')
  }
  const work = await (await get(ops, '/work-items')).json()
  expect(work).toEqual(expect.arrayContaining([expect.objectContaining({ kind: 'STOPPED_APPOINTMENT', objectId: one.id })]))
  await expect.poll(async () => (await (await get(patient, '/inbox')).json()).some(
    (item: { kind: string; objectId: number; readAt: string | null }) => item.objectId === one.id && item.kind === 'APPOINTMENT_STOPPED' && !item.readAt,
  )).toBeTruthy()

  await loginInBrowser(page, 'operations')
  await expect(page.getByRole('heading', { name: '排班与放号' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '停诊影响记录' })).toBeVisible()
  await expect(page.locator(`[data-appointment-id="${one.id}"]`)).toContainText('合成停诊演示')
})

test('operator can publish an appointment slot from the workspace', async ({ page }) => {
  await loginInBrowser(page, 'operations')
  const shanghaiClock = Date.now() + 8 * 3_600_000 + Math.floor(Math.random() * 300) * 60_000
  const localInput = (hours: number) => new Date(shanghaiClock + hours * 3_600_000).toISOString().slice(0, 16)
  await page.getByLabel('开始时间（北京时间）').fill(localInput(24))
  await page.getByLabel('结束时间（北京时间）').fill(localInput(25))
  await page.getByLabel('号源数量').fill('3')
  await page.getByRole('button', { name: '开放号源' }).click()
  await expect(page.getByRole('status')).toHaveText('号源已开放。')
})
