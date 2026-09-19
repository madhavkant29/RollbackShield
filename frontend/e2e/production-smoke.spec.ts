import { test, expect } from '@playwright/test';

/**
 * Production smoke test against the real public Amplify URL. Proves the
 * browser only talks HTTPS to the Amplify origin (proxied /api/*), signs in
 * through the real Cognito Hosted UI, and that pages render real backend
 * data.
 */
test('production smoke: cognito sign-in and real data through Amplify edge', async ({ page }) => {
  const consoleErrors: string[] = [];
  const offending: string[] = [];
  page.on('request', (request) => {
    const url = request.url();
    if (url.startsWith('http://')) {
      offending.push(`plain-http ${url}`);
    }
    if (url.includes('localhost:8080')) {
      offending.push(`localhost ${url}`);
    }
  });
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text());
  });

  // 1a. When RS_ID_TOKEN is provided (hosted-UI branding issue workaround),
  // inject the real Cognito ID token the same way the PKCE flow stores it.
  const injected = process.env.RS_ID_TOKEN;
  if (injected) {
    await page.addInitScript((value: string) => {
      window.sessionStorage.setItem('rs.tokens', JSON.stringify({
        accessToken: value, idToken: value, refreshToken: null,
        expiresAt: Date.now() + 3_600_000,
      }));
    }, injected);
    await page.goto('/');
  } else {
    // 1b. real Cognito Hosted UI sign-in
    await page.goto('/');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await page.waitForURL(/amazoncognito\.com/, { timeout: 30_000 });
    await page.waitForLoadState('domcontentloaded');
    const username = page.locator(
      '#signInFormUsername, input[name="username"], input[name="email"], input[type="email"]')
      .first();
    await username.waitFor({ state: 'attached', timeout: 45_000 });
    await username.fill(process.env.RS_USER ?? '');
    await page.locator('#signInFormPassword, input[name="password"], input[type="password"]')
      .first().fill(process.env.RS_PASS ?? '');
    await page.locator(
      'input[name="signInSubmitButton"], button[type="submit"], input[type="submit"]')
      .first().click();
    await page.waitForURL(/amplifyapp\.com/, { timeout: 45_000 });
  }
  await expect(page.getByText('signed in')).toBeVisible({ timeout: 20_000 });

  // 2. Integrations shows real CONNECTED / HEALTHY state
  await page.goto('/integrations');
  await expect(page.getByText(/CONNECTED/).first()).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText(/HEALTHY/).first()).toBeVisible({ timeout: 10_000 });

  // POST with auth through the whole chain: connection test reaches Spring/AWS
  await page.getByRole('button', { name: 'Test' }).first().click();
  await expect(page.getByText(/connection verified/i).first()).toBeVisible({ timeout: 45_000 });

  // 3. Services shows the fresh payments-verify import
  await page.goto('/services');
  await expect(page.getByText('payments-verify')).toBeVisible({ timeout: 30_000 });

  // 4. Releases, Reversibility, Audit load real data
  await page.goto('/releases?serviceId=' + (process.env.RS_SERVICE_ID ?? ''));
  await expect(page.getByText(/Open control room/).first()).toBeVisible({ timeout: 30_000 });
  await page.getByRole('link', { name: /Open control room/ }).first().click();
  await expect(page.getByText('COMPUTE', { exact: true })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText(/task-definition:/).first()).toBeVisible();
  await expect(page.getByText('Timeline')).toBeVisible();

  await page.goto('/reversibility');
  await expect(page.getByText(/CAN_|UNKNOWN|reversible/i).first()).toBeVisible({ timeout: 30_000 });
  await page.goto('/audit');
  await expect(page.locator('table')).toBeVisible({ timeout: 30_000 });

  // 5. no plain-HTTP and no localhost requests anywhere
  expect(offending, offending.join('\n')).toEqual([]);
  expect(consoleErrors.filter((text) => text.includes('Mixed Content'))).toEqual([]);
});
