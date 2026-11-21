import { expect, test } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { get, loginInBrowser, post, runSql, signedIn, type Session } from './fixtures'

async function setup(owner: Session) {
  const options = await (await get(owner, '/care-directory')).json()
  const registered = await post(owner, '/episodes', {
    patientId: options.patients.find((item: { name: string }) => item.name === '患者甲').id,
    reference: `TEST-${randomUUID()}`, careDate: new Date().toISOString().slice(0, 10),
  })
  expect(registered.status()).toBe(201)
  const episodeId = (await registered.json()).id as number
  return { episodeId, reviewerId: options.reviewers[0].id as number,
    secondOwner: options.owners.find((item: { name: string }) => item.name === '周随访员').id as number }
}

function draft(segmentIds: number[], title: string, text: string) {
  return { kind: 'REVISIT', title, instructions: text, dueAt: new Date(Date.now() + 3 * 86_400_000).toISOString(),
    segmentIds, manualReference: segmentIds.length ? null : '合成医护手工记录' }
}

function action(session: Session, id: number, name: string, version: number, extra: object = {}) {
  return post(session, `/followups/${id}/actions`, { action: name, version, ...extra })
}

test('AC-05 and AC-09 document changes invalidate the old approval and link patient corrections', async ({ browser }) => {
  const [owner, reviewer, patient, other, operator] = await Promise.all(['followup', 'doctor', 'patient-a', 'patient-b', 'operations'].map(signedIn))
  const { episodeId, reviewerId } = await setup(owner)
  const base = `/episodes/${episodeId}`
  const imported = await owner.context.post(`/api/v1${base}/documents`, {
    headers: owner.headers, multipart: { title: '出院小结', file: {
      name: 'discharge.txt', mimeType: 'text/plain', buffer: Buffer.from('合成资料\n安排 2026-12-05 门诊复诊。'),
    } },
  })
  expect(imported.status()).toBe(201)
  const originalFile = (await imported.json()).versionId
  let sources = await (await get(owner, `${base}/segments`)).json()
  const id = (await (await post(owner, '/followups', { episodeId, reviewerId, draft: draft([sources[1].id], '门诊复诊', '请预约门诊复诊。') })).json()).id as number
  expect((await get(patient, `/followups/${id}`)).status()).toBe(404)
  expect((await get(other, `${base}/versions/${originalFile}/download`)).status()).toBe(404)
  expect((await get(operator, `${base}/documents`)).status()).toBe(404)
  expect((await get(owner, `${base}/versions/${originalFile}/download`)).status()).toBe(200)
  expect((await action(owner, id, 'SUBMIT', 1)).status()).toBe(204)
  expect((await action(reviewer, id, 'APPROVE', 1)).status()).toBe(204)

  const firstDoc = (await (await get(owner, `${base}/documents`)).json())[0]
  expect((await post(owner, `${base}/documents`, { documentId: firstDoc.id, title: '出院小结', text: '更新后请在 2026-12-06 复诊。' })).status()).toBe(201)
  expect((await action(reviewer, id, 'DISPATCH', 1)).status()).toBe(409)
  expect((await get(patient, `/followups/${id}`)).status()).toBe(404)

  sources = await (await get(owner, `${base}/segments`)).json()
  expect((await post(owner, `/followups/${id}/draft`, { version: 1, draft: draft([sources[0].id], '门诊复诊', '请预约更新后的日期。') })).status()).toBe(204)
  expect((await action(owner, id, 'SUBMIT', 2)).status()).toBe(204)
  const doctorPage = await browser.newPage()
  await loginInBrowser(doctorPage, 'doctor')
  await doctorPage.locator(`[data-followup-id="${id}"]`).getByRole('button', { name: '查看随访' }).click()
  const detail = doctorPage.locator('.followup-detail')
  await expect(detail).toContainText('更新后请在 2026-12-06 复诊。')
  await detail.getByRole('button', { name: '审核通过' }).click()
  await detail.getByRole('button', { name: '派发随访' }).click()
  await expect(detail).toContainText('已派发')
  await doctorPage.close()

  expect((await get(other, `/followups/${id}`)).status()).toBe(404)
  await expect.poll(async () => (await (await get(patient, '/inbox')).json()).some(
    (m: { kind: string; objectId: number }) => m.kind === 'FOLLOWUP_PUBLISHED' && m.objectId === id,
  )).toBeTruthy()
  const original = (await (await get(patient, '/inbox')).json()).find((item: { objectId: number; kind: string }) =>
    item.objectId === id && item.kind === 'FOLLOWUP_PUBLISHED')
  expect(original.readAt).toBeNull()
  expect((await post(patient, `/inbox/${original.id}/read`)).status()).toBe(204)

  expect((await post(owner, `${base}/documents`, { documentId: firstDoc.id, title: '出院小结', text: '最终确认 2026-12-07 复诊。' })).status()).toBe(201)
  expect((await (await get(patient, `/followups/${id}`)).json()).instructions).toBeNull()
  expect(runSql(`select state from outbox_event where episode_id = ${episodeId} and kind = 'FOLLOWUP_DUE';`)).toBe('STALE')
  expect((await (await get(patient, `/inbox/${original.id}`)).json()).correctedAt).toBeTruthy()

  sources = await (await get(owner, `${base}/segments`)).json()
  expect((await post(owner, `/followups/${id}/draft`, { version: 2, draft: draft([sources[0].id], '门诊复诊', '请按最终日期预约。') })).status()).toBe(204)
  for (const [session, name] of [[owner, 'SUBMIT'], [reviewer, 'APPROVE'], [reviewer, 'DISPATCH']] as const) {
    expect((await action(session, id, name, 3)).status()).toBe(204)
  }
  await expect.poll(async () => (await (await get(patient, `/inbox/${original.id}`)).json()).replacementId).not.toBeNull()
  expect((await (await get(patient, `/followups/${id}`)).json()).instructions).toContain('最终日期')
})

