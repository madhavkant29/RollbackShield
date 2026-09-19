'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { api, ApiError, type AuditEvent, type Release, type ServiceSummary } from '@/lib/api';

interface AuditRow extends AuditEvent {
  serviceName: string;
  releaseId: string;
}

const MAX_ROWS = 200;

/**
 * Organization audit view: the real per-release audit trails, aggregated
 * across every service's latest release. There is no global audit store in
 * the backend, so this reads what exists rather than pretending otherwise.
 */
export default function AuditPage() {
  const [rows, setRows] = useState<AuditRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    async function load() {
      try {
        const services = await api.listServices();
        const collected: AuditRow[] = [];
        for (const service of services) {
          const releases: Release[] = await api.listReleases(service.serviceId).catch(() => []);
          const latest = releases[0];
          if (!latest) {
            continue;
          }
          const events = await api.getAuditTrail(latest.releaseId).catch(() => [] as AuditEvent[]);
          for (const event of events) {
            collected.push({ ...event, serviceName: service.name, releaseId: latest.releaseId });
          }
        }
        collected.sort((left, right) =>
          new Date(right.timestamp).getTime() - new Date(left.timestamp).getTime());
        setRows(collected.slice(0, MAX_ROWS));
      } catch (e) {
        setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Control plane unreachable');
      }
    }
    void load();
  }, []);

  return (
    <div className="mx-auto max-w-5xl px-4 py-6 md:px-8 md:py-8">
      <h1 className="mb-1 text-2xl text-ink-primary">Audit</h1>
      <p className="mb-6 text-sm text-ink-secondary">
        Append-only trails for the latest release of every service. Actions cannot be edited or
        deleted anywhere in the product.
      </p>

      {error && <p className="mb-4 text-sm text-status-fail">{error}</p>}
      {rows === null && !error && <p className="text-sm text-ink-tertiary">Loading…</p>}
      {rows !== null && rows.length === 0 && (
        <p className="text-sm text-ink-tertiary">No audit events recorded yet.</p>
      )}

      {rows !== null && rows.length > 0 && (
        <div className="overflow-hidden border border-border bg-panel">
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="border-b border-border text-xs text-ink-tertiary">
                <th className="px-4 py-2 font-normal">Time</th>
                <th className="px-4 py-2 font-normal">Service</th>
                <th className="px-4 py-2 font-normal">Action</th>
                <th className="px-4 py-2 font-normal">Transition</th>
                <th className="px-4 py-2 font-normal">Reason</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((event) => (
                <tr key={`${event.eventId}`} className="border-b border-border last:border-b-0 hover:bg-panel-alt">
                  <td className="px-4 py-2 font-mono text-xs text-ink-tertiary">
                    {new Date(event.timestamp).toLocaleString()}
                  </td>
                  <td className="px-4 py-2 text-xs text-ink-primary">
                    <Link href={`/releases/${event.releaseId}`} className="hover:underline">
                      {event.serviceName}
                    </Link>
                  </td>
                  <td className="px-4 py-2 font-mono text-xs text-ink-primary">{event.action}</td>
                  <td className="px-4 py-2 font-mono text-xs text-ink-secondary">
                    {event.previousState || event.newState
                      ? `${event.previousState ?? '—'} → ${event.newState ?? '—'}`
                      : '—'}
                  </td>
                  <td className="px-4 py-2 text-xs text-ink-tertiary">{event.reason ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
