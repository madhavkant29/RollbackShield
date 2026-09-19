'use client';

import { useEffect, useState } from 'react';
import {
  api,
  ApiError,
  type IntegrationSummary,
  type SyncResultResponse,
} from '@/lib/api';
import { IntegrationResources } from '@/components/IntegrationResources';

const CONNECTOR_TYPES = ['AWS', 'GITHUB', 'KUBERNETES', 'POSTGRESQL', 'FLYWAY'] as const;

const CREDENTIAL_KINDS: Record<string, Array<{ value: string; label: string }>> = {
  AWS: [
    { value: 'AWS_CONTROL_PLANE_ROLE', label: 'Control-plane role (hackathon)' },
    { value: 'AWS_ASSUME_ROLE', label: 'AssumeRole (customer account)' },
  ],
  GITHUB: [
    { value: 'GITHUB_APP', label: 'GitHub App installation' },
    { value: 'GITHUB_TOKEN', label: 'Token from secret store (dev)' },
    { value: 'GITHUB_PUBLIC', label: 'Public repositories (no credentials)' },
  ],
  KUBERNETES: [{ value: 'KUBERNETES_KUBECONFIG', label: 'Kubeconfig from secret store' }],
  POSTGRESQL: [{ value: 'POSTGRES_PASSWORD', label: 'Password from secret store' }],
  FLYWAY: [{ value: 'NONE', label: 'None (local directory)' }],
};

function stateLabel(integration: IntegrationSummary): string {
  if (integration.connectionState !== 'CONNECTED') {
    return integration.connectionState;
  }
  return `CONNECTED · ${integration.healthState}`;
}

