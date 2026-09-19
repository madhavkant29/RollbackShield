'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import {
  api,
  ApiError,
  type IntegrationSummary,
  type Release,
  type ReversibilityReport,
  type ServiceSummary,
} from '@/lib/api';

interface ServiceState {
  service: ServiceSummary;
  release: Release | null;
  preflight: ReversibilityReport | null;
}

/**
 * Real operational state only: connected systems, protected services,
 * active releases, actual blockers and recent deployments. If a number is
 * shown, it was fetched from the control plane.
 */
export default function OverviewPage() {
  const [integrations, setIntegrations] = useState<IntegrationSummary[]>([]);
  const [services, setServices] = useState<ServiceState[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    async function load() {
      try {
        const [integrationList, serviceList] = await Promise.all([
          api.listIntegrations(),
          api.listServices(),
        ]);
        setIntegrations(integrationList);
        const states: ServiceState[] = [];
        for (const service of serviceList) {
          const releases = await api.listReleases(service.serviceId);
          const release = releases.find((candidate) =>
            ['PROTECTED_ROLLOUT', 'AT_RISK', 'DRAFT', 'PREPARING', 'READY'].includes(candidate.state))
            ?? releases[0] ?? null;
          const preflight = release
            ? await api.getReversibility(release.releaseId).catch(() => null)
            : null;
          states.push({ service, release, preflight });
        }
        setServices(states);
        setError(null);
      } catch (e) {
        setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Control plane unreachable');
      } finally {
        setLoaded(true);
      }
    }
    void load();
  }, []);

  const connected = integrations.filter((integration) => integration.connectionState === 'CONNECTED');
  const protectedCount = services.filter(({ preflight }) => preflight?.status === 'REVERSIBLE').length;
  const blocked = services.filter(({ preflight }) => preflight?.verdict === 'CANNOT_ROLLBACK');
  const activeReleases = services.filter(({ release }) =>
    release && ['PROTECTED_ROLLOUT', 'AT_RISK', 'ROLLING_BACK'].includes(release.state));

  return (
    <div className="mx-auto max-w-5xl px-4 py-6 md:px-8 md:py-8">
      <h1 className="mb-1 text-2xl text-ink-primary">Overview</h1>
      <p className="mb-6 text-sm text-ink-secondary">
        Deployment reversibility across your services, read from connected systems.
      </p>

      {error && (
        <p className="mb-6 text-sm text-status-fail">
          {error} — is the backend running at <code className="font-mono">http://localhost:8080</code>?
        </p>
      )}

      <div className="mb-8 grid grid-cols-2 gap-x-8 gap-y-3 border-y border-border py-4 md:grid-cols-4">
        <Metric label="Connected systems" value={`${connected.length}/${integrations.length}`}
                detail={connected.length === 0 ? 'nothing connected yet' : undefined} />
        <Metric label="Services" value={String(services.length)} />
        <Metric label="Reversible now" value={String(protectedCount)} />
        <Metric label="Cannot roll back" value={String(blocked.length)}
                tone={blocked.length > 0 ? 'fail' : undefined} />
      </div>

      {integrations.some((integration) => integration.connectionState === 'ERROR') && (
        <div className="mb-6 border border-status-fail/40 bg-panel p-3">
          <div className="mb-1 font-mono text-xs text-status-fail">INTEGRATION ERRORS</div>
          {integrations.filter((integration) => integration.connectionState === 'ERROR').map((integration) => (
            <p key={integration.integrationId} className="text-xs text-ink-secondary">
              <span className="font-mono">{integration.name}</span>: {integration.lastError}
            </p>
          ))}
        </div>
      )}

      <h2 className="mb-3 text-sm text-ink-secondary">Services and active releases</h2>
      {loaded && services.length === 0 && (
        <p className="text-sm text-ink-tertiary">
          No services yet. Connect a system on the{' '}
          <Link href="/integrations" className="text-accent underline">Integrations</Link> page, then
          import a discovered service.
        </p>
      )}

      {services.length > 0 && (
        <div className="overflow-hidden border border-border bg-panel">
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="border-b border-border text-xs text-ink-tertiary">
                <th className="px-4 py-2 font-normal">Service</th>
                <th className="px-4 py-2 font-normal">Release</th>
                <th className="px-4 py-2 font-normal">State</th>
                <th className="px-4 py-2 font-normal">Reversibility</th>
                <th className="px-4 py-2 font-normal">Top blocker</th>
              </tr>
            </thead>
            <tbody>
              {services.map(({ service, release, preflight }) => {
                const firstFailure = preflight?.checks.find((check) => !check.passed);
                return (
                  <tr key={service.serviceId} className="border-b border-border last:border-b-0">
                    <td className="px-4 py-3">
                      <Link href="/services" className="text-ink-primary hover:underline">
                        {service.name}
                      </Link>
                    </td>
                    <td className="px-4 py-3 font-mono text-xs text-ink-secondary">
                      {release ? (
                        <Link href={`/releases/${release.releaseId}`} className="text-accent hover:underline">
                          {release.previousVersionLabel} → {release.candidateVersionLabel}
                        </Link>
                      ) : '—'}
                    </td>
                    <td className="px-4 py-3 font-mono text-xs text-ink-secondary">
                      {release?.state ?? '—'}
                    </td>
                    <td className="px-4 py-3 font-mono text-xs">
                      {preflight ? (
                        <span className={
                          preflight.verdict === 'CAN_ROLLBACK' ? 'text-status-pass'
                            : preflight.verdict === 'CANNOT_ROLLBACK' ? 'text-status-fail'
                              : 'text-status-warn'
                        }>
                          {preflight.verdict}
                        </span>
                      ) : '—'}
                    </td>
                    <td className="px-4 py-3 text-[11px] text-ink-tertiary">
                      {firstFailure
                        ? <><span className="font-mono text-status-fail">{firstFailure.blockerCode}</span>{' '}
                          {firstFailure.blockerDescription}</>
                        : preflight ? 'none' : '—'}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {activeReleases.length > 0 && (
        <p className="mt-4 text-xs text-ink-tertiary">
          {activeReleases.length} release{activeReleases.length === 1 ? '' : 's'} in a protected
          window — roll back or commit from the release control room.
        </p>
      )}
    </div>
  );
}

function Metric({ label, value, detail, tone }: {
  label: string;
  value: string;
  detail?: string;
  tone?: 'fail';
}) {
  return (
    <div>
      <div className="text-xs text-ink-tertiary">{label}</div>
      <div className={`font-mono text-xl ${tone === 'fail' ? 'text-status-fail' : 'text-ink-primary'}`}>
        {value}
      </div>
      {detail && <div className="text-[11px] text-ink-tertiary">{detail}</div>}
    </div>
  );
}
