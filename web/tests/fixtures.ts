import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { expect, request as playwright, type APIRequestContext, type Page } from '@playwright/test'

const webUrl = process.env.CARE_WEB_URL!

export async function signedIn(username: string) {
  const context = await playwright.newContext({ baseURL: webUrl })
  const first = await csrf(context)
  const response = await context.post('/api/v1/session/login', {
    form: { username, password: 'demo-care-2026' }, headers: first,
  })
  expect(response.status()).toBe(204)
  return { context, headers: await csrf(context) }
}

async function csrf(context: APIRequestContext): Promise<Record<string, string>> {
  const result = await (await context.get('/api/v1/session/csrf')).json()
  return { [result.header]: result.token }
}

export type Session = Awaited<ReturnType<typeof signedIn>>

export async function post(session: Session, path: string, data?: unknown, key?: string) {
  return session.context.post(`/api/v1${path}`, {
    data, headers: { ...session.headers, ...(key ? { 'Idempotency-Key': key } : {}) },
  })
}

export async function get(session: Session, path: string) {
  return session.context.get(`/api/v1${path}`)
}

export async function createSlot(operator: Session, minutes: number, capacity = 1) {
  const overview = await (await get(operator, '/schedule')).json()
  const doctorId = overview.doctors.find((item: { displayName: string }) => item.displayName === '陈医生').id
  const startsAt = new Date(Date.now() + minutes * 60_000 + Math.floor(Math.random() * 3_000))
  const endsAt = new Date(startsAt.getTime() + 60 * 60_000)
  const response = await post(operator, '/schedule-slots', { doctorId, startsAt, endsAt, capacity })
  expect(response.status()).toBe(201)
  return (await response.json()).id as number
}

export function book(session: Session, slotId: number, key: string = randomUUID()) {
  return post(session, '/appointments', { slotId }, key)
}

export async function loginInBrowser(page: Page, username: string) {
  await page.goto('/')
  await page.getByLabel('账号').fill(username)
  await page.getByLabel('密码').fill('demo-care-2026')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/workspace$/)
}

export function runSql(sql: string) {
  const name = process.env.CARE_E2E_DB_CONTAINER!
  return execFileSync('docker', ['exec', '-i', name, 'psql', '-XqAt', '-v', 'ON_ERROR_STOP=1', '-U', 'carecontinuo', '-d', 'carecontinuo'], {
    input: sql, encoding: 'utf8',
  }).trim()
}

export function seedBookedAppointment(minutesFromStart: number) {
  return Number(runSql(`
    with slot as (
      insert into schedule_slot(doctor_id, starts_at, ends_at, capacity, remaining)
      select d.id, now() + interval '${minutesFromStart} minutes',
             now() + interval '${minutesFromStart + 60} minutes', 1, 0
      from doctor d join app_user u on u.id = d.user_id where u.username = 'doctor' returning id
    )
    insert into appointment(patient_id, slot_id, status)
    select u.patient_id, slot.id, 'BOOKED' from app_user u cross join slot where u.username = 'patient-a'
    returning id;
  `))
}
