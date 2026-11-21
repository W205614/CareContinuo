import { shanghaiDate } from '../src/time'
import { expect, test } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { createSlot, get, loginInBrowser, post, runSql, seedBookedAppointment, signedIn } from './fixtures'

test('AC-01 and AC-07 patient can rebook and staff can verify an actual consultation', async ({ browser }) => {
  const [owner, reviewer, patient, other, ops] = await Promise.all(['followup', 'doctor', 'patient-a', 'patient-b', 'operations'].map(signedIn))
  const directory = (await (await get(owner, '/care-directory')).json())
  const { id: episodeId } = await (await post(owner, '/episodes', { patientId: directory.patients[0].id,
    reference: `REVISIT-${randomUUID()}`, careDate: new Date().toISOString().slice(0, 10) })).json()
  expect((await post(owner, `/episodes/${episodeId}/documents`, {
    title: '合成出院小结', text: '出院后请按安排于 2099-12-05 门诊复诊。',
  })).status()).toBe(201)
  const jobId = (await (await post(owner, `/episodes/${episodeId}/agent-jobs`)).json()).id
  await expect.poll(async () => (await (await get(owner, `/agent-jobs/${jobId}`)).json()).status).toBe('READY')

  const firstSlot = await createSlot(ops, 150, 2)
  const secondSlot = await createSlot(ops, 155, 2)
  const moveSlot = await createSlot(ops, 160, 2)
  const finalSlot = await createSlot(ops, 165, 2)
  const schedule = await (await get(ops, '/schedule')).json()
  const firstSlotDetails = schedule.slots.find((slot: { id: number }) => slot.id === firstSlot)
  const finalSlotDetails = schedule.slots.find((slot: { id: number }) => slot.id === finalSlot)

  const staffPage = await browser.newPage()
  await loginInBrowser(staffPage, 'followup')
  await staffPage.getByLabel('诊疗记录').selectOption(String(episodeId))
  await staffPage.getByRole('button', { name: '填入草稿' }).click()
  await expect(staffPage.getByLabel('复诊开始日期')).toHaveValue('2099-12-05')
  await expect(staffPage.getByLabel('完成期限（北京时间）')).toHaveValue('')
  await staffPage.getByLabel('复诊科室').selectOption(String(firstSlotDetails.departmentId))
  await staffPage.getByLabel('复诊开始日期').fill(shanghaiDate(firstSlotDetails.startsAt))
  await staffPage.getByLabel('复诊结束日期').fill(shanghaiDate(finalSlotDetails.startsAt))
  await staffPage.getByLabel('完成期限（北京时间）').fill('2099-12-05T17:00')
  await staffPage.getByRole('button', { name: '保存随访草稿' }).click()
  await expect(staffPage.locator('.followup-detail')).toContainText('合成门诊复诊')
  await staffPage.locator('.followup-detail').getByRole('button', { name: '提交审核' }).click()
  await expect(staffPage.locator('.followup-detail')).toContainText('待审核')
  const list = await (await get(owner, '/followups')).json()
  const taskId = list.find((row: { episodeId: number }) => row.episodeId === episodeId).id
  for (const action of ['APPROVE', 'DISPATCH']) {
    expect((await post(reviewer, `/followups/${taskId}/actions`, { action, version: 1 })).status()).toBe(204)
  }

  expect((await post(other, `/followups/${taskId}/appointments`, { slotId: firstSlot }, randomUUID())).status()).toBe(404)
  const patientPage = await browser.newPage()
  await loginInBrowser(patientPage, 'patient-a')
  await patientPage.locator(`[data-followup-id="${taskId}"]`).getByRole('button', { name: '查看随访' }).click()
  const detail = patientPage.locator('.followup-detail')
  await detail.getByLabel('复诊时段').selectOption(String(firstSlot))
  await detail.getByRole('button', { name: '预约复诊' }).click()
  await expect(detail.locator('[data-revisit-appointment]')).toContainText('已预约')
  const visits = async () => (await get(patient, `/followups/${taskId}/appointments`)).json()
  const first = (await visits())[0].appointmentId as number
  await patientPage.locator(`[data-appointment-id="${first}"]`).getByRole('button', { name: '取消预约' }).click()
  await expect(detail.locator(`[data-revisit-appointment="${first}"]`)).toContainText('已取消')
  expect((await (await get(patient, `/followups/${taskId}`)).json()).status).toBe('DISPATCHED')

  const key = randomUUID()
  const bookPath = `/followups/${taskId}/appointments`
  const booked = await post(patient, bookPath, { slotId: secondSlot }, key)
  expect(booked.status()).toBe(201)
  const second = (await booked.json()).id as number
  expect((await (await post(patient, bookPath, { slotId: secondSlot }, key)).json()).id).toBe(second)
  expect((await post(patient, bookPath, { slotId: moveSlot }, randomUUID())).status()).toBe(409)
  const moved = (await (await post(patient, `/appointments/${second}/reschedule`, { slotId: moveSlot }, randomUUID())).json()).id as number
  expect((await post(ops, `/schedule-slots/${moveSlot}/stop`, { reason: '合成复诊停诊' })).status()).toBe(204)
  const final = (await (await post(patient, `/appointments/${moved}/reschedule`, { slotId: finalSlot }, randomUUID())).json()).id as number
  const history = await visits()
  expect(history.map((row: { status: string }) => row.status)).toEqual(['BOOKED', 'RESCHEDULED', 'RESCHEDULED', 'CANCELLED'])
  expect(history[0].supersedesId).toBe(moved)

  expect((await post(patient, `/followups/${taskId}/actions`, { action: 'FEEDBACK', version: 1, note: '患者自述已复查' })).status()).toBe(204)
  expect((await (await get(patient, `/followups/${taskId}`)).json()).status).toBe('PATIENT_REPLIED')
  runSql(`update schedule_slot set starts_at = now() + interval '2 minutes', ends_at = now() + interval '1 hour' where id = ${finalSlot};`)
  expect((await post(patient, `/appointments/${final}/check-in`)).status()).toBe(204)
  expect((await post(reviewer, `/appointments/${final}/start`)).status()).toBe(204)
  expect((await post(reviewer, `/appointments/${final}/complete`, { note: '合成复诊接诊完成' })).status()).toBe(204)
  const completed = (await visits())[0]
  expect(completed.completedAt).toBeTruthy()
  await staffPage.locator(`[data-followup-id="${taskId}"]`).getByRole('button', { name: '查看随访' }).click()
  const staffDetail = staffPage.locator('.followup-detail')
  await expect(staffDetail).toContainText('接诊完成')
  await staffDetail.getByRole('button', { name: '核实结果' }).click()
  await staffDetail.getByLabel('核实依据').selectOption(String(completed.encounterId))
  await staffDetail.getByLabel('处理依据').fill('核对关联接诊记录，复诊已完成。')
  await staffDetail.getByRole('button', { name: '确认提交' }).click()
  await expect(staffDetail).toContainText('医护已核实')
  const confirmed = await (await get(patient, `/followups/${taskId}`)).json()
  expect(confirmed.feedback.map((row: { kind: string }) => row.kind)).toEqual(['PATIENT', 'VERIFICATION'])
  expect(confirmed.feedback[1].sourceEncounterId).toBe(completed.encounterId)
  await staffPage.close()
  await patientPage.close()
})

