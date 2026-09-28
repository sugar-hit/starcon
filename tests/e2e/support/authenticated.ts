import { test as base, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
const password = Object.fromEntries(
  readFileSync('deploy/.env.catalog.local', 'utf8')
    .trim()
    .split('\n')
    .map((line) => line.split('=')),
).LOCAL_ADMIN_PASSWORD!;
// Existing business-shell tests now run inside the real application login boundary.
export const test = base.extend({
  page: async ({ page }, use) => {
    const response = await page.request.post('/api/iam/auth/login', {
      headers: { Origin: 'http://localhost:3000' },
      data: { username: 'local-admin', password, membershipId: null },
    });
    expect(response.ok()).toBeTruthy();
    await use(page);
  },
});
export { expect };
