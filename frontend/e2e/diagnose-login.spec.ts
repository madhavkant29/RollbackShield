import { test, expect } from '@playwright/test';

test('real hosted ui login then api call', async ({ page }) => {
  await page.goto('https://main.d372yre6lvoau6.amplifyapp.com/');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.waitForURL(/amazoncognito\.com/, { timeout: 30_000 });

  const username = page.locator('input[name="username"], input[name="email"]').first();
  await username.waitFor({ state: 'visible', timeout: 60_000 });
  await username.fill(process.env.RS_USER ?? '');
  await page.locator('input[name="password"], input[type="password"]').first()
    .fill(process.env.RS_PASS ?? '');
  await page.locator('input[name="signInSubmitButton"], button[type="submit"]').first().click();

  await page.waitForURL(/amplifyapp\.com/, { timeout: 60_000 });
  console.log('AFTER_LOGIN_URL=' + page.url());
  await expect(page.getByText('signed in')).toBeVisible({ timeout: 20_000 });

  // authenticated GET through the full edge, from the browser itself
  const status = await page.evaluate(async () => {
    const raw = window.sessionStorage.getItem('rs.tokens');
    const token = raw ? JSON.parse(raw).idToken : null;
    const response = await fetch(`${window.location.origin}/api/v1/services`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    return response.status;
  });
  console.log('AUTH_GET_STATUS=' + status);
  expect(status).toBe(200);
});
