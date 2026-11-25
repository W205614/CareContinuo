import { expect, test, type Browser, type Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { book, get, loginInBrowser, post, runSql, signedIn, type Session } from './fixtures'
import { fromShanghaiInput, shanghaiDate, shanghaiTime, toShanghaiInput } from '../src/time'

async function openPage(browser: Browser, username: string, phone = false) {
  const context = await browser.newContext(phone ? { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } : {})
  const page = await context.newPage()
  await loginInBrowser(page, username)
  return page
}

async function publish(owner: Session, reviewer: Session, id: number, version = 1) {
  for (const [actor, action] of [[owner, 'SUBMIT'], [reviewer, 'APPROVE'], [reviewer, 'DISPATCH']] as const) {
    expect((await post(actor, `/followups/${id}/actions`, { action, version })).status()).toBe(204)
  }
}

function textPdf(pages: string[][]) {
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    `<< /Type /Pages /Count ${pages.length} /Kids [${pages.map((_, index) => (4 + 2 * index) + ' 0 R').join(' ')}] >>`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ]
  for (const [index, lines] of pages.entries()) {
    const body = 'BT /F1 12 Tf 72 735 Td\n' + lines.map((line) => `(${line}) Tj`).join('\n0 -18 Td\n') + '\nET\n'
    objects.push(
      `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents ${5 + 2 * index} 0 R >>`,
      `<< /Length ${body.length} >>\nstream\n${body}endstream`,
    )
  }
  let pdf = '%PDF-1.4\n'
  const offsets = ['0000000000 65535 f \n']
  for (const [index, object] of objects.entries()) {
    offsets.push(`${String(pdf.length).padStart(10, '0')} 00000 n \n`)
    pdf += `${index + 1} 0 obj\n${object}\nendobj\n`
  }
  return Buffer.from(pdf + `xref\n0 ${offsets.length}\n${offsets.join('')}trailer\n<< /Size ${offsets.length} /Root 1 0 R >>\nstartxref\n${pdf.length}\n%%EOF\n`, 'ascii')
}

async function uploadPdf(owner: Session, episodeId: number, pages: string[][], documentId?: number) {
  const response = await owner.context.post(`/api/v1/episodes/${episodeId}/documents`, {
    headers: owner.headers,
    multipart: {
      ...(documentId ? { documentId: String(documentId) } : {}),
      title: '合成复诊原件',
      file: { name: 'synthetic-revisit.pdf', mimeType: 'application/pdf', buffer: textPdf(pages) },
    },
  })
  expect(response.status()).toBe(201)
}

async function prepare(owner: Session, reviewer: Session, title: string, plan: object,
                       pdf?: { pages: string[][]; quote: string }) {
  const directory = await (await get(owner, '/care-directory')).json()
  const response = await post(owner, '/episodes', {
    patientReference: 'DEMO-A', patientName: '患者甲', reference: 'PRODUCT-' + randomUUID(), careDate: shanghaiDate(new Date().toISOString()),
  })
  expect(response.status()).toBe(201)
  const episodeId = (await response.json()).id as number
  if (pdf) await uploadPdf(owner, episodeId, pdf.pages)
  else expect((await post(owner, `/episodes/${episodeId}/documents`, { title: '原出院资料', text: '原版合成资料用于验证。' })).status()).toBe(201)
  const sources = await (await get(owner, `/episodes/${episodeId}/segments`)).json()
  const cited = pdf ? sources.find((source: { excerpt: string }) => source.excerpt === pdf.quote) : sources[0]
  const draft = {
    kind: 'REVISIT', title, instructions: '按原安排办理。', dueAt: '2097-04-02T17:00:00+08:00',
    segmentIds: [cited.id], ...plan,
  }
  const created = await post(owner, '/followups', { episodeId, reviewerId: directory.reviewers[0].id, draft })
  expect(created.status()).toBe(201)
  const id = (await created.json()).id as number
  await publish(owner, reviewer, id)
  return { id, episodeId, draft }
}

async function addSlot(operator: Session, doctorId: number, value: string, capacity = 1) {
  const startsAt = new Date(Date.parse(value) + Date.now() % 900)
  const response = await post(operator, '/schedule-slots', {
    doctorId, startsAt, endsAt: new Date(startsAt.getTime() + 30 * 60_000), capacity,
  })
  expect(response.status()).toBe(201)
  return (await response.json()).id as number
}

function appointment(page: Page, id: number) {
  return page.locator(`[data-appointment-id="${id}"]`)
}

