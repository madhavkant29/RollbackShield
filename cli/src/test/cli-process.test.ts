import { spawn } from 'node:child_process';
import { createServer, Server } from 'node:http';
import { AddressInfo } from 'node:net';
import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

/**
 * Spawns the built CLI as a real child process against a local stub server.
 * This is the regression test for the exit-code defect found in the audit:
 * process.exit() racing undici socket teardown crashed the runtime on
 * Windows/Node 25 with a garbage exit code and could truncate stdout.
 * The CLI must exit 0 with intact stdout and no runtime assertion noise.
 */
describe('rollbackshield CLI process', () => {
  let server: Server;
  let baseUrl: string;

  before(async () => {
    server = createServer((request, response) => {
      response.setHeader('Content-Type', 'application/json');
      if (request.url === '/api/v1/services') {
        response.end(JSON.stringify([{ serviceId: 'svc-1', organizationId: 'org-1', name: 'payments' }]));
        return;
      }
      response.end('{}');
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  });

  after(() => server.close());

  function runCli(args: string[]): Promise<{ code: number | null; stdout: string; stderr: string }> {
    const entry = fileURLToPath(new URL('../index.js', import.meta.url));
    return new Promise((resolve) => {
      const child = spawn(process.execPath, [entry, ...args], {
        env: { ...process.env, ROLLBACKSHIELD_API_URL: baseUrl, ROLLBACKSHIELD_TOKEN: '' },
      });
      let stdout = '';
      let stderr = '';
      child.stdout.on('data', (chunk) => (stdout += chunk));
      child.stderr.on('data', (chunk) => (stderr += chunk));
      child.on('close', (code) => resolve({ code, stdout, stderr }));
    });
  }

  it('exits 0 with intact JSON output and no runtime assertion', async () => {
    const result = await runCli(['services', 'list', '--json']);

    assert.equal(result.code, 0);
    const services = JSON.parse(result.stdout);
    assert.equal(services[0].name, 'payments');
    assert.equal(result.stderr, '', `stderr must be empty, got: ${result.stderr}`);
  });

  it('returns exit code 2 for usage errors without crashing', async () => {
    const result = await runCli(['integrations', 'test']);

    assert.equal(result.code, 2);
    assert.match(result.stderr, /integrationId is required/);
  });
});