export default function IntegrationsPage() {
  const [integrations, setIntegrations] = useState<IntegrationSummary[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [showConnect, setShowConnect] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const [type, setType] = useState<string>('AWS');
  const [name, setName] = useState('');
  const [endpoint, setEndpoint] = useState('us-east-1');
  const [credentialKind, setCredentialKind] = useState('AWS_CONTROL_PLANE_ROLE');
  const [secretReference, setSecretReference] = useState('');
  const [roleArn, setRoleArn] = useState('');
  const [externalId, setExternalId] = useState('');

  function load() {
    api.listIntegrations()
      .then((list) => {
        setIntegrations(list);
        setError(null);
      })
      .catch((e) => setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Control plane unreachable'));
  }

  useEffect(load, []);

  useEffect(() => {
    setCredentialKind(CREDENTIAL_KINDS[type]?.[0]?.value ?? 'NONE');
    setEndpoint(type === 'AWS' ? 'us-east-1' : type === 'GITHUB' ? 'https://api.github.com' : '');
  }, [type]);

  async function connect(e: React.FormEvent) {
    e.preventDefault();
    setBusy('connect');
    try {
      const configuration: Record<string, string> = {};
      if (type === 'FLYWAY') configuration.directory = endpoint;
      await api.createIntegration({
        name: name.trim(),
        type,
        endpoint: endpoint.trim(),
        credential: {
          kind: credentialKind,
          secretReference: secretReference.trim() || undefined,
          roleArn: roleArn.trim() || undefined,
          externalId: externalId.trim() || undefined,
        },
        configuration,
      });
      setShowConnect(false);
      setName('');
      setSecretReference('');
      setRoleArn('');
      setExternalId('');
      setNotice('Integration created as CONNECTING. Run a connection test to verify it.');
      load();
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : 'Create failed');
    } finally {
      setBusy(null);
    }
  }

  async function test(integration: IntegrationSummary) {
    setBusy(`test:${integration.integrationId}`);
    try {
      const result = await api.testIntegration(integration.integrationId);
      setNotice(`${integration.name}: ${result.success ? 'connection verified' : 'connection failed'} — ${result.message}`);
      load();
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : 'Connection test failed');
    } finally {
      setBusy(null);
    }
  }

  async function sync(integration: IntegrationSummary) {
    setBusy(`sync:${integration.integrationId}`);
    try {
      const result: SyncResultResponse = await api.syncIntegration(integration.integrationId);
      if (result.errorCount > 0) {
        setError(`${integration.name}: ${result.errorCount} sync error(s) — ${result.errors.join('; ')}`);
      } else {
        setNotice(`${integration.name}: discovered ${result.discoveredCount} resources`);
        setSelected(integration.integrationId);
      }
      load();
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : 'Sync failed');
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="mx-auto max-w-5xl px-4 py-6 md:px-8 md:py-8">
      <div className="mb-6 flex items-start justify-between">
        <div>
          <h1 className="mb-1 text-2xl text-ink-primary">Integrations</h1>
          <p className="text-sm text-ink-secondary">
            Connected systems RollbackShield observes. A state is only CONNECTED after a real
            provider call succeeds.
          </p>
        </div>
        <button
          onClick={() => setShowConnect((v) => !v)}
          className="rounded-sm border border-border-strong px-3 py-1.5 text-sm text-ink-primary hover:bg-panel-alt"
        >
          {showConnect ? 'Cancel' : 'Connect system'}
        </button>
      </div>

      {error && <p className="mb-4 text-sm text-status-fail">{error}</p>}
      {notice && <p className="mb-4 text-sm text-accent">{notice}</p>}

      {showConnect && (
        <form onSubmit={connect} className="mb-8 border border-border bg-panel p-4">
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <label className="text-xs text-ink-tertiary">
              Connector
              <select
                value={type}
                onChange={(e) => setType(e.target.value)}
                className="mt-1 w-full rounded-sm border border-border bg-base px-2 py-1.5 text-sm text-ink-primary"
              >
                {CONNECTOR_TYPES.map((value) => (
                  <option key={value} value={value}>{value}</option>
                ))}
              </select>
            </label>
            <label className="text-xs text-ink-tertiary">
              Name
              <input
                required
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="aws-hackathon"
                className="mt-1 w-full rounded-sm border border-border bg-base px-2 py-1.5 text-sm text-ink-primary"
              />
            </label>
            <label className="text-xs text-ink-tertiary">
              {type === 'AWS' ? 'Region' : type === 'FLYWAY' ? 'Directory' : 'Endpoint'}
              <input
                required
                value={endpoint}
                onChange={(e) => setEndpoint(e.target.value)}
                className="mt-1 w-full rounded-sm border border-border bg-base px-2 py-1.5 text-sm text-ink-primary"
              />
            </label>
            <label className="text-xs text-ink-tertiary">
              Credentials
              <select
                value={credentialKind}
                onChange={(e) => setCredentialKind(e.target.value)}
                className="mt-1 w-full rounded-sm border border-border bg-base px-2 py-1.5 text-sm text-ink-primary"
              >
                {(CREDENTIAL_KINDS[type] ?? []).map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
            </label>
            {credentialKind === 'AWS_ASSUME_ROLE' && (
              <>
                <label className="text-xs text-ink-tertiary">
                  Role ARN
                  <input
                    required
                    value={roleArn}
                    onChange={(e) => setRoleArn(e.target.value)}
                    placeholder="arn:aws:iam::<account>:role/RollbackShieldObservation"
                    className="mt-1 w-full rounded-sm border border-border bg-base px-2 py-1.5 font-mono text-xs text-ink-primary"
                  />
                </label>
                <label className="text-xs text-ink-tertiary">
                  External id
                  <input
                    value={externalId}
                    onChange={(e) => setExternalId(e.target.value)}
                    className="mt-1 w-full rounded-sm border border-border bg-base px-2 py-1.5 font-mono text-xs text-ink-primary"
                  />
                </label>
              </>
            )}
            {['GITHUB_APP', 'GITHUB_TOKEN', 'KUBERNETES_KUBECONFIG', 'POSTGRES_PASSWORD'].includes(credentialKind) && (
              <label className="text-xs text-ink-tertiary">
                Secret reference (never the secret)
                <input
                  required
                  value={secretReference}
                  onChange={(e) => setSecretReference(e.target.value)}
                  placeholder="ENV_VAR_OR_SECRET_NAME"
                  className="mt-1 w-full rounded-sm border border-border bg-base px-2 py-1.5 font-mono text-xs text-ink-primary"
                />
              </label>
            )}
          </div>
          <p className="mt-3 text-xs text-ink-tertiary">
            Credentials are stored as references only. Locally, a reference names an environment
            variable; in AWS it names a Secrets Manager secret read by the task role.
          </p>
          <button
            type="submit"
            disabled={busy === 'connect'}
            className="mt-3 rounded-sm border border-border-strong px-4 py-1.5 text-sm text-ink-primary hover:bg-panel-alt disabled:opacity-40"
          >
            {busy === 'connect' ? 'Creating…' : 'Create integration'}
          </button>
        </form>
      )}

      <div className="overflow-hidden border border-border bg-panel">
        <table className="w-full text-left text-sm">
          <thead>
            <tr className="border-b border-border text-xs text-ink-tertiary">
              <th className="px-4 py-2 font-normal">System</th>
              <th className="px-4 py-2 font-normal">Type</th>
              <th className="px-4 py-2 font-normal">Endpoint</th>
              <th className="px-4 py-2 font-normal">State</th>
              <th className="px-4 py-2 font-normal">Last successful sync</th>
              <th className="px-4 py-2 font-normal">Actions</th>
            </tr>
          </thead>
          <tbody>
            {integrations.length === 0 && (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-sm text-ink-tertiary">
                  No integrations connected.
                </td>
              </tr>
            )}
            {integrations.map((integration) => (
              <tr key={integration.integrationId}
                  className="border-b border-border last:border-b-0 align-top">
                <td className="px-4 py-3">
                  <div className="text-ink-primary">{integration.name}</div>
                  <div className="font-mono text-[11px] text-ink-tertiary">
                    {integration.capabilities.join(', ') || 'no capabilities'}
                  </div>
                  <div className="text-[11px] text-ink-tertiary">
                    {integration.discoveredResourceCount} resource
                    {integration.discoveredResourceCount === 1 ? '' : 's'} discovered
                  </div>
                  {Object.keys(integration.configuration).length > 0 && (
                    <div className="font-mono text-[11px] text-ink-tertiary"
                         title={Object.entries(integration.configuration)
                           .map(([key, value]) => `${key}=${value}`).join('  ')}>
                      {Object.entries(integration.configuration)
                        .map(([key, value]) => `${key}=${value}`).join('  ')}
                    </div>
                  )}
                </td>
                <td className="px-4 py-3 font-mono text-xs text-ink-secondary">
                  {integration.connectorType}
                </td>
                <td className="px-4 py-3 font-mono text-xs text-ink-secondary">
                  {integration.endpoint}
                </td>
                <td className="px-4 py-3">
                  <span className={`font-mono text-xs ${
                    integration.connectionState === 'CONNECTED'
                      ? integration.healthState === 'HEALTHY' ? 'text-status-pass' : 'text-status-warn'
                      : 'text-status-fail'
                  }`}>
                    {stateLabel(integration)}
                  </span>
                  {integration.lastError && (
                    <div className="mt-1 max-w-xs text-[11px] text-status-fail">
                      {integration.lastError}
                    </div>
                  )}
                </td>
                <td className="px-4 py-3 font-mono text-xs text-ink-tertiary">
                  {integration.lastSuccessfulSyncAt
                    ? new Date(integration.lastSuccessfulSyncAt).toLocaleString()
                    : 'never'}
                </td>
                <td className="px-4 py-3">
                  <div className="flex flex-wrap gap-3">
                    <button
                      onClick={() => test(integration)}
                      disabled={busy !== null}
                      className="text-xs text-accent hover:underline disabled:opacity-40"
                    >
                      {busy === `test:${integration.integrationId}` ? 'Testing…' : 'Test'}
                    </button>
                    <button
                      onClick={() => sync(integration)}
                      disabled={busy !== null || integration.connectionState !== 'CONNECTED'}
                      className="text-xs text-accent hover:underline disabled:opacity-40"
                    >
                      {busy === `sync:${integration.integrationId}` ? 'Syncing…' : 'Sync'}
                    </button>
                    <button
                      onClick={() => setSelected(
                        selected === integration.integrationId ? null : integration.integrationId)}
                      className="text-xs text-accent hover:underline"
                    >
                      {selected === integration.integrationId ? 'Hide resources' : 'Resources'}
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {selected && (
        <div className="mt-6 border border-border bg-panel p-4">
          <h2 className="mb-3 text-sm text-ink-secondary">Discovered resources</h2>
          <IntegrationResources
            integrationId={selected}
            onImported={(imported) => {
              setNotice(`Imported ${imported.name} (${imported.serviceId})`);
              setSelected(null);
            }}
          />
        </div>
      )}
    </div>
  );
}
