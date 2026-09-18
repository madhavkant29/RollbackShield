import { test, expect } from '@playwright/test';

/**
 * The §26 control-room flow end to end against a live backend: create a
 * service, walk a release to READY, activate a rollback contract (which
 * moves it to PROTECTED_ROLLOUT), roll back, and see the real audit trail.
 * This is the committed counterpart to the manual browser check that first
 * surfaced the missing CORS config and missing contract UI.
 */
test('service -> release -> contract -> rollback', async ({ page }) => {
  const serviceName = 'e2e-checkout-' + Date.now().toString().slice(-8);

  await page.goto('/services');
  await page.getByPlaceholder('Service name, e.g. checkout').fill(serviceName);
  await page.getByRole('button', { name: 'Add service' }).click();
  await expect(page.getByText(serviceName)).toBeVisible();

  // Overview links a service to its release list.
  await page.goto('/');
  await page.getByRole('link', { name: new RegExp(serviceName) }).click();
  await expect(page).toHaveURL(/\/releases\?serviceId=/);

  await page.getByRole('button', { name: 'Create release' }).click();
  await page.getByRole('button', { name: 'Prepare' }).click();
  await page.getByRole('button', { name: 'Mark ready' }).click();
  await page.getByRole('link', { name: /Open control room/ }).click();

  // READY: the contract form is the only way to reach PROTECTED_ROLLOUT.
  await page.getByRole('button', { name: 'Activate contract' }).click();
  await expect(page.getByText('PROTECTED_ROLLOUT')).toBeVisible();

  await page.getByRole('button', { name: 'Roll back' }).first().click();
  await page.getByRole('dialog').getByRole('button', { name: 'Roll back' }).click();
  await expect(page.getByText('ROLLED_BACK')).toBeVisible();

  await expect(page.getByText('ROLLBACK_COMPLETED')).toBeVisible();
  await expect(page.getByText('CONTRACT_ACTIVATED')).toBeVisible();
});
