import { defineConfig } from '@playwright/test';
const baseURL = process.env.PLAYWRIGHT_BASE_URL ?? 'http://localhost:3000';
const port = new URL(baseURL).port || '3000';

export default defineConfig({
  testDir: './tests/e2e',
  outputDir: './work/test-results',
  fullyParallel: false,
  workers: 1,
  use: {
    baseURL,
    headless: true,
    channel: 'chrome',
    screenshot: 'only-on-failure',
  },
  reporter: 'list',
  webServer: {
    command: `pnpm --filter @starcon/web exec next dev -p ${port}`,
    url: `${baseURL}/styleguide`,
    reuseExistingServer: !process.env.CI,
    timeout: 120000,
  },
});
