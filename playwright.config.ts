import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests/e2e',
  outputDir: './work/test-results',
  fullyParallel: false,
  workers: 1,
  use: {
    baseURL: 'http://localhost:3000',
    headless: true,
    channel: 'chrome',
    screenshot: 'only-on-failure',
  },
  reporter: 'list',
  webServer: {
    command: 'pnpm --filter @starcon/web dev',
    url: 'http://localhost:3000/styleguide',
    reuseExistingServer: !process.env.CI,
    timeout: 120000,
  },
});
