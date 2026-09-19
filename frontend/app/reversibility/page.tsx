'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import {
  api,
  ApiError,
  type Release,
  type ReversibilityReport,
  type ServiceSummary,
} from '@/lib/api';

interface Row {
  service: ServiceSummary;
  release: Release | null;
  preflight: ReversibilityReport | null;
}

const VERDICT_STYLE: Record<string, string> = {
  CAN_ROLLBACK: 'text-status-pass',
  CANNOT_ROLLBACK: 'text-status-fail',
  UNKNOWN: 'text-status-warn',
};

/**
 * Reversibility rollup: for every service, the current release and the
 * preflight verdict read from live state. This is deliberately a table:
 * operators need to scan for the services that cannot roll back.
 */
export default function ReversibilityPage() {
  const [rows, setRows] = useState<Row[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    async function load() {
      try {
        const services = await api.listServices();
        const result: Row[] = [];
        for (const service of services) {
          const releases = await api.listReleases(service.serviceId);
          const release = releases.find((candidate) =>
            ['PROTECTED_ROLLOUT', 'AT_RISK', 'DRAFT', 'PREPARING', 'READY'].includes(candidate.state))
            ?? releases[0] ?? null;
          let preflight: ReversibilityReport | null = null;
          if (release) {
            preflight = await api.getReversibility(release.releaseId).catch(() => null);
          }
          result.push({ service, release, preflight });
        }
        setRows(result);
      } catch (e) {
        setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Control plane unreachable');
      }
    }
    void load();
  }, []);

  return (
    <div className="mx-auto max-w-5xl px-4 py-6 md:px-8 md:py-8">
      <h1 className="mb-1 text-2xl text-ink-primary">Reversibility</h1>
      <p className="mb-6 text-sm text-ink-secondary">
        Whether each service can return to its previous release right now, from live evidence.
      </p>

      {error && <p className="mb-4 text-sm text-status-fail">{error}</p>}
      {rows === null && !error && <p className="text-sm text-ink-tertiary">Loading…</p>}

      {rows !== null && (
        <div className="overflow-hidden border border-border bg-panel">
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="border-b border-border text-xs text-ink-tertiary">
                <th className="px-4 py-2 font-normal">Service</th>
                <th className="px-4 py-2 font-normal">Release</th>
                <th className="px-4 py-2 font-normal">Status</th>
                <th className="px-4 py-2 font-normal">Verdict</th>
                <th className="px-4 py-2 font-normal">Blockers</th>
              </tr>
            </thead>
            <tbody>
              {rows.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-8 text-center text-sm text-ink-tertiary">
                    No services yet.
                  </td>
                </tr>
              )}
              {rows.map(({ service, release, preflight }) => (
                <tr key={service.serviceId} className="border-b border-border last:border-b-0 align-top">
                  <td className="px-4 py-3">
                    <Link href={`/services`} className="text-ink-primary hover:underline">
                      {service.name}
                    </Link>
                  </td>
                  <td className="px-4 py-3">
                    {release ? (
                      <Link href={`/releases/${release.releaseId}`} className="text-xs text-accent hover:underline">
                        <span className="font-mono">{release.previousVersionLabel} → {release.candidateVersionLabel}</span>
                        <span className="ml-2 text-ink-tertiary">{release.state}</span>
                      </Link>
                    ) : (
                      <span className="text-xs text-ink-tertiary">no release</span>
                    )}
                  </td>
                  <td className="px-4 py-3 font-mono text-xs text-ink-secondary">
                    {preflight?.status ?? '—'}
                  </td>
                  <td className={`px-4 py-3 font-mono text-xs ${preflight ? VERDICT_STYLE[preflight.verdict] : 'text-ink-tertiary'}`}>
                    {preflight?.verdict ?? '—'}
                  </td>
                  <td className="px-4 py-3">
                    {preflight && preflight.checks.some((check) => !check.passed) ? (
                      <ul className="flex flex-col gap-0.5">
                        {preflight.checks.filter((check) => !check.passed).map((check) => (
                          <li key={`${check.name}:${check.blockerCode}`} className="text-[11px] text-ink-tertiary">
                            <span className="font-mono text-status-fail">{check.blockerCode}</span>{' '}
                            {check.blockerDescription}
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <span className="text-[11px] text-ink-tertiary">
                        {preflight ? 'none' : '—'}
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