test('completed outpatient visit hands off its own return plan', async () => {
  const [doctor, owner, patient] = await Promise.all(['doctor', 'followup', 'patient-a'].map(signedIn))
  const id = seedBookedAppointment(4)
  expect((await post(patient, `/appointments/${id}/check-in`)).status()).toBe(204)
  expect((await post(doctor, `/appointments/${id}/start`)).status()).toBe(204)
  expect((await post(doctor, `/appointments/${id}/complete`, { note: '合成接诊', returnPlan: '门诊安排请预约复诊。' })).status()).toBe(204)
  const staffId = (await (await get(owner, '/session')).json()).id
  const handoff = await post(doctor, `/appointments/${id}/return-plan`, { ownerId: staffId })
  expect(handoff.status()).toBe(201)
  const episodeId = (await handoff.json()).id as number
  expect((await (await get(owner, '/episodes')).json())).toEqual(expect.arrayContaining([
    expect.objectContaining({ id: episodeId, kind: 'OUTPATIENT' }),
  ]))
  expect((await (await get(owner, `/episodes/${episodeId}/segments`)).json())[0].excerpt).toBe('门诊安排请预约复诊。')
  expect((await post(patient, `/appointments/${id}/return-plan`, { ownerId: staffId })).status()).toBe(404)
})