test('patient on mobile and operations handle a stopped revisit with help from the follow-up queue', async ({ browser }, testInfo) => {
  test.setTimeout(90_000)
  const [operator, patient, other, owner, reviewer] = await Promise.all(
    ['operations', 'patient-a', 'patient-b', 'followup', 'doctor'].map(signedIn))
  const overview = await (await get(operator, '/schedule')).json()
  const doctor = overview.doctors.find((item: { displayName: string }) => item.displayName === '陈医生')
  const task = await prepare(owner, reviewer, '合成复诊协助',
    { revisitFrom: '2097-03-19', revisitTo: '2097-03-19', revisitDepartmentId: doctor.departmentId })
  const slotId = await addSlot(operator, doctor.id, '2097-03-19T02:00:00Z', 2)
  const initial = await post(patient, `/followups/${task.id}/appointments`, { slotId }, randomUUID())
  expect(initial.status()).toBe(201)
  const oldId = (await initial.json()).id as number
  const otherBooking = await book(other, slotId)
  expect(otherBooking.status()).toBe(201)
  const otherId = (await otherBooking.json()).id as number
  expect((await post(operator, `/schedule-slots/${slotId}/stop`, { reason: '合成测试停诊' })).status()).toBe(204)

  await expect.poll(async () => {
    const messages = await (await get(patient, '/inbox')).json()
    return messages.some((row: { kind: string; objectId: number }) => row.kind === 'APPOINTMENT_STOPPED' && row.objectId === oldId)
  }).toBe(true)
  const patientPage = await openPage(browser, 'patient-a', true)
  await expect(patientPage.getByRole('region', { name: '需要我处理的事' })).toContainText('预约停诊')
  const home = patientPage.getByRole('region', { name: '需要我处理的事' })
  await expect(home).toBeInViewport()
  expect(await patientPage.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await home.screenshot({ path: testInfo.outputPath('patient-home.png') })
  const message = patientPage.getByRole('region', { name: '站内消息' }).getByRole('listitem').filter({ hasText: '门诊停诊' }).first()
  await message.getByRole('button', { name: '处理停诊' }).click()
  await expect(patientPage).toHaveURL(new RegExp('appointment=' + oldId))
  const originalCard = appointment(patientPage, oldId)
  await expect(originalCard).toContainText('停诊待处理')
  const callbackProgress = originalCard.getByRole('region', { name: '停诊回电' })
  await expect(callbackProgress).toContainText('暂未安排回电')
  await originalCard.getByRole('button', { name: '改约', exact: true }).click()
  await patientPage.getByRole('button', { name: '申请复诊协助' }).click()
  await expect(patientPage.getByText('复诊协助 · 已提交')).toBeVisible()
  await expect(patientPage).toHaveURL(new RegExp(`appointment=${oldId}$`))
  const duplicate = await post(patient, `/followups/${task.id}/requests`, { category: 'REVISIT_ASSISTANCE', version: 1 })
  expect(duplicate.status()).toBe(200)
  const requestId = (await duplicate.json()).id
  const latest = await (await get(patient, `/followups/${task.id}`)).json()
  expect(latest.requests.map((item: { id: number }) => item.id)).toEqual([requestId])
  const foreign = await get(other, `/appointments/${oldId}`)
  expect(foreign.status()).toBe(404)
  expect((await post(other, `/followups/${task.id}/requests`, { category: 'REVISIT_ASSISTANCE', version: 1 })).status()).toBe(404)
  expect([403, 404]).toContain((await get(patient, `/appointments/${oldId}/stop-contacts`)).status())

  const opsPage = await openPage(browser, 'operations')
  const stops = opsPage.getByRole('region', { name: '停诊影响记录' })
  await expect(appointment(opsPage, oldId)).toContainText('患者已读')
  await expect(appointment(opsPage, otherId)).toContainText('患者未读')
  await appointment(opsPage, oldId).getByRole('button', { name: '查看联系记录' }).click()
  await appointment(opsPage, oldId).getByLabel('联系结果').selectOption('CALL_BACK')
  const callbackAt = toShanghaiInput(new Date(Date.now() + 3_600_000).toISOString())
  const callbackOwnerId = runSql("select id from app_user where username = 'operations'")
  await appointment(opsPage, oldId).getByLabel('回电负责人').selectOption(callbackOwnerId)
  await appointment(opsPage, oldId).getByLabel('回电时间（北京时间）').fill(callbackAt)
  await appointment(opsPage, oldId).getByLabel('联系备注').fill('合成回访，约定回电。')
  await appointment(opsPage, oldId).getByRole('button', { name: '保存联系记录' }).click()
  await expect(appointment(opsPage, oldId)).toContainText('合成回访，约定回电。')
  await expect(appointment(opsPage, oldId)).toContainText('待回电 · 排班运营')
  await expect(callbackProgress).toContainText('已安排回电', { timeout: 10000 })
  await expect(callbackProgress).toContainText(shanghaiTime(fromShanghaiInput(callbackAt)))
  await expect(callbackProgress).not.toContainText('排班运营')
  await expect(callbackProgress).not.toContainText('合成回访')
  await callbackProgress.screenshot({ path: testInfo.outputPath('mobile-callback-scheduled.png') })
  const progressPath = `/appointments/${oldId}/callback-status`
  const visible = await (await get(patient, progressPath)).json()
  expect(visible).toEqual({ status: 'SCHEDULED', expectedAt: expect.any(String) })
  expect(Date.parse(visible.expectedAt)).toBe(Date.parse(fromShanghaiInput(callbackAt)))
  expect((await get(other, progressPath)).status()).toBe(404)
  expect((await get(reviewer, progressPath)).status()).toBe(404)
  const week = new Date(Date.now() + 7 * 86_400_000).toISOString()
  const callbackPath = (id: number) => `/appointments/${id}/stop-contacts`
  const callbackMessageCount = () => Number(runSql(`select count(*) from outbox_event where kind = 'APPOINTMENT_CALLBACK_UPDATED' and object_id = ${oldId}`))
  expect((await post(operator, callbackPath(otherId), { outcome: 'CALL_BACK' })).status()).toBe(400)
  const secondOwner = Number(runSql(`
    insert into app_user(username, display_name, password_hash)
    select 'p0-callback-peer', '合成回电专员', password_hash from app_user where username = 'operations'
    on conflict (username) do update set enabled = true returning id;
  `))
  runSql(`insert into user_role(user_id, role) values (${secondOwner}, 'OPERATIONS') on conflict do nothing;`)
  const operatorPeer = await signedIn('p0-callback-peer')
  const assign = (id: number, callbackOwnerId: number) => post(operator, callbackPath(id),
    { outcome: 'CALL_BACK', callbackOwnerId, callbackDueAt: week })
  const readCallbacks = async (session: Session, state: string) => (await (await get(session,
    `/work-items/page?kind=STOP_CALLBACK&state=${state}&departmentId=${doctor.departmentId}`)).json()).work
  expect((await assign(oldId, secondOwner)).status()).toBe(204)
  expect((await readCallbacks(operator, 'OPEN')).map((row: { objectId: number }) => row.objectId)).not.toContain(oldId)
  expect(await readCallbacks(operatorPeer, 'OPEN')).toContainEqual(expect.objectContaining({
    objectId: oldId, owner: '合成回电专员', targetType: 'appointment',
  }))
  expect((await post(operator, callbackPath(oldId), { outcome: 'REACHED' })).status()).toBe(403)
  const callbackPage = await openPage(browser, 'p0-callback-peer')
  const callbackRow = callbackPage.getByRole('region', { name: '工作待办' }).getByRole('listitem')
    .filter({ hasText: '停诊回电' }).filter({ has: callbackPage.locator(`a[href="/workspace?appointment=${oldId}"]`) })
  await callbackRow.getByRole('link', { name: '打开处理' }).click()
  const callbackCase = appointment(callbackPage, oldId)
  await expect(callbackCase).toContainText('待回电 · 合成回电专员')
  await callbackCase.getByLabel('联系结果').selectOption('NO_ANSWER')
  await callbackCase.getByRole('button', { name: '保存联系记录' }).click()
  await expect(callbackCase).toContainText('未接通')
  await expect(callbackRow).toBeVisible()
  await expect(callbackProgress).toContainText('未接通，待后续联系', { timeout: 10000 })
  await expect(callbackProgress).toContainText('新的回电时间待确认')
  await expect(callbackProgress).toContainText('工作日 09:00–17:00')
  await expect(callbackProgress).not.toContainText(shanghaiTime(week))
  expect(await (await get(patient, progressPath)).json()).toEqual({ status: 'NO_ANSWER', expectedAt: null })
  const messagesBeforeDuplicate = callbackMessageCount()
  expect((await post(operatorPeer, callbackPath(oldId), { outcome: 'NO_ANSWER' })).status()).toBe(204)
  expect(callbackMessageCount()).toBe(messagesBeforeDuplicate)

  await patientPage.goto('/workspace')
  const newCallback = new Date(Date.parse(week) + 86_400_000).toISOString()
  expect((await post(operatorPeer, callbackPath(oldId), {
    outcome: 'CALL_BACK', callbackOwnerId: secondOwner, callbackDueAt: newCallback,
  })).status()).toBe(204)
  await expect.poll(() => runSql(`
    select count(*) from inbox_message m join outbox_event e on e.id = m.event_id
    where e.kind = 'APPOINTMENT_CALLBACK_UPDATED' and e.object_id = ${oldId};
  `)).toBe(String(messagesBeforeDuplicate + 1))
  await home.getByRole('button', { name: '刷新事项' }).click()
  const callbackNotification = home.locator(`[data-callback-appointment="${oldId}"]`)
  await expect(callbackNotification).toHaveCount(1)
  await callbackNotification.getByRole('button', { name: '查看回电进展' }).click()
  await expect(patientPage).toHaveURL(new RegExp(`appointment=${oldId}$`))
  await expect(callbackProgress).toContainText(shanghaiTime(newCallback))
  expect((await (await get(other, '/inbox/highlights')).json()).some(
    (item: { kind: string; objectId: number }) => item.kind === 'APPOINTMENT_CALLBACK_UPDATED' && item.objectId === oldId)).toBe(false)
  await callbackCase.getByLabel('联系结果').selectOption('REACHED')
  await callbackCase.getByRole('button', { name: '保存联系记录' }).click()
  await expect(callbackCase).toContainText('回电已关闭 · 已联系到患者')
  await expect(callbackRow).toHaveCount(0)
  await expect(callbackProgress).toContainText('已联系', { timeout: 10000 })
  await expect(callbackProgress).not.toContainText('预计回电时间')
  expect((await (await get(patient, `/appointments/${oldId}`)).json()).status).toBe('STOPPED')
  expect((await assign(oldId, Number(callbackOwnerId))).status()).toBe(204)
  expect((await assign(otherId, Number(callbackOwnerId))).status()).toBe(204)
  await stops.getByRole('button', { name: '刷新停诊清单' }).click()
  await expect(appointment(opsPage, oldId).getByRole('list')).toContainText('合成回电专员')
  await stops.screenshot({ path: testInfo.outputPath('operations-stop-list.png') })

  const staffPage = await openPage(browser, 'followup')
  const work = staffPage.getByRole('region', { name: '工作待办' })
    .getByRole('listitem').filter({ has: staffPage.locator(`a[href*="request=${requestId}"]`) })
  await work.getByRole('link', { name: '打开处理' }).click()
  const caseRow = staffPage.locator('#followup-request-' + requestId)
  await caseRow.getByRole('button', { name: '开始处理' }).click()
  await expect(caseRow).toContainText('处理中')
  const replacementSlot = await addSlot(operator, doctor.id, '2097-03-19T03:00:00Z')
  await caseRow.getByRole('button', { name: '回复患者' }).click()
  await caseRow.getByLabel('患者可见回复').fill('同科室已放出合成复诊新号，请自行确认改约。')
  await caseRow.getByRole('button', { name: '发布回复' }).click()
  await expect(caseRow).toContainText('复诊协助 · 已处理')
  await expect.poll(async () => (await (await get(patient, '/inbox/revisit-replies')).json()).some(
    (reply: { revisitRequestId: number }) => reply.revisitRequestId === requestId)).toBe(true)
  const unreadReply = (await (await get(patient, '/inbox/revisit-replies')).json()).find(
    (reply: { revisitRequestId: number }) => reply.revisitRequestId === requestId)
  expect((await (await get(other, '/inbox/revisit-replies')).json()).some(
    (reply: { objectId: number }) => reply.objectId === task.id)).toBe(false)
  await patientPage.goto('/workspace')
  const replyCard = home.locator(`[data-reply-task="${task.id}"]`)
  await expect(replyCard).toContainText('复诊协助有新回复')
  const priority = home.getByRole('list', { name: '优先处理事项' })
  await expect(home.getByRole('list').first()).toHaveAccessibleName('优先处理事项')
  await expect(priority.locator(`a[href="/workspace?appointment=${oldId}"]`)).toBeVisible()
  await expect(replyCard).toBeInViewport()
  await home.screenshot({ path: testInfo.outputPath('mobile-home-priority.png') })
  await replyCard.screenshot({ path: testInfo.outputPath('patient-reply-on-home.png') })
  await replyCard.getByRole('button', { name: '查看回复与号源' }).click()
  await expect(patientPage).toHaveURL(new RegExp(`followup=${task.id}&request=${requestId}`))
  await expect(patientPage.getByText('同科室已放出合成复诊新号，请自行确认改约。')).toBeVisible()
  await expect(replyCard).toHaveCount(0)
  expect((await (await get(patient, '/inbox/' + unreadReply.id)).json()).readAt).toBeTruthy()
  const options = patientPage.getByRole('region', { name: '复诊号源' })
  const confirm = options.getByRole('button', { name: '确认改约' })
  await expect(confirm).toBeInViewport()
  await expect(options.getByLabel('新复诊时段')).toHaveValue(String(replacementSlot))
  await options.screenshot({ path: testInfo.outputPath('patient-inline-reschedule.png') })
  await confirm.click()
  await expect(options.getByRole('status')).toContainText('改约已确认')
  await expect(patientPage).toHaveURL(new RegExp(`followup=${task.id}&request=${requestId}`))
  await expect(home.locator(`a[href="/workspace?appointment=${oldId}"]`)).toHaveCount(0)
  expect((await (await get(patient, `/appointments/${oldId}`)).json()).status).toBe('RESCHEDULED')
  expect(runSql(`select remaining from schedule_slot where id = ${replacementSlot}`)).toBe('0')
  expect((await post(other, `/appointments/${otherId}/cancel`, undefined, randomUUID())).status()).toBe(200)
  await stops.getByLabel('处理状态').selectOption({ label: '已处理' })
  await expect(appointment(opsPage, oldId)).toContainText('已改约')
  await expect(appointment(opsPage, otherId)).toContainText('已取消')
  await expect(appointment(opsPage, oldId)).toContainText('回电已关闭 · 患者已改约')
  await expect(appointment(opsPage, otherId)).toContainText('回电已关闭 · 患者已取消')
  await patientPage.goto(`/workspace?appointment=${oldId}`)
  await expect(callbackProgress).toContainText('已改约，无需回电')
  expect(await (await get(other, `/appointments/${otherId}/callback-status`)).json()).toEqual({
    status: 'CANCELLED', expectedAt: null,
  })
  const closed = await readCallbacks(operator, 'DONE')
  expect(closed).toEqual(expect.arrayContaining([
    expect.objectContaining({ objectId: oldId, resolution: '患者已改约' }),
    expect.objectContaining({ objectId: otherId, resolution: '患者已取消' }),
  ]))
  expect((await assign(oldId, secondOwner)).status()).toBe(409)
  const history = await (await get(operator, callbackPath(oldId))).json()
  expect(history).toEqual(expect.arrayContaining([
    expect.objectContaining({ outcome: 'CALL_BACK', callbackOwner: '合成回电专员', callbackDueAt: expect.any(String) }),
    expect.objectContaining({ outcome: 'NO_ANSWER', operator: '合成回电专员' }),
    expect.objectContaining({ outcome: 'REACHED', operator: '合成回电专员' }),
  ]))
  await Promise.all([patientPage, opsPage, staffPage, callbackPage].map(page => page.context().close()))
})

test('mobile inline rescheduling keeps a booked appointment when another patient takes the last seat', async ({ browser }, testInfo) => {
  const [operator, patient, other, owner, reviewer] = await Promise.all(
    ['operations', 'patient-a', 'patient-b', 'followup', 'doctor'].map(signedIn))
  const overview = await (await get(operator, '/schedule')).json()
  const doctor = overview.doctors.find((item: { displayName: string }) => item.displayName === '陈医生')
  const task = await prepare(owner, reviewer, '合成改约抢号', {
    revisitFrom: '2097-03-24', revisitTo: '2097-03-24', revisitDepartmentId: doctor.departmentId,
  })
  const path = `/followups/${task.id}`
  const originalSlot = await addSlot(operator, doctor.id, '2097-03-24T02:00:00Z')
  const original = await post(patient, path + '/appointments', { slotId: originalSlot }, randomUUID())
  expect(original.status()).toBe(201)
  const { id: originalId } = await original.json()
  expect((await post(owner, `/episodes/${task.episodeId}/documents`, {
    title: '合成改约日期变更', text: '改为 2097-03-25 复诊。',
  })).status()).toBe(201)
  const segments = await (await get(owner, `/episodes/${task.episodeId}/segments`)).json()
  const updated = segments.find((source: { title: string }) => source.title === '合成改约日期变更')
  expect((await post(owner, path + '/draft', { version: 1, draft: {
    ...task.draft, revisitFrom: '2097-03-25', revisitTo: '2097-03-25', segmentIds: [updated.id],
  } })).status()).toBe(204)
  await publish(owner, reviewer, task.id, 2)
  const request = await post(patient, path + '/requests', { category: 'REVISIT_ASSISTANCE', version: 2 })
  expect(request.status()).toBe(200)
  const { id: requestId } = await request.json()
  const replacementSlot = await addSlot(operator, doctor.id, '2097-03-25T02:00:00Z')
  const wrongDay = await addSlot(operator, doctor.id, '2097-03-26T02:00:00Z')
  expect((await post(owner, path + '/requests/' + requestId + '/actions', {
    action: 'REPLY', version: 2, outcome: 'SLOTS_AVAILABLE', reply: '已开放符合更正安排的合成号源。',
  })).status()).toBe(204)
  await expect.poll(async () => (await (await get(patient, '/inbox/revisit-replies')).json()).some(
    (reply: { revisitRequestId: number }) => reply.revisitRequestId === requestId)).toBe(true)
  const page = await openPage(browser, 'patient-a', true)
  await page.locator(`[data-reply-task="${task.id}"]`).getByRole('button', { name: '查看回复与号源' }).click()
  const options = page.getByRole('region', { name: '复诊号源' })
  await expect(options.getByLabel('新复诊时段').locator('option')).toHaveCount(1)
  await expect(options.getByLabel('新复诊时段')).toHaveValue(String(replacementSlot))
  const competing = await book(other, replacementSlot)
  expect(competing.status()).toBe(201)
  await options.getByRole('button', { name: '确认改约' }).click()
  await expect(options.getByRole('alert')).toContainText('原预约当前状态为「已预约」')
  await expect(options.getByRole('alert')).toBeInViewport()
  await options.screenshot({ path: testInfo.outputPath('patient-inline-race.png') })
  const visits = await (await get(patient, path + '/appointments')).json()
  expect(visits).toEqual([expect.objectContaining({ appointmentId: originalId, status: 'BOOKED' })])
  const remaining = (id: number) => runSql(`select remaining from schedule_slot where id = ${id}`)
  expect(remaining(originalSlot)).toBe('0')
  expect(remaining(replacementSlot)).toBe('0')
  expect(remaining(wrongDay)).toBe('1')
  const { id: competingId } = await competing.json()
  expect((await post(other, `/appointments/${competingId}/cancel`, undefined, randomUUID())).status()).toBe(200)
  await options.getByRole('button', { name: '刷新复诊号源' }).click()
  await options.getByRole('button', { name: '确认改约' }).click()
  await expect(options.getByRole('status')).toContainText('改约已确认')
  expect(await (await get(patient, path + '/appointments')).json()).toEqual([
    expect.objectContaining({ status: 'BOOKED', supersedesId: originalId }),
    expect.objectContaining({ appointmentId: originalId, status: 'RESCHEDULED' }),
  ])
  expect(remaining(originalSlot)).toBe('1')
  expect(remaining(replacementSlot)).toBe('0')
  await page.context().close()
})

test('P0 an ordinary booked revisit keeps its original seat through no-slot assistance and a failed move', async ({ browser }, testInfo) => {
  test.setTimeout(60_000)
  const [operator, patient, other, owner, reviewer] = await Promise.all(
    ['operations', 'patient-a', 'patient-b', 'followup', 'doctor'].map(signedIn))
  const schedule = await (await get(operator, '/schedule')).json()
  const doctor = schedule.doctors.find((person: { displayName: string }) => person.displayName === '陈医生')
  const task = await prepare(owner, reviewer, '合成普通复诊改约协助', {
    revisitFrom: '2097-04-14', revisitTo: '2097-04-14', revisitDepartmentId: doctor.departmentId,
  })
  const path = `/followups/${task.id}`
  const oldSlot = await addSlot(operator, doctor.id, '2097-04-14T02:00:00Z')
  const original = await post(patient, path + '/appointments', { slotId: oldSlot }, randomUUID())
  expect(original.status()).toBe(201)
  const { id: originalId } = await original.json()
  expect((await post(patient, path + '/requests',
    { version: 1, category: 'REVISIT_ASSISTANCE', appointmentId: originalId + 98765432 })).status()).toBe(404)

  const mobile = await openPage(browser, 'patient-a', true)
  await mobile.goto(`/workspace?appointment=${originalId}`)
  const card = appointment(mobile, originalId)
  await expect(card).toContainText('签到尚未开始')
  await expect(card).toContainText('退改约截止')
  await card.screenshot({ path: testInfo.outputPath('mobile-booked-revisit.png') })
  await card.getByRole('button', { name: '改约', exact: true }).click()
  const inline = mobile.getByRole('region', { name: '选择新的时段' })
  const apply = inline.getByRole('button', { name: '申请复诊协助' })
  await expect(inline).toContainText('原预约保留到改约成功')
  await expect(apply).toBeInViewport()
  await inline.screenshot({ path: testInfo.outputPath('mobile-inline-assistance.png') })
  await apply.click()
  await expect(inline.getByRole('status')).toContainText('复诊协助 · 已提交')
  await expect(mobile).toHaveURL(new RegExp(`appointment=${originalId}$`))
  await expect(inline.getByRole('link', { name: '查看协助进度' })).toBeVisible()
  await expect(apply).toHaveCount(0)
  const options = mobile.getByRole('region', { name: '复诊号源' })
  const request = (await (await get(patient, path)).json()).requests[0]
  expect(request.appointmentId).toBe(originalId)
  const snapshot = async () => (await (await get(patient, `/appointments/${originalId}`)).json()).status
  expect(await snapshot()).toBe('BOOKED')
  expect(runSql(`select remaining from schedule_slot where id = ${oldSlot}`)).toBe('0')

  const newSlot = await addSlot(operator, doctor.id, '2097-04-14T03:00:00Z')
  expect((await post(owner, path + `/requests/${request.id}/actions`, {
    action: 'REPLY', version: 1, outcome: 'SLOTS_AVAILABLE', reply: '合成新号已开放，请从此处继续改约。',
  })).status()).toBe(204)
  await expect.poll(async () => (await (await get(patient, '/inbox/revisit-replies')).json())
    .some((row: { objectId: number }) => row.objectId === task.id)).toBe(true)
  await mobile.goto('/workspace')
  await mobile.locator(`[data-reply-task="${task.id}"]`).getByRole('button', { name: '查看回复与号源' }).click()
  await expect(options.getByLabel('新复诊时段')).toHaveValue(String(newSlot))
  expect((await book(other, newSlot)).status()).toBe(201)
  await options.getByRole('button', { name: '确认改约' }).click()
  await expect(options.getByRole('alert')).toContainText('原预约当前状态为「已预约」')
  expect(await snapshot()).toBe('BOOKED')
  expect(runSql(`select string_agg(remaining::text, ',' order by id) from schedule_slot where id in (${oldSlot}, ${newSlot})`)).toBe('0,0')
  expect(runSql(`select count(*) from appointment where supersedes_id = ${originalId}`)).toBe('0')
  expect(await mobile.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await options.screenshot({ path: testInfo.outputPath('mobile-revisit-kept.png') })

  const freshSlot = await addSlot(operator, doctor.id, '2097-04-14T04:00:00Z')
  await options.getByRole('button', { name: '刷新复诊号源' }).click()
  await expect(options.getByLabel('新复诊时段')).toHaveValue(String(freshSlot))
  await options.getByRole('button', { name: '确认改约' }).click()
  await expect(options.getByRole('status')).toContainText('改约已确认')
  expect(await snapshot()).toBe('RESCHEDULED')
  expect(runSql(`select remaining from schedule_slot where id = ${oldSlot}`)).toBe('1')
  expect(runSql(`select count(*) from appointment where supersedes_id = ${originalId} and status = 'BOOKED'`)).toBe('1')
  await mobile.context().close()
})

test('first-booking help survives transfer and correction and closes after self-booking', async () => {
  const [patient, owner, reviewer, operator] = await Promise.all(['patient-a', 'followup', 'doctor', 'operations'].map(signedIn))
  const overview = await (await get(operator, '/schedule')).json()
  const doctor = overview.doctors.find((item: { displayName: string }) => item.displayName === '陈医生')
  const task = await prepare(owner, reviewer, '合成首次复诊无号',
    { revisitFrom: '2097-03-29', revisitTo: '2097-03-29', revisitDepartmentId: doctor.departmentId })
  const path = `/followups/${task.id}`
  expect((await (await get(patient, path + '/revisit-slots')).json()).slots).toEqual([])
  const request = () => post(patient, path + '/requests', { category: 'REVISIT_ASSISTANCE', version: 1 })
  const submissions = await Promise.all([request(), request()])
  for (const result of submissions) expect(result.status()).toBe(200)
  const [first, duplicate] = await Promise.all(submissions.map((response) => response.json()))
  expect(duplicate.id).toBe(first.id)
  const actionPath = path + '/requests/' + first.id + '/actions'
  expect((await post(owner, actionPath, { action: 'START', version: 1 })).status()).toBe(204)

  const username = 'product-followup-' + randomUUID()
  runSql(`with account as (
    insert into app_user(username, password_hash, display_name)
    select '${username}', password_hash, '合成接手人' from app_user where username = 'followup' returning id
  ) insert into user_role(user_id, role) select id, 'FOLLOWUP' from account;`)
  const replacement = await signedIn(username)
  const { id: replacementId } = await (await get(replacement, '/session')).json()
  expect((await post(owner, path + '/actions', { action: 'TRANSFER', version: 1, newOwnerId: replacementId, note: '合成交接' })).status()).toBe(204)
  expect((await post(owner, actionPath, { action: 'START', version: 1 })).status()).toBe(404)
  const queue = await (await get(replacement, '/work-items/page?state=OPEN&kind=FOLLOWUP_REQUEST')).json()
  expect(queue.work).toEqual(expect.arrayContaining([expect.objectContaining({ objectId: first.id, targetId: task.id })]))
  expect((await (await get(replacement, path)).json()).requests[0].status).toBe('IN_PROGRESS')

  expect((await post(replacement, `/episodes/${task.episodeId}/documents`, {
    title: '合成复诊日期更新', text: '新版安排为 2097-03-30。',
  })).status()).toBe(201)
  expect((await post(replacement, actionPath, { action: 'REPLY', outcome: 'NO_SLOTS', reply: '过时回复', version: 1 })).status()).toBe(409)
  const waiting = await (await get(patient, path)).json()
  expect(waiting.needsReview).toBe(true)
  expect(waiting.requests[0].status).toBe('IN_PROGRESS')
  const segments = await (await get(replacement, `/episodes/${task.episodeId}/segments`)).json()
  const newSource = segments.find((segment: { title: string }) => segment.title === '合成复诊日期更新')
  expect((await post(replacement, path + '/draft', { version: 1, draft: {
    ...task.draft, revisitFrom: '2097-03-30', revisitTo: '2097-03-30', segmentIds: [newSource.id],
  } })).status()).toBe(204)
  await publish(replacement, reviewer, task.id, 2)
  const wrong = await addSlot(operator, doctor.id, '2097-03-29T02:00:00Z')
  const right = await addSlot(operator, doctor.id, '2097-03-30T02:00:00Z')
  const options = await (await get(patient, path + '/revisit-slots')).json()
  expect(options.slots.map((slot: { id: number }) => slot.id)).toEqual([right])
  expect((await post(patient, path + '/appointments', { slotId: wrong }, randomUUID())).status()).toBe(409)
  expect((await post(patient, path + '/appointments', { slotId: right }, randomUUID())).status()).toBe(201)
  expect((await (await get(patient, path)).json()).requests[0]).toMatchObject({ status: 'RESOLVED', outcome: 'BOOKED' })
  const handled = await (await get(replacement, '/work-items/page?state=DONE&kind=FOLLOWUP_REQUEST')).json()
  expect(handled.work).toEqual(expect.arrayContaining([expect.objectContaining({ objectId: first.id, resolution: '患者已预约' })]))
})

test('staff answer a patient question and publish check-result progress separately from internal notes', async ({ browser }) => {
  const [patient, owner, reviewer, other] = await Promise.all(['patient-a', 'followup', 'doctor', 'patient-b'].map(signedIn))
  const task = await prepare(owner, reviewer, '合成待出检查结果', { kind: 'PENDING_RESULT' })
  const patientPage = await openPage(browser, 'patient-a', true)
  const home = patientPage.getByRole('region', { name: '需要我处理的事' })
  const entry = home.locator(`a[href="/workspace?followup=${task.id}"]`)
  await expect(home.getByRole('listitem').first()).toBeVisible()
  if (!(await entry.isVisible())) await home.getByRole('button', { name: '显示更多事项' }).click()
  await entry.click()
  await expect(patientPage.getByText('检查结果进度 · 等待结果')).toBeVisible()
  await patientPage.getByRole('button', { name: '填写反馈或联系医护' }).click()
  await patientPage.getByLabel('反馈类型').selectOption('QUESTION')
  await patientPage.getByLabel('补充说明').fill('什么时候可以查询合成检查结果？')
  await patientPage.getByRole('button', { name: '提交反馈' }).click()
  await expect(patientPage.getByText('有疑问 · 已提交')).toBeVisible()
  const details = await (await get(patient, `/followups/${task.id}`)).json()
  const requestId = details.requests[0].id
  expect((await post(owner, `/followups/${task.id}/actions`, { action: 'CONTACT', version: 1, note: '仅供工作人员查阅的合成备注' })).status()).toBe(204)
  const staffPage = await openPage(browser, 'followup')
  await staffPage.locator(`a[href*="request=${requestId}"]`).click()
  await staffPage.locator('#followup-request-' + requestId).getByRole('button', { name: '开始处理' }).click()
  await expect(staffPage.locator('#followup-request-' + requestId)).toContainText('处理中')
  expect((await post(owner, `/followups/${task.id}/requests/${requestId}/actions`, { action: 'START', version: 1 })).status()).toBe(204)
  await staffPage.getByRole('button', { name: '更新检查结果进度' }).click()
  await staffPage.getByRole('button', { name: '发布进度' }).click()
  await expect(staffPage.getByText('检查结果进度 · 医护核对中')).toBeVisible()
  const caseRow = staffPage.locator('#followup-request-' + requestId)
  await caseRow.getByRole('button', { name: '回复患者' }).click()
  await caseRow.getByLabel('患者可见回复').fill('合成检查结果已复核，请查看随访说明。')
  await caseRow.getByRole('button', { name: '发布回复' }).click()
  await expect(caseRow).toContainText('有疑问 · 已处理')
  await staffPage.getByRole('button', { name: '更新检查结果进度' }).click()
  await staffPage.getByLabel('检查结果进度').selectOption('PLAN_AVAILABLE')
  await staffPage.getByLabel('患者可见说明').fill('请在约定时段联系随访办公室。')
  await staffPage.getByRole('button', { name: '发布进度' }).click()
  await expect(staffPage.getByText('检查结果进度 · 已有后续安排')).toBeVisible()
  const resultPath = `/followups/${task.id}/result-updates`
  expect((await post(owner, resultPath, {
    version: 1, stage: 'PLAN_AVAILABLE', message: '请在约定时段联系随访办公室。',
  })).status()).toBe(204)
  const progressMessages = async (session: Session) => (await (await get(session, '/inbox/followup-progress')).json())
    .filter((message: { objectId: number }) => message.objectId === task.id)
  await expect.poll(async () => (await progressMessages(patient)).map((m: { kind: string }) => m.kind).sort()).toEqual([
    'FOLLOWUP_REPLY', 'FOLLOWUP_REQUEST_STARTED', 'FOLLOWUP_RESULT_UPDATED', 'FOLLOWUP_RESULT_UPDATED',
  ].sort())
  expect(await progressMessages(other)).toEqual([])
  await patientPage.goto('/workspace')
  const progress = home.locator(`[data-progress-task="${task.id}"]`)
  const resultCard = progress.filter({ hasText: '检查结果进度已更新' })
  await expect(progress).toHaveCount(1)
  await expect(resultCard).toBeVisible()
  await resultCard.getByRole('button', { name: '查看随访进展' }).click()
  await expect(patientPage).toHaveURL(new RegExp(`followup=${task.id}&progress=result`))
  await expect(patientPage.locator('#followup-result-progress')).toBeFocused()
  await expect(patientPage.getByText('检查结果进度 · 已有后续安排')).toBeVisible()
  await expect(patientPage.getByText('请在约定时段联系随访办公室。')).toBeVisible()
  await expect(patientPage.getByText('有疑问 · 已处理')).toBeVisible()
  await expect(patientPage.getByText('仅供工作人员查阅的合成备注')).toHaveCount(0)
  await expect(progress).toHaveCount(0)
  await patientPage.goto('/workspace')
  await expect(progress).toHaveCount(0)
  const inbox = patientPage.getByRole('region', { name: '站内消息' })
  const priorReply = inbox.getByRole('listitem').filter({ hasText: '随访有新的医护回复' }).first()
  await expect(priorReply).toContainText('未读')
  await priorReply.getByRole('button', { name: '查看随访安排' }).click()
  await expect(patientPage.locator('#followup-request-' + requestId)).toBeFocused()
  expect(JSON.stringify(await progressMessages(patient))).not.toContain('仅供工作人员查阅')
  await expect.poll(async () => (await (await get(patient, '/inbox')).json()).some(
    (reply: { objectId: number; kind: string }) => reply.objectId === task.id && reply.kind === 'FOLLOWUP_REPLY')).toBe(true)
  expect((await (await get(patient, '/inbox/revisit-replies')).json()).some(
    (reply: { objectId: number }) => reply.objectId === task.id)).toBe(false)
  await Promise.all([patientPage, staffPage].map(page => page.context().close()))
})

test('reviewer compares old and corrected sources, dates and departments before publishing', async ({ browser }, testInfo) => {
  const [patient, owner, reviewer, operator] = await Promise.all(['patient-a', 'followup', 'doctor', 'operations'].map(signedIn))
  const overview = await (await get(operator, '/schedule')).json()
  const doctor = overview.doctors.find((item: { displayName: string }) => item.displayName === '陈医生')
  const oldCitation = 'Original appointment on 2097-03-22 at General Clinic.'
  const newCitation = 'Corrected appointment on 2097-03-23 at Review Clinic.'
  const task = await prepare(owner, reviewer, '合成复诊更正',
    { revisitFrom: '2097-03-22', revisitTo: '2097-03-22', revisitDepartmentId: doctor.departmentId }, {
      quote: oldCitation,
      pages: [['Old cover page', 'Unrelated line two'], ['Old page before citation', oldCitation, 'Old page after citation']],
    })
  const slot = await addSlot(operator, doctor.id, '2097-03-22T02:00:00Z')
  const booked = await post(patient, `/followups/${task.id}/appointments`, { slotId: slot }, randomUUID())
  expect(booked.status()).toBe(201)
  const appointmentId = (await booked.json()).id as number
  const department = (await (await post(operator, '/departments', { name: '合成更正后科室' })).json()).id
  const documents = await (await get(owner, `/episodes/${task.episodeId}/documents`)).json()
  await uploadPdf(owner, task.episodeId, [
    ['New cover page'], ['New second page', 'Unrelated line two', 'Unrelated line three'],
    ['New context header', 'New page before citation', newCitation, 'New page after citation'],
  ], documents[0].id)
  const textCitation = '复诊请携带本次合成资料。'
  expect((await post(owner, `/episodes/${task.episodeId}/documents`, {
    title: '合成补充文本', text: '补充说明\n\n请阅读以下提示\n' + textCitation + '\n文本末行',
  })).status()).toBe(201)
  const segments = await (await get(owner, `/episodes/${task.episodeId}/segments`)).json()
  const pdfSource = segments.find((source: { excerpt: string }) => source.excerpt === newCitation)
  const textSource = segments.find((source: { excerpt: string }) => source.excerpt === textCitation)
  expect((await post(owner, `/followups/${task.id}/draft`, { version: 1, draft: {
    ...task.draft, segmentIds: [pdfSource.id, textSource.id], instructions: '请以更正版复诊要求为准。',
    revisitFrom: '2097-03-23', revisitTo: '2097-03-23', revisitDepartmentId: department,
  } })).status()).toBe(204)
  expect((await post(owner, `/followups/${task.id}/actions`, { action: 'SUBMIT', version: 2 })).status()).toBe(204)
  const patientPage = await openPage(browser, 'patient-a', true)
  const home = patientPage.getByRole('region', { name: '需要我处理的事' })
  await home.locator(`a[href="/workspace?followup=${task.id}"]`).click()
  await expect(patientPage.getByText('资料已更正，等待医护确认。')).toBeVisible()
  await expect(patientPage.getByText('请以更正版复诊要求为准。')).toHaveCount(0)

  const reviewerPage = await openPage(browser, 'doctor')
  await reviewerPage.locator(`a[href="/workspace?followup=${task.id}"]`).click()
  const diff = reviewerPage.getByRole('region', { name: '审核版本对比' })
  for (const label of ['上次派发', '当前草稿', oldCitation, newCitation, '合成更正后科室', '2097-03-22', '2097-03-23']) {
    await expect(diff).toContainText(label)
  }
  const summary = diff.getByRole('region', { name: '变更摘要' })
  await expect(diff.getByRole('heading', { level: 5 }).first()).toHaveText('变更项')
  for (const label of ['全科门诊 → 合成更正后科室', '2097-03-22 → 2097-03-23', '患者须知', '原文引用', '与新安排不符的预约（1）', '已预约']) {
    await expect(summary).toContainText(label)
  }
  await diff.getByRole('button', { name: '并排核对新旧原文' }).click()
  const sideBySide = diff.getByRole('region', { name: '并排原文' })
  await expect(sideBySide.locator('mark')).toHaveText([oldCitation, newCitation])
  await diff.getByRole('button', { name: '收起原文对照' }).click()
  await diff.screenshot({ path: testInfo.outputPath('medical-review-comparison.png') })

  const oldSource = diff.getByRole('button', { name: /版本 1.*第 2 页.*第 2 行/ })
  await oldSource.click()
  const locator = reviewerPage.getByRole('dialog', { name: '原文定位' })
  const targetLine = locator.locator('[aria-current="true"]')
  await expect(locator).toContainText('合成复诊原件 · 版本 1')
  await expect(locator).toContainText('第 2 页 · 第 2 行')
  await expect(targetLine).toHaveAttribute('data-line', '2')
  await expect(targetLine.locator('mark')).toHaveText(oldCitation)
  await expect(targetLine).toBeFocused()
  await expect(locator).toContainText('Old page before citation')
  await expect(locator).not.toContainText('Unrelated line two')
  await expect(locator).not.toContainText(newCitation)
  const originalLink = locator.getByRole('link', { name: '打开 PDF 第 2 页' })
  const oldHref = await originalLink.getAttribute('href')
  expect(oldHref).toBe(`/api/v1/episodes/${task.episodeId}/versions/${documents[0].versionId}/download?inline=true#page=2`)
  const oldFile = await get(reviewer, oldHref!.replace('/api/v1', '').split('#')[0])
  expect(oldFile.headers()['content-disposition']).toMatch(/^inline/)
  expect((await oldFile.body()).toString('ascii')).toContain(oldCitation)
  await locator.screenshot({ path: testInfo.outputPath('medical-old-source.png') })
  await locator.getByRole('button', { name: '关闭' }).click()

  await diff.getByRole('button', { name: /版本 2.*第 3 页.*第 3 行/ }).click()
  await expect(locator).toContainText('合成复诊原件 · 版本 2')
  await expect(locator).toContainText('第 3 页 · 第 3 行')
  await expect(targetLine).toHaveAttribute('data-line', '3')
  await expect(targetLine.locator('mark')).toHaveText(newCitation)
  await expect(targetLine).toBeFocused()
  await expect(locator).toContainText('New page before citation')
  await expect(locator).not.toContainText('Unrelated line three')
  await expect(locator).not.toContainText(oldCitation)
  await expect(locator.getByRole('link', { name: '打开 PDF 第 3 页' })).toHaveAttribute('href',
    `/api/v1/episodes/${task.episodeId}/versions/${pdfSource.versionId}/download?inline=true#page=3`)
  await locator.screenshot({ path: testInfo.outputPath('medical-current-source.png') })
  await reviewerPage.keyboard.press('Escape')

  await diff.getByRole('button', { name: /合成补充文本.*第 4 行/ }).click()
  await expect(targetLine).toHaveAttribute('data-line', '4')
  await expect(targetLine.locator('mark')).toHaveText(textCitation)
  await expect(locator.getByRole('link', { name: '下载该版本原件' })).toHaveAttribute('href',
    `/api/v1/episodes/${task.episodeId}/versions/${textSource.versionId}/download`)
  await locator.getByRole('button', { name: '关闭' }).click()

  const sourceContext = `/episodes/${task.episodeId}/segments/${pdfSource.id}/context`
  expect((await get(patient, sourceContext)).status()).toBe(404)
  expect((await get(operator, sourceContext)).status()).toBe(404)
  await reviewerPage.getByRole('button', { name: '审核通过' }).click()
  await reviewerPage.getByRole('button', { name: '派发随访' }).click()
  await expect.poll(async () => (await (await get(patient, '/inbox')).json()).some(
    (row: { kind: string; objectId: number }) => row.kind === 'FOLLOWUP_CORRECTION' && row.objectId === task.id)).toBe(true)
  await patientPage.reload()
  const message = patientPage.getByRole('region', { name: '站内消息' }).getByRole('listitem').filter({ hasText: '随访安排已更正' }).first()
  await message.getByRole('button', { name: '查看随访安排' }).click()
  await expect(patientPage.getByText('请以更正版复诊要求为准。')).toBeVisible()
  const wrongDateSlot = await addSlot(operator, doctor.id, '2097-03-22T03:00:00Z')
  const moved = await post(patient, `/appointments/${appointmentId}/reschedule`, { slotId: wrongDateSlot }, randomUUID())
  expect(moved.status()).toBe(409)
  expect(runSql(`select remaining from schedule_slot where id = ${wrongDateSlot}`)).toBe('1')
  expect(runSql(`select status || ':' || remaining from appointment join schedule_slot on schedule_slot.id = slot_id where appointment.id = ${appointmentId}`)).toBe('BOOKED:0')
  await Promise.all([patientPage, reviewerPage].map(page => page.context().close()))
})

test('P1 appointments expose visit details and staff preview a same-doctor batch stop', async ({ browser }) => {
  const [operator, patient, other] = await Promise.all(['operations', 'patient-a', 'patient-b'].map(signedIn))
  const schedule = await (await get(operator, '/schedule')).json()
  const doctor = schedule.doctors.find((person: { displayName: string }) => person.displayName === '陈医生')
  const starts = new Date('2096-01-12T02:00:00Z')
  const lead = await post(operator, '/schedule-slots', {
    doctorId: doctor.id, startsAt: starts, endsAt: new Date(starts.getTime() + 1_800_000), capacity: 2,
    visitLocation: '合成门诊楼二层 205', preparation: '请携带合成预约确认单。',
  })
  expect(lead.status()).toBe(201)
  const firstSlot = (await lead.json()).id
  const secondSlot = await addSlot(operator, doctor.id, '2096-01-12T03:00:00Z')
  const original = await book(patient, firstSlot)
  const second = await book(other, secondSlot)
  expect(original.status()).toBe(201)
  expect(second.status()).toBe(201)
  const originalId = (await original.json()).id
  const secondId = (await second.json()).id

  const page = await openPage(browser, 'patient-a', true)
  await appointment(page, originalId).getByText('合成门诊楼二层 205', { exact: false }).waitFor()
  await expect(appointment(page, originalId)).toContainText('请携带合成预约确认单。')
  await expect(appointment(page, originalId)).toContainText('签到尚未开始')
  await expect(appointment(page, originalId)).toContainText('退改约截止')
  await expect(appointment(page, originalId).getByRole('button', { name: '签到', exact: true })).toBeDisabled()
  await expect(appointment(page, originalId).getByRole('button', { name: '改约', exact: true })).toBeEnabled()
  await appointment(page, originalId).getByRole('button', { name: '取消预约', exact: true }).click()
  await expect(appointment(page, originalId).getByRole('button', { name: '确认取消预约' })).toBeVisible()
  await appointment(page, originalId).getByRole('button', { name: '保留原预约' }).click()
  await expect(appointment(page, originalId)).toContainText('已预约')
  const history = await get(patient, '/appointments/history?status=BOOKED')
  expect((await history.json()).appointments).toContainEqual(expect.objectContaining({ id: originalId,
    checkInOpensAt: '2096-01-12T01:45:00Z', checkInClosesAt: '2096-01-12T02:15:00Z', bookingChangesCloseAt: '2096-01-12T01:30:00Z' }))
  expect((await (await get(other, '/appointments/history?status=BOOKED')).json()).appointments).not.toContainEqual(expect.objectContaining({ id: originalId }))

  const staff = await openPage(browser, 'operations')
  const slot = (id: number) => staff.locator(`[data-schedule-id="${id}"]`)
  await slot(firstSlot).getByRole('button', { name: '复制排班' }).click()
  await expect(staff.getByLabel('就诊地点')).toHaveValue('合成门诊楼二层 205')
  await slot(firstSlot).getByLabel('选入批量停诊').check()
  await slot(secondSlot).getByLabel('选入批量停诊').check()
  await staff.getByRole('button', { name: '预览批量停诊（2）' }).click()
  const preview = staff.getByRole('region', { name: '停诊影响预览' })
  await expect(preview).toContainText('可能影响 2 笔预约')
  await expect(preview).toContainText('患者甲')
  await expect(preview).toContainText('患者乙')
  await preview.getByLabel('停诊原因').fill('P1 合成批量停诊')
  await preview.getByRole('button', { name: '确认停诊' }).click()
  await expect(staff.getByRole('list', { name: '停诊处理结果' }).getByRole('listitem')).toHaveCount(2)
  expect((await (await get(patient, `/appointments/${originalId}`)).json()).status).toBe('STOPPED')
  expect((await (await get(other, `/appointments/${secondId}`)).json()).status).toBe('STOPPED')
  expect((await post(patient, '/schedule-slots/stop-preview', { slotIds: [firstSlot] })).status()).toBe(403)
  await Promise.all([page, staff].map(item => item.context().close()))
})

test('P1 revisits sort and filter by local time and P2 waiting patients receive a reminder without a booking', async ({ browser }) => {
  const [operator, patient, other, owner, reviewer] = await Promise.all(
    ['operations', 'patient-a', 'patient-b', 'followup', 'doctor'].map(signedIn))
  const schedule = await (await get(operator, '/schedule')).json()
  const doctor = schedule.doctors.find((person: { displayName: string }) => person.displayName === '陈医生')
  const template = { departmentId: doctor.departmentId, kind: 'REVISIT', name: 'P1 合成科室复诊模板', content: '按确认的日期办理。' }
  expect((await post(owner, '/followups/templates', template)).status()).toBe(201)
  expect((await post(owner, '/followups/templates', template)).status()).toBe(409)
  const presets = await get(owner, '/followups/templates?departmentId=' + doctor.departmentId)
  expect(await presets.json()).toContainEqual(expect.objectContaining({ name: template.name, content: template.content }))
  expect(presets.headers()['x-request-id']).toMatch(/[0-9a-f]{8}-[0-9a-f-]{27}/)
  expect((await get(other, '/followups/templates?departmentId=' + doctor.departmentId)).status()).toBe(404)
  const task = await prepare(owner, reviewer, 'P1 P2 合成候补',
    { revisitFrom: '2096-01-16', revisitTo: '2096-01-17', revisitDepartmentId: doctor.departmentId })
  const path = `/followups/${task.id}`
  expect((await (await get(patient, path + '/revisit-slots')).json()).availability).toBe('NOT_RELEASED')
  const created = await post(patient, path + '/requests', { category: 'REVISIT_ASSISTANCE', version: 1, note: '上午可到诊' })
  expect(created.status()).toBe(200)
  const requestId = (await created.json()).id
  const nextUpdateAt = new Date(Date.now() + 172_800_000).toISOString()
  const reply = await post(owner, path + '/requests/' + requestId + '/actions', {
    action: 'REPLY', version: 1, outcome: 'NO_SLOTS', reply: '合成样本继续协调', nextUpdateAt,
  })
  expect(reply.status()).toBe(204)
  const waiting = await (await get(patient, path)).json()
  expect(waiting.requests[0]).toMatchObject({ status: 'IN_PROGRESS', outcome: 'NO_SLOTS', patientNote: '上午可到诊' })
  expect(Date.parse(waiting.requests[0].nextUpdateAt)).toBe(Date.parse(nextUpdateAt))
  expect(Date.parse(waiting.requests[0].responseDueAt)).toBeLessThan(Date.parse(waiting.dueAt))

  const patientPage = await openPage(browser, 'patient-a', true)
  await patientPage.goto('/workspace?followup=' + task.id)
  await patientPage.getByRole('button', { name: '候补并提醒我' }).click()
  await expect(patientPage.getByText('候补提醒已订阅')).toBeVisible()
  expect((await get(other, path + '/revisit-watch')).status()).toBe(404)
  const late = await addSlot(operator, doctor.id, '2096-01-16T06:00:00Z')
  const early = await addSlot(operator, doctor.id, '2096-01-16T02:00:00Z')
  const nextDay = await addSlot(operator, doctor.id, '2096-01-17T02:00:00Z')
  const available = (query = '') => get(patient, path + '/revisit-slots' + query).then(response => response.json())
  expect((await available()).slots.map((item: { id: number }) => item.id)).toEqual([early, late, nextDay])
  expect((await available('?date=2096-01-16&period=AM&doctor=' + encodeURIComponent('陈'))).slots
    .map((item: { id: number }) => item.id)).toEqual([early])
  expect((await available('?date=2096-01-17&period=PM')).availability).toBe('NO_MATCH')
  await expect.poll(async () => (await (await get(patient, '/inbox/page?group=FOLLOWUP&read=false')).json()).messages.some(
    (message: { kind: string; objectId: number }) => message.kind === 'FOLLOWUP_SLOT_AVAILABLE' && message.objectId === task.id),
  { timeout: 20_000 }).toBe(true)
  expect(await (await get(patient, path + '/appointments')).json()).toEqual([])
  expect((await (await get(patient, path + '/revisit-watch')).json()).status).toBe('NOTIFIED')
  await patientPage.reload()
  const panel = patientPage.getByRole('region', { name: '复诊号源' })
  await panel.getByText('筛选复诊号源').click()
  await panel.getByLabel('复诊日期').fill('2096-01-16')
  await panel.getByLabel('筛选号源时段').selectOption('AM')
  await panel.getByRole('button', { name: '应用筛选' }).click()
  await expect(panel.getByLabel('复诊时段').locator('option')).toHaveCount(1)
  await panel.getByRole('button', { name: '预约复诊' }).click()
  await expect.poll(async () => (await (await get(patient, path + '/appointments')).json()).length).toBe(1)
  expect((await (await get(patient, path)).json()).requests[0]).toMatchObject({ outcome: 'BOOKED', status: 'RESOLVED' })
  await patientPage.context().close()
})
