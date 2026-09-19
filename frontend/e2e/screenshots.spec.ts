import { test } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Captures real screenshots of the deployed product. The control plane
 * requires real Cognito auth; the ID token is injected into sessionStorage
 * (the same store the PKCE flow writes) via RS_ID_TOKEN so no fake session
 * state is used. Skips when the token is absent.
 */
const OUT = join(__dirname, '..', '..', 'docs', 'screenshots');

test.describe.configure({ mode: 'serial' });

test('capture deployed product screenshots', async ({ page }) => {
  const token = process.env.RS_ID_TOKEN;
  test.skip(!token, 'RS_ID_TOKEN not set');
  mkdirSync(OUT, { recursive: true });

  await page.addInitScript((value: string) => {
    window.sessionStorage.setItem('rs.tokens', JSON.stringify({
      accessToken: value,
      idToken: value,
      refreshToken: null,
      expiresAt: Date.now() + 3_600_000,
    }));
  }, token as string);

  const shots: Array<{ path: string; file: string; waitFor?: string }> = [
    { path: '/integrations', file: '01-integrations.png' },
    { path: '/services', file: '02-services-mapping.png', waitFor: 'payments-verify' },
    { path: '/releases', file: '03-releases.png' },
    { path: '/reversibility', file: '04-reversibility.png' },
    { path: '/audit', file: '05-audit.png' },
    { path: '/settings', file: '06-settings.png' },
  ];

  for (const shot of shots) {
    await page.goto(shot.path);
    if (shot.waitFor) {
      await page.getByText(shot.waitFor).first().waitFor({ timeout: 20_000 }).catch(() => {});
    }
    await page.waitForTimeout(1_500);
    await page.screenshot({ path: join(OUT, shot.file), fullPage: true });
  }

  // Fresh service open: mapping bindings with confidence and evidence.
  await page.goto('/services');
  const row = page.getByRole('row', { name: /payments-verify/ }).last();
  await row.getByRole('button', { name: 'Open' }).click();
  await page.waitForTimeout(1_500);
  await page.screenshot({ path: join(OUT, '07-mapping-evidence.png'), fullPage: true });

  // Release control room with the real evidence and timeline.
  const releaseId = process.env.RS_RELEASE_ID;
  if (releaseId) {
    await page.goto(`/releases/${releaseId}`);
    await page.waitForTimeout(2_000);
    await page.screenshot({ path: join(OUT, '08-control-room.png'), fullPage: true });
  }
});
