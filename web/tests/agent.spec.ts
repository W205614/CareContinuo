import { expect, test } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { get, loginInBrowser, post, runSql, signedIn, type Session } from './fixtures'

async function episode(owner: Session, text: string) {
  const directory = await (await get(owner, '/care-directory')).json()
  const response = await post(owner, '/episodes', { patientId: directory.patients[0].id,
    reference: `AI-${randomUUID()}`, careDate: new Date().toISOString().slice(0, 10) })
  expect(response.status()).toBe(201)
  const { id } = await response.json()
  expect((await post(owner, `/episodes/${id}/documents`, { title: '合成出院小结', text })).status()).toBe(201)
  return { id: id as number, reviewer: directory.reviewers[0].id as number }
}

async function job(owner: Session, id: number) {
  const requested = await post(owner, `/episodes/${id}/agent-jobs`)
  expect(requested.status()).toBe(202)
  const snapshot = await requested.json()
  return snapshot.id as number
}

async function state(owner: Session, id: number) {
  return (await get(owner, `/agent-jobs/${id}`)).json()
}

test('AC-05 and AC-09 Agent conflict cites both authorized documents and prevents publication', async ({ page }) => {
  const [owner, reviewer, otherPatient, otherStaff] = await Promise.all(['followup', 'doctor', 'patient-b', 'followup-b'].map(signedIn))
  const e = await episode(owner, '[conflict] 安排 2099-12-05 门诊复诊')
  expect((await post(owner, `/episodes/${e.id}/documents`, { title: '补充记录', text: '安排 2099-12-06 门诊复诊' })).status()).toBe(201)
  const jobId = await job(owner, e.id)
  const replay = await job(owner, e.id)
  expect(replay).toBe(jobId)
  for (const denied of [otherPatient, otherStaff]) {
    expect((await get(denied, `/agent-jobs/${jobId}`)).status()).toBe(404)
    expect((await post(denied, `/episodes/${e.id}/agent-jobs`)).status()).toBe(404)
  }
  await expect.poll(async () => (await state(owner, jobId)).status).toBe('CONFLICT')
  expect(await job(owner, e.id)).toBe(jobId)
  const result = await state(owner, jobId)
  expect(result.outcome.conflicts[0].references.map((item: { quote: string }) => item.quote)).toEqual([
    '[conflict] 安排 2099-12-05 门诊复诊', '安排 2099-12-06 门诊复诊',
  ])
  const task = (await (await post(owner, '/followups', { episodeId: e.id, reviewerId: e.reviewer, draft: {
    kind: 'REVISIT', title: '候选复诊', instructions: '合成测试', dueAt: '2099-12-05T17:00:00+08:00',
    segmentIds: [], manualReference: '医护合成记录',
  } })).json()).id as number
  expect((await post(owner, `/followups/${task}/actions`, { version: 1, action: 'SUBMIT' })).status()).toBe(204)
  expect((await post(reviewer, `/followups/${task}/actions`, { version: 1, action: 'APPROVE' })).status()).toBe(204)
  expect((await post(reviewer, `/followups/${task}/actions`, { version: 1, action: 'DISPATCH' })).status()).toBe(409)
  expect(runSql(`select count(*) from outbox_event where episode_id = ${e.id};`)).toBe('0')

  await loginInBrowser(page, 'followup')
  await page.getByLabel('诊疗记录').selectOption(String(e.id))
  await expect(page.getByText('同一随访的复诊日期不一致')).toBeVisible()
})

test('AC-06 a late result cannot replace new source and Agent failures preserve manual drafts', async () => {
  const owner = await signedIn('followup')
  const delayed = await episode(owner, '[delayed] 安排 2099-12-05 门诊复诊')
  const pending = await job(owner, delayed.id)
  await expect.poll(async () => (await state(owner, pending)).status).toBe('RUNNING')
  const doc = (await (await get(owner, `/episodes/${delayed.id}/documents`)).json())[0]
  expect((await post(owner, `/episodes/${delayed.id}/documents`, {
    documentId: doc.id, title: '合成出院小结', text: '更正后安排 2099-12-05 门诊复诊',
  })).status()).toBe(201)
  await expect.poll(() => runSql(`select status::text || ':' || (outcome is null)::text from agent_job where id = ${pending};`),
    { timeout: 12_000 }).toBe('STALE:true')
  const fresh = await job(owner, delayed.id)
  await expect.poll(async () => (await state(owner, fresh)).status).toBe('READY')
  expect((await state(owner, fresh)).outcome.candidates[0].references[0].quote).toContain('更正后')

  for (const [marker, code] of [['[timeout]', 'MODEL_TIMEOUT'], ['[unsupported]', 'MODEL_SOURCE_MISMATCH']]) {
    const failed = await episode(owner, `${marker} 安排 2099-12-05 门诊复诊`)
    const requested = await job(owner, failed.id)
    await expect.poll(async () => (await state(owner, requested)).failureCode).toBe(code)
    expect((await post(owner, '/followups', { episodeId: failed.id, reviewerId: failed.reviewer,
      draft: { kind: 'REVISIT', title: '手工随访', instructions: '合成补录', dueAt: '2099-12-05T17:00:00+08:00',
        segmentIds: [], manualReference: '医护核对后补录' },
    })).status()).toBe(201)
  }
})
