import { test, expect } from '@playwright/test';

/**
 * Proves the control room attaches a Cognito access token as
 * `Authorization: Bearer ...` once signed in.
 *
 * Only meaningful in a build configured with NEXT_PUBLIC_COGNITO_DOMAIN /
 * NEXT_PUBLIC_COGNITO_CLIENT_ID; it skips cleanly otherwise (the local
 * profile needs no token). Run it against a Cognito-configured build:
 *
 *   NEXT_PUBLIC_COGNITO_DOMAIN=https://example.invalid \
 *   NEXT_PUBLIC_COGNITO_CLIENT_ID=test-client npm run build && npm run test:e2e
 */
test('attaches the Cognito access token as a Bearer header', async ({ page }) => {
  await page.goto('/');
  const signIn = page.getByRole('button', { name: 'Sign in' });
  const configured = await signIn.isVisible().catch(() => false);
  test.skip(!configured, 'build has no Cognito config; local profile sends no token');

  await page.addInitScript(() => {
    window.sessionStorage.setItem(
      'rs.tokens',
      JSON.stringify({
        accessToken: 'fake-access-token',
        refreshToken: 'fake-refresh-token',
        idToken: null,
        expiresAt: Date.now() + 3_600_000,
      }),
    );
  });

  let authorization: string | undefined;
  await page.route('**/api/v1/services', async (route) => {
    authorization = route.request().headers()['authorization'];
    await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
  });

  await page.reload();

  await expect.poll(() => authorization).toBe('Bearer fake-access-token');
});
