import { expect, test } from '@playwright/test'
import { loginInBrowser } from './fixtures'

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

test('desktop staff navigation opens the follow-up workspace', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await loginInBrowser(page, 'followup')
  const navigation = page.getByRole('navigation', { name: '工作台导航' })
  await expect(navigation.getByRole('link', { name: '工作待办' })).toHaveAttribute('aria-current', 'location')
  await expect(navigation.getByRole('link', { name: '我的预约' })).toHaveCount(0)

  const followup = navigation.getByRole('link', { name: '随访工作台' })
  await followup.click()
  await expect(page).toHaveURL(/#followup-title$/)
  await expect(followup).toHaveAttribute('aria-current', 'location')
  await expect(page.getByRole('heading', { name: '随访工作台' })).toBeInViewport()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(1440)
})

test.describe('mobile workspace', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })

  test('patient can reach appointments and follow-ups through role navigation', async ({ page }) => {
    await loginInBrowser(page, 'patient-a')
    const navigation = page.getByRole('navigation', { name: '工作台导航' })
    await expect(navigation.getByRole('link', { name: '待处理事项' })).toHaveAttribute('aria-current', 'location')
    await expect(page.getByRole('region', { name: '需要我处理的事' }).getByText('常规安排', { exact: true })).toBeVisible()
    await expect(navigation.getByRole('link', { name: '工作待办' })).toHaveCount(0)

    const appointments = navigation.getByRole('link', { name: '预约挂号' })
    await appointments.click()
    await expect(page).toHaveURL(/#schedule-workspace$/)
    await expect(appointments).toHaveAttribute('aria-current', 'location')
    await expect(page.locator('#slots-title')).toBeInViewport()

    const followup = navigation.getByRole('link', { name: '我的随访' })
    await followup.click()
    await expect(page).toHaveURL(/#followup-title$/)
    await expect(followup).toHaveAttribute('aria-current', 'location')
    await expect(page.getByRole('heading', { name: '我的随访' })).toBeInViewport()
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  })
})
