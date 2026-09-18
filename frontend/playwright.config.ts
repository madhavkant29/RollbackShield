import { defineConfig, devices } from '@playwright/test';

/**
 * E2E smoke test for the control room. Requires:
 *   1. the backend running (`local` profile), and
 *   2. a production build: `npm run build`
 * then `npm run test:e2e`. Set NEXT_PUBLIC_ROLLBACKSHIELD_API_URL at build
 * time if the backend is not on http://localhost:8080.
 *
 * Not wired into CI: it needs a live backend plus Docker-backed DynamoDB
 * for the full stack, which the GitHub workflow does not provision.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3000',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run start',
    url: process.env.E2E_BASE_URL ?? 'http://localhost:3000',
    reuseExistingServer: true,
    timeout: 120_000,
  },
});
