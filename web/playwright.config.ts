import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  workers: 1,
  use: {
    baseURL: process.env.CARE_WEB_URL ?? 'http://127.0.0.1:18080',
    channel: process.env.CARE_BROWSER_CHANNEL || undefined,
    trace: 'retain-on-failure',
  },
})
