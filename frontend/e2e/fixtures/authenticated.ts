import { test as base } from '@playwright/test';

export const test = base.extend<{ authenticated: void }>({
  authenticated: [async ({ page }, use) => {
    await page.route('**/api/auth/session', route => route.fulfill({
      json: { code: 200, message: 'success', data: { username: 'test-user', admin: true } },
    }));
    await page.route('**/api/auth/csrf', route => route.fulfill({
      json: { code: 200, message: 'success', data: { headerName: 'X-CSRF-TOKEN', token: 'test' } },
    }));
    await use();
  }, { auto: true }],
});

export { expect } from '@playwright/test';