test('revisit windows apply to booking and rescheduling, including after source correction', async ({ page }) => {
  const [owner, doctor, patient, ops] = await Promise.all(['followup', 'doctor', 'patient-a', 'operations'].map(signedIn))
  const directory = await (await get(owner, '/care-directory')).json()
  const schedule = await (await get(ops, '/schedule')).json()
  const mainDoctor = schedule.doctors.find((item: { displayName: string }) => item.displayName === '陈医生')
  const otherDepartment = (await (await post(ops, '/departments', { name: '复诊测试科室' })).json()).id as number
  const syntheticDoctor = 'revisit-' + randomUUID()
  runSql("with new_user as (insert into app_user(username, password_hash, display_name) "
    + "select '" + syntheticDoctor + "', password_hash, '测试医生' from app_user where username = 'doctor' returning id) "
    + "insert into user_role(user_id, role) select id, 'DOCTOR' from new_user")
  const registered = await post(ops, '/doctors', { username: syntheticDoctor, departmentId: otherDepartment, displayName: '测试医生' })
  expect(registered.status()).toBe(200)
  const otherDoctor = (await registered.json()).id as number
  const offset = Date.now() % 59_000
  async function slot(doctorId: number, start: string) {
    const startsAt = new Date(Date.parse(start) + offset)
    const response = await post(ops, '/schedule-slots', {
      doctorId, startsAt, endsAt: new Date(startsAt.getTime() + 3_600_000), capacity: 2,
    })
    expect(response.status()).toBe(201)
    return (await response.json()).id as number
  }
  const early = await slot(mainDoctor.id, '2098-04-05T15:59:00Z') // Same UTC date, previous Beijing date.
  const within = await slot(mainDoctor.id, '2098-04-05T16:00:00Z')
  const wrongDepartment = await slot(otherDoctor, '2098-04-05T16:00:00Z')
  const after = await slot(mainDoctor.id, '2098-04-06T16:00:00Z')
  const remaining = (id: number) => Number(runSql('select remaining from schedule_slot where id = ' + id))
  const registeredEpisode = await post(owner, '/episodes', {
    patientId: directory.patients[0].id, reference: 'WINDOW-' + randomUUID(), careDate: '2026-11-21',
  })
  expect(registeredEpisode.status()).toBe(201)
  const episodeId = (await registeredEpisode.json()).id
  const task = { episodeId, reviewerId: directory.reviewers[0].id }
  const draft = { kind: 'REVISIT', title: '限科室和日期的复诊', instructions: '请在指定日期复诊。',
    dueAt: '2098-04-09T17:00:00+08:00', segmentIds: [], manualReference: '合成复诊安排',
    revisitFrom: '2098-04-06', revisitTo: '2098-04-06', revisitDepartmentId: mainDoctor.departmentId }
  expect((await post(owner, '/followups', { ...task, draft: { ...draft, revisitDepartmentId: null } })).status()).toBe(400)
  expect((await post(owner, '/followups', { ...task, draft: { ...draft, revisitTo: '2098-04-05' } })).status()).toBe(400)
  const response = await post(owner, '/followups', { ...task, draft })
  expect(response.status()).toBe(201)
  const id = (await response.json()).id
  async function publish(version: number) {
    for (const [session, action] of [[owner, 'SUBMIT'], [doctor, 'APPROVE'], [doctor, 'DISPATCH']] as const) {
      expect((await post(session, '/followups/' + id + '/actions', { action, version })).status()).toBe(204)
    }
  }
  await publish(1)
  const visitsPath = '/followups/' + id + '/appointments'
  for (const wrong of [early, wrongDepartment, after]) {
    expect((await post(patient, visitsPath, { slotId: wrong }, randomUUID())).status()).toBe(409)
    expect(remaining(wrong)).toBe(2)
  }
  const booked = await post(patient, visitsPath, { slotId: within }, randomUUID())
  expect(booked.status()).toBe(201)
  const appointment = (await booked.json()).id
  const move = (target: number) => post(patient, '/appointments/' + appointment + '/reschedule', { slotId: target }, randomUUID())
  expect((await move(wrongDepartment)).status()).toBe(409)
  expect(remaining(within)).toBe(1)
  expect(remaining(wrongDepartment)).toBe(2)

  expect((await post(owner, '/episodes/' + episodeId + '/documents', { title: '复诊更正', text: '复诊改为 2098-04-07。' })).status()).toBe(201)
  expect((await move(after)).status()).toBe(409)
  expect((await post(owner, '/followups/' + id + '/draft', { version: 1, draft: {
    ...draft, revisitFrom: '2098-04-07', revisitTo: '2098-04-07',
  } })).status()).toBe(204)
  await publish(2)
  await loginInBrowser(page, 'patient-a')
  await page.locator('[data-followup-id="' + id + '"]').getByRole('button', { name: '查看随访' }).click()
  await expect(page.getByText('已有预约与更正后的复诊安排不符。')).toBeVisible()
  await expect(page.locator('[data-appointment-id="' + appointment + '"]').getByRole('button', { name: '取消预约' })).toBeVisible()
  expect((await move(early)).status()).toBe(409)
  expect(remaining(early)).toBe(2)
  expect(remaining(within)).toBe(1)
  const moved = await move(after)
  expect(moved.status()).toBe(200)
  const replacement = (await moved.json()).id
  expect(remaining(within)).toBe(2)
  expect(remaining(after)).toBe(1)
  expect(await (await get(patient, visitsPath)).json()).toEqual([
    expect.objectContaining({ appointmentId: replacement, status: 'BOOKED', needsReschedule: false }),
    expect.objectContaining({ appointmentId: appointment, status: 'RESCHEDULED' }),
  ])
})
