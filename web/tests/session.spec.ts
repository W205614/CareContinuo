import { expect, test } from '@playwright/test'

test('API readiness and same-origin demo session', async ({ page, request }) => {
  const readiness = await request.get('/api/v1/status')
  expect(readiness.ok()).toBeTruthy()
  expect(await readiness.json()).toEqual({ service: 'carecontinuo', status: 'ready' })
  expect((await request.get('/api/v1/session')).status()).toBe(401)

  await page.goto('/')
  await page.getByLabel('账号').fill('patient-a')
  await page.getByLabel('密码').fill('demo-care-2026')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '患者甲' })).toBeVisible()
  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page.getByRole('heading', { name: '登录', exact: true })).toBeVisible()
})
