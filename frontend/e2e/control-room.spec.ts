import { test, expect } from '@playwright/test';

/**
 * The control-room flow end to end against a live backend (local profile):
 * create a service, walk a release to READY, activate a rollback contract
 * (PROTECTED_ROLLOUT), roll back, and see the real timeline.
 */
test('service -> release -> contract -> rollback', async ({ page }) => {
  const serviceName = 'e2e-checkout-' + Date.now().toString().slice(-8);

  // Services are the entry point; manual creation is the documented dev fallback.
  await page.goto('/services');
  await page.getByPlaceholder('Manual service name (local dev only)').fill(serviceName);
  await page.getByRole('button', { name: 'Create manually' }).click();
  await expect(page.getByText(serviceName)).toBeVisible();

  // Open the service just created (scope to its row; other runs leave services).
  const row = page.getByRole('row', { name: new RegExp(serviceName) });
  await row.getByRole('button', { name: 'Open' }).click();
  await row.getByRole('link', { name: 'Releases →' }).click();
  await expect(page).toHaveURL(/\/releases\?serviceId=/);

  await page.getByRole('button', { name: 'Create release' }).click();
  await page.getByRole('button', { name: 'Prepare' }).click();
  await page.getByRole('button', { name: 'Mark ready' }).click();
  await page.getByRole('link', { name: /Open control room/ }).click();

  // READY: the contract form is the only way to reach PROTECTED_ROLLOUT.
  await page.getByRole('button', { name: 'Activate contract' }).click();
  await expect(page.getByText('PROTECTED_ROLLOUT')).toBeVisible();

  // The dimension headers are part of the control room contract.
  await expect(page.getByText('COMPUTE', { exact: true })).toBeVisible();
  await expect(page.getByText('ARTIFACT', { exact: true })).toBeVisible();
  await expect(page.getByText('DATABASE', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: 'Roll back' }).first().click();
  await page.getByRole('dialog').getByRole('button', { name: 'Roll back' }).click();
  await expect(page.getByText('ROLLED_BACK', { exact: true }).first()).toBeVisible();

  await expect(page.getByText('ROLLBACK_COMPLETED', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('CONTRACT_ACTIVATED', { exact: true }).first()).toBeVisible();
});
