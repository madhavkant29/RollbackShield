import { createServer, IncomingMessage, Server } from 'node:http';
import { AddressInfo } from 'node:net';
import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { ControlPlaneClient, ApiError } from '../client.js';

/**
 * Drives the CLI's HTTP client against a real local HTTP server: request
 * shapes, bearer attachment, error-code parsing, and the response types the
 * commands render. No control-plane internals are re-implemented here.
 */
describe('ControlPlaneClient', () => {
  let server: Server;
  let baseUrl: string;
  const requests: Array<{ method: string; url: string; authorization?: string; body: string }> = [];

  before(async () => {
    server = createServer((request: IncomingMessage, response) => {
      let body = '';
      request.on('data', (chunk) => (body += chunk));
      request.on('end', () => {
        requests.push({
          method: request.method ?? '',
          url: request.url ?? '',
          authorization: request.headers.authorization,
          body,
        });
        response.setHeader('Content-Type', 'application/json');
        if (request.url === '/api/v1/integrations') {
          response.end(JSON.stringify([{
            integrationId: 'i-1', name: 'aws', connectorType: 'AWS', endpoint: 'us-east-1',
            connectionState: 'CONNECTED', healthState: 'HEALTHY', healthDetail: 'ok',
            lastSuccessfulSyncAt: null, lastError: null, capabilities: ['RUNTIME_DISCOVERY'],
          }]));
          return;
        }
        if (request.url === '/api/v1/releases/r-1/reversibility') {
          response.end(JSON.stringify({
            releaseId: 'r-1', status: 'AT_RISK', verdict: 'CANNOT_ROLLBACK',
            checks: [{ name: 'Database compatibility', passed: false,
              blockerCode: 'DESTRUCTIVE_DATABASE_MIGRATION', blockerDescription: 'drops a column',
              blockerSeverity: 'BLOCKING' }],
            evidence: [],
          }));
          return;
        }
        if (request.url === '/api/v1/missing') {
          response.statusCode = 404;
          response.end(JSON.stringify({ code: 'NOT_FOUND', message: 'no such thing' }));
          return;
        }
        response.end('{}');
      });
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  });

  after(() => server.close());

  it('attaches the bearer token and returns typed responses', async () => {
    const client = new ControlPlaneClient({ apiUrl: baseUrl, token: 'jwt-token' });
    const integrations = await client.listIntegrations();

    assert.equal(integrations.length, 1);
    assert.equal(integrations[0].connectionState, 'CONNECTED');
    assert.equal(requests.at(-1)?.authorization, 'Bearer jwt-token');
  });

  it('sends JSON bodies for commands that mutate', async () => {
    const client = new ControlPlaneClient({ apiUrl: baseUrl, token: null });
    await client.rollback('r-2', 'cli rollback');

    const request = requests.at(-1);
    assert.equal(request?.method, 'POST');
    assert.equal(request?.url, '/api/v1/releases/r-2/rollback');
    assert.deepEqual(JSON.parse(request?.body ?? '{}'), { reason: 'cli rollback' });
    assert.equal(request?.authorization, undefined);
  });

  it('surfaces stable API error codes instead of raw HTTP status text', async () => {
    const client = new ControlPlaneClient({ apiUrl: baseUrl, token: null });
    await assert.rejects(
      () => client.request('GET', '/api/v1/missing'),
      (error: unknown) => {
        assert.ok(error instanceof ApiError);
        assert.equal(error.status, 404);
        assert.equal(error.code, 'NOT_FOUND');
        assert.equal(error.message, 'no such thing');
        return true;
      },
    );
  });

  it('parses the preflight verdict the CLI returns as exit signal', async () => {
    const client = new ControlPlaneClient({ apiUrl: baseUrl, token: null });
    const report = await client.preflight('r-1');

    assert.equal(report.verdict, 'CANNOT_ROLLBACK');
    assert.equal(report.checks[0].blockerCode, 'DESTRUCTIVE_DATABASE_MIGRATION');
  });
});
