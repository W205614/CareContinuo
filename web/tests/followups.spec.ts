import { expect, test } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { book, createSlot, get, loginInBrowser, post, runSql, signedIn, type Session } from './fixtures'

async function setup(owner: Session) {
  const options = await (await get(owner, '/care-directory')).json()
  const registered = await post(owner, '/episodes', {
    patientReference: 'DEMO-A', patientName: '患者甲',
    reference: `TEST-${randomUUID()}`, careDate: new Date().toISOString().slice(0, 10),
  })
  expect(registered.status()).toBe(201)
  const episodeId = (await registered.json()).id as number
  return { episodeId, reviewerId: options.reviewers[0].id as number,
    secondOwner: options.owners.find((item: { name: string }) => item.name === '周随访员').id as number }
}

function draft(segmentIds: number[], title: string, text: string) {
  return { kind: 'PENDING_RESULT', title, instructions: text, dueAt: new Date(Date.now() + 3 * 86_400_000).toISOString(),
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

test('discharge registration requires a matching patient number and name', async ({ page }) => {
  await loginInBrowser(page, 'followup')
  await page.getByText('登记合成出院记录', { exact: true }).click()
  await expect(page.getByLabel('患者编号')).toHaveValue('')
  await page.getByLabel('患者编号').fill('DEMO-A')
  await page.getByLabel('患者姓名').fill('患者乙')
  await page.getByLabel('出院记录编号').fill('IDENTITY-' + randomUUID())
  await page.getByLabel('出院日期').fill('2026-01-01')
  await page.getByRole('button', { name: '登记出院记录' }).click()
  await expect(page.getByRole('alert')).toContainText('患者编号和姓名不匹配')
  await page.getByLabel('患者姓名').fill('患者甲')
  await page.getByRole('button', { name: '登记出院记录' }).click()
  await expect(page.getByRole('status')).toContainText('出院记录已登记')
})

test('late source correction reopens verification and closure ends patient reminders', async () => {
  const [owner, reviewer, patient] = await Promise.all(['followup', 'doctor', 'patient-a'].map(signedIn))
  const { episodeId, reviewerId } = await setup(owner)
  const created = await post(owner, '/followups', { episodeId, reviewerId,
    draft: draft([], '待核实的合成检查', '此前发布的合成随访说明') })
  expect(created.status()).toBe(201)
  const { id } = await created.json()
  for (const [session, name] of [[owner, 'SUBMIT'], [reviewer, 'APPROVE'], [reviewer, 'DISPATCH']] as const) {
    expect((await action(session, id, name, 1)).status()).toBe(204)
  }
  expect((await action(owner, id, 'VERIFY', 1, { note: '内部依据仅医护可见' })).status()).toBe(204)
  const path = `/followups/${id}`
  const before = await (await get(patient, path)).json()
  expect(before.status).toBe('VERIFIED')
  expect(JSON.stringify(before)).not.toContain('内部依据仅医护可见')
  const readInbox = async () => (await (await get(patient, '/inbox')).json()) as {
    id: number; kind: string; objectId: number; correctedAt: string | null
  }[]
  const verificationMessage = async () => (await readInbox())
    .find(message => message.objectId === id && message.kind === 'FOLLOWUP_VERIFIED')
  await expect.poll(async () => !!(await verificationMessage())).toBe(true)
  expect((await post(owner, `/episodes/${episodeId}/documents`, { title: '新资料', text: '检查结果来源已经更正。' })).status()).toBe(201)
  expect((await verificationMessage())?.correctedAt).toBeTruthy()
  expect(await (await get(patient, path)).json()).toMatchObject({ status: 'UNDER_CORRECTION', instructions: null })
  expect((await action(owner, id, 'CLOSE', 1, { closureReason: 'WRONG_TASK', note: '原任务已撤回' })).status()).toBe(204)
  expect(await (await get(patient, path)).json()).toMatchObject({ status: 'CLOSED', needsReview: false, instructions: null })
  expect(await (await get(patient, '/followups?actionable=true')).json()).not.toEqual(expect.arrayContaining([
    expect.objectContaining({ id }),
  ]))
  expect((await (await get(patient, '/followups/history?closed=true')).json()).tasks).toEqual(expect.arrayContaining([
    expect.objectContaining({ id, status: 'CLOSED' }),
  ]))
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
  test.setTimeout(60_000)
  const [owner, reviewer, patient, ops, replacement] = await Promise.all(
    ['followup', 'doctor', 'patient-a', 'operations', 'followup-b'].map(signedIn))
  const { episodeId, reviewerId, secondOwner } = await setup(owner)
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
  const retryPath = '/work-items/' + work.id + '/retry'
  expect((await post(ops, retryPath)).status()).toBe(404)
  const ownerId = (await (await get(owner, '/session')).json()).id
  runSql("delete from user_role where user_id = " + ownerId + " and role = 'FOLLOWUP'")
  try {
    const items = await get(owner, '/work-items')
    expect(items.status()).toBe(200)
    expect(await items.json()).not.toEqual(expect.arrayContaining([expect.objectContaining({ id: work.id })]))
    expect((await post(owner, retryPath)).status()).toBe(404)
  } finally {
    runSql("insert into user_role(user_id, role) values (" + ownerId + ", 'FOLLOWUP')")
  }
  runSql('update app_user set enabled = false where id = ' + ownerId)
  try {
    expect((await get(owner, '/work-items')).status()).toBe(401)
    expect((await post(owner, retryPath)).status()).toBe(401)
  } finally {
    runSql('update app_user set enabled = true where id = ' + ownerId)
  }
  expect((await get(owner, '/work-items')).status()).toBe(401)
  const resumed = await signedIn('followup')
  expect((await action(resumed, id, 'TRANSFER', 1, { newOwnerId: secondOwner, note: '更换随访负责人' })).status()).toBe(204)
  expect((await post(resumed, retryPath)).status()).toBe(404)
  expect((await post(replacement, retryPath)).status()).toBe(204)
  await expect.poll(async () => (await (await get(patient, '/inbox')).json()).filter(
    (item: { kind: string; objectId: number }) => item.kind === 'FOLLOWUP_PUBLISHED' && item.objectId === id,
  ).length, { timeout: 12_000 }).toBe(1)
  expect((await post(replacement, retryPath)).status()).toBe(404)
})

test('source correction closes old work and the newly published version can become overdue again', async () => {
  const [owner, reviewer] = await Promise.all(['followup', 'doctor'].map(signedIn))
  const { episodeId, reviewerId } = await setup(owner)
  async function create(title: string) {
    const response = await post(owner, '/followups', { episodeId, reviewerId, draft: draft([], title, '合成') })
    expect(response.status()).toBe(201)
    return (await response.json()).id as number
  }
  const published = await create('原随访')
  const inReview = await create('等待审核')
  const publish = async (version: number) => {
    for (const [session, name] of [[owner, 'SUBMIT'], [reviewer, 'APPROVE'], [reviewer, 'DISPATCH']] as const) {
      expect((await action(session, published, name, version)).status()).toBe(204)
    }
  }
  const overdue = async () => (await (await get(owner, '/work-items')).json()).find(
    (item: { kind: string; objectId: number }) => item.kind === 'FOLLOWUP_OVERDUE' && item.objectId === published,
  )
  const expire = (version: number) => runSql(
    "update followup_revision set due_at = now() - interval '1 minute' where task_id = " + published + ' and version_number = ' + version,
  )

  await publish(1)
  expect((await action(owner, inReview, 'SUBMIT', 1)).status()).toBe(204)
  expire(1)
  await expect.poll(async () => (await overdue())?.status, { timeout: 12_000 }).toBe('OPEN')
  const old = await overdue()
  expect((await post(owner, '/episodes/' + episodeId + '/documents', { title: '补充资料', text: '更新后的合成资料' })).status()).toBe(201)
  expect((await overdue()).status).toBe('DONE')
  expect((await (await get(reviewer, '/work-items')).json())).toEqual(expect.arrayContaining([
    expect.objectContaining({ kind: 'FOLLOWUP_REVIEW', objectId: inReview, status: 'DONE' }),
  ]))
  expect((await post(owner, '/followups/' + published + '/draft', {
    version: 1, draft: draft([], '新版随访', '更正的安排'),
  })).status()).toBe(204)
  await publish(2)
  expect((await overdue()).status).toBe('DONE')
  expire(2)
  await expect.poll(async () => (await overdue())?.status, { timeout: 12_000 }).toBe('OPEN')
  expect((await overdue()).id).toBe(old.id)
  expect(new Date((await overdue()).dueAt).getTime()).toBeGreaterThan(new Date(old.dueAt).getTime())
})

test('operations can retry a shared scheduling notification', async ({ page }) => {
  test.setTimeout(60_000)
  const [operator, patient] = await Promise.all(['operations', 'patient-a'].map(signedIn))
  const slot = await createSlot(operator, 180)
  const booked = await book(patient, slot)
  expect(booked.status()).toBe(201)
  const appointment = (await booked.json()).id
  const event = runSql("insert into outbox_event(dedupe_key, recipient_user_id, kind, object_id, title, available_at, state) "
    + "select 'synthetic:operations:' || id, recipient_user_id, kind, object_id, title, now(), 'FAILED' from outbox_event "
    + "where kind = 'APPOINTMENT_BOOKED' and object_id = " + appointment + ' returning id')
  const id = Number(runSql("insert into work_item(kind, object_id, role, due_at) values ('NOTIFICATION_FAILED', " + event
    + ", 'OPERATIONS', now()) returning id"))
  expect(id).toBeGreaterThan(0)
  expect((await (await get(operator, '/work-items')).json())).toEqual(expect.arrayContaining([
    expect.objectContaining({ id, status: 'OPEN' }),
  ]))
  expect((await get(operator, '/appointments/' + appointment)).status()).toBe(404)
  await loginInBrowser(page, 'operations')
  const pending = page.getByRole('region', { name: '工作待办' }).getByRole('listitem')
    .filter({ hasText: '通知发送失败' })
  await expect(pending.getByRole('button', { name: '补发消息' })).toBeVisible()
  await expect(pending.getByRole('link', { name: '打开处理' })).toHaveCount(0)
  const itemPath = '/work-items/' + id
  expect((await get(patient, itemPath)).status()).toBe(404)
  expect((await (await get(operator, itemPath)).json()).deliveryStatus).toBe('FAILED')
  const trigger = 'synthetic_manual_retry_' + event
  runSql(`
    create function ${trigger}() returns trigger language plpgsql as $$
    begin
      if new.event_id = ${event} then raise exception 'Controlled manual retry failure'; end if;
      return new;
    end; $$;
    create trigger ${trigger} before insert on inbox_message for each row execute function ${trigger}();
  `)
  try {
    await pending.getByRole('button', { name: '补发消息' }).click()
    await expect(pending.getByRole('status')).toHaveText('已提交补发，等待投递')
    await expect(pending.getByRole('button', { name: '补发消息' })).toHaveCount(0)
    await expect.poll(() => Number(runSql(`select attempts from outbox_event where id = ${event}`))).toBeGreaterThan(0)
    runSql(`update outbox_event set attempts = 3, available_at = now() where id = ${event};`)
    await expect(pending.getByRole('status')).toHaveText('补发失败', { timeout: 12_000 })
    expect((await (await get(operator, itemPath)).json()).deliveryStatus).toBe('RETRY_FAILED')
  } finally {
    runSql(`drop trigger ${trigger} on inbox_message; drop function ${trigger}();`)
  }
  await pending.getByRole('button', { name: '补发消息' }).click()
  await expect(page.getByRole('region', { name: '工作待办' }).getByRole('status').filter({ hasText: '消息已生成' })).toBeVisible()
  await expect(pending).toHaveCount(0)
  expect((await (await get(operator, itemPath)).json()).deliveryStatus).toBe('SENT')
  expect(runSql(`select count(*) from inbox_message where event_id = ${event}`)).toBe('1')
})
