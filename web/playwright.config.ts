import { defineConfig } from '@playwright/test'

const baseURL = process.env.CARE_WEB_URL
if (!baseURL || !process.env.CARE_E2E_DB_CONTAINER) {
  throw new Error('CARE_WEB_URL and CARE_E2E_DB_CONTAINER are required. See CONTRIBUTING.md.')
}

export default defineConfig({
  globalSetup: './tests/verify-environment.ts',
  testDir: './tests',
  workers: 1,
  use: {
    baseURL,
    channel: process.env.CARE_BROWSER_CHANNEL || undefined,
    trace: 'retain-on-failure',
  },
})
