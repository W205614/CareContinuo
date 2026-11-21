import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './preprod-tests',
  globalSetup: './preprod-tests/verify.ts',
  workers: 1,
  timeout: 120_000,
  use: {
    baseURL: process.env.CARE_PREPROD_ORIGIN,
    channel: process.env.CARE_BROWSER_CHANNEL || undefined,
    ignoreHTTPSErrors: true,
  },
})
