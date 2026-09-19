import { test } from '@playwright/test';

test('diagnose cognito page failure with network evidence', async ({ page }) => {
  const responses: string[] = [];
  const consoleLines: string[] = [];
  page.on('response', (response) => {
    const url = response.url();
    if (response.status() >= 300 || url.includes('amazoncognito.com')) {
      responses.push(`${response.status()} ${response.request().method()} ${url}`);
    }
  });
  page.on('requestfailed', (request) => {
    responses.push(`FAILED ${request.method()} ${request.url()} :: ${request.failure()?.errorText}`);
  });
  page.on('console', (message) => consoleLines.push(`${message.type()}: ${message.text()}`));

  await page.goto('https://main.d372yre6lvoau6.amplifyapp.com/');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.waitForTimeout(8_000);

  console.log('FINAL_URL=' + page.url());
  const cookies = await page.context().cookies();
  console.log('COOKIES=' + cookies.map((cookie) => `${cookie.domain}${cookie.path}:${cookie.name}`).join(','));
  const bodyText = await page.textContent('body').catch(() => '');
  console.log('BODY=' + (bodyText ?? '').replace(/\s+/g, ' ').slice(0, 300));
  console.log('RESPONSES_START');
  for (const line of responses.slice(0, 40)) console.log(line);
  console.log('RESPONSES_END');
  console.log('CONSOLE_START');
  for (const line of consoleLines.slice(0, 30)) console.log(line);
  console.log('CONSOLE_END');
});