test('AC-08 an overdue task can be transferred and closed with its history', async () => {
  const [owner, reviewer, patient, replacement] = await Promise.all(['followup', 'doctor', 'patient-a', 'followup-b'].map(signedIn))
  const { episodeId, reviewerId, secondOwner } = await setup(owner)
  const id = (await (await post(owner, '/followups', { episodeId, reviewerId, draft: draft([], '检查记录核对', '合成出院事项') })).json()).id as number
  for (const [session, name] of [[owner, 'SUBMIT'], [reviewer, 'APPROVE'], [reviewer, 'DISPATCH']] as const) {
    expect((await action(session, id, name, 1)).status()).toBe(204)
  }
  runSql(`update followup_revision set due_at = now() - interval '1 minute' where task_id = ${id};`)
  await expect.poll(async () => (await (await get(owner, '/work-items')).json()).some(
    (item: { kind: string; objectId: number }) => item.kind === 'FOLLOWUP_OVERDUE' && item.objectId === id,
  ), { timeout: 12_000 }).toBeTruthy()
  expect((await action(owner, id, 'CONTACT', 1, { note: '一次人工联系，无回应' })).status()).toBe(204)
  expect((await action(owner, id, 'TRANSFER', 1, { newOwnerId: secondOwner, note: '负责人离岗' })).status()).toBe(204)
  expect((await get(owner, `/followups/${id}`)).status()).toBe(404)
  expect((await get(owner, `/episodes/${episodeId}/documents`)).status()).toBe(404)
  expect((await (await get(replacement, '/work-items')).json())).toEqual(expect.arrayContaining([
    expect.objectContaining({ kind: 'FOLLOWUP_OVERDUE', objectId: id, status: 'OPEN' }),
  ]))
  expect((await (await get(replacement, `/followups/${id}`)).json()).feedback).toEqual(expect.arrayContaining([
    expect.objectContaining({ detail: '一次人工联系，无回应' }),
  ]))
  expect((await action(patient, id, 'FEEDBACK', 1, { note: '患者自述已复查' })).status()).toBe(204)
  expect((await (await get(patient, `/followups/${id}`)).json()).status).toBe('PATIENT_REPLIED')
  expect((await action(replacement, id, 'CLOSE', 1, { closureReason: 'UNREACHABLE', note: '多次联系未能核实' })).status()).toBe(204)
  expect((await (await get(replacement, `/followups/${id}`)).json()).status).toBe('CLOSED')
})

test('AC-06 failed station delivery reaches the owner and manual retry delivers once', async () => {
  const [owner, reviewer, patient, ops] = await Promise.all(['followup', 'doctor', 'patient-a', 'operations'].map(signedIn))
  const { episodeId, reviewerId } = await setup(owner)
  const id = (await (await post(owner, '/followups', { episodeId, reviewerId, draft: draft([], '恢复通知', '合成随访安排') })).json()).id as number
  const trigger = `synthetic_notice_failure_${episodeId}`
  runSql(`
    create function ${trigger}() returns trigger language plpgsql as $$
    begin
      if exists (select 1 from outbox_event where id = new.event_id and episode_id = ${episodeId}) then
        raise exception 'Controlled notification failure';
      end if;
      return new;
    end; $$;
    create trigger ${trigger} before insert on inbox_message for each row execute function ${trigger}();
  `)
  try {
    for (const [session, name] of [[owner, 'SUBMIT'], [reviewer, 'APPROVE'], [reviewer, 'DISPATCH']] as const) {
      expect((await action(session, id, name, 1)).status()).toBe(204)
    }
    for (let count = 1; count <= 4; count++) {
      const row = `from outbox_event where episode_id = ${episodeId} and kind = 'FOLLOWUP_PUBLISHED'`
      await expect.poll(() => runSql(`select attempts ${row};`), { timeout: 12_000 }).toBe(String(count))
      if (count < 4) runSql(`update outbox_event set available_at = now() where episode_id = ${episodeId} and kind = 'FOLLOWUP_PUBLISHED';`)
    }
  } finally {
    runSql(`drop trigger ${trigger} on inbox_message; drop function ${trigger}();`)
  }
  const eventId = Number(runSql(`select id from outbox_event where episode_id = ${episodeId} and kind = 'FOLLOWUP_PUBLISHED';`))
  const work = (await (await get(owner, '/work-items')).json()).find((item: { kind: string; objectId: number }) =>
    item.kind === 'NOTIFICATION_FAILED' && item.objectId === eventId)
  expect(work.status).toBe('OPEN')
  expect((await post(ops, `/work-items/${work.id}/retry`)).status()).toBe(404)
  expect((await post(owner, `/work-items/${work.id}/retry`)).status()).toBe(204)
  await expect.poll(async () => (await (await get(patient, '/inbox')).json()).filter(
    (item: { kind: string; objectId: number }) => item.kind === 'FOLLOWUP_PUBLISHED' && item.objectId === id,
  ).length, { timeout: 12_000 }).toBe(1)
  expect((await post(owner, `/work-items/${work.id}/retry`)).status()).toBe(404)
})
