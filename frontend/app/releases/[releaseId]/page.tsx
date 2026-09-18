'use client';

import { useCallback, useEffect, useState } from 'react';
import { api, ApiError, type AuditEvent, type Release, type ReversibilityReport } from '@/lib/api';
import { StatusBadge } from '@/components/StatusBadge';
import { CheckRow } from '@/components/CheckRow';
import { RollbackDialog } from '@/components/RollbackDialog';
import { ContractForm } from '@/components/ContractForm';

interface PageProps {
  params: { releaseId: string };
}

/**
 * The release control room (§26 of the product brief): the single page
 * that answers "can this release safely go back right now," with the
 * evidence that backs the answer directly below it -- not a KPI-card
 * dashboard, a dense operational view.
 */
export default function ReleaseControlRoom({ params }: PageProps) {
  const { releaseId } = params;
  const [release, setRelease] = useState<Release | null>(null);
  const [reversibility, setReversibility] = useState<ReversibilityReport | null>(null);
  const [audit, setAudit] = useState<AuditEvent[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState<'rollback' | 'commit' | null>(null);
  const [rollbackDialogOpen, setRollbackDialogOpen] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const [releaseData, reversibilityData, auditData] = await Promise.all([
        api.getRelease(releaseId),
        api.getReversibility(releaseId),
        api.getAuditTrail(releaseId),
      ]);
      setRelease(releaseData);
      setReversibility(reversibilityData);
      setAudit(auditData);
      setError(null);
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Failed to reach the control plane');
    }
  }, [releaseId]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  async function handleRollback(reason: string) {
    setRollbackDialogOpen(false);
    setPending('rollback');
    try {
      await api.rollbackRelease(releaseId, reason);
      await refresh();
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Rollback failed');
    } finally {
      setPending(null);
    }
  }

  async function handleCommit() {
    setPending('commit');
    try {
      await api.commitRelease(releaseId);
      await refresh();
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Commit failed');
    } finally {
      setPending(null);
    }
  }

  if (error && !release) {
    return (
      <div className="p-8">
        <p className="text-sm text-status-fail">{error}</p>
        <p className="mt-2 text-xs text-ink-tertiary">
          Is the backend running? <code className="font-mono">mvn spring-boot:run</code> in{' '}
          <code className="font-mono">backend/</code>.
        </p>
      </div>
    );
  }

  if (!release || !reversibility) {
    return <div className="p-8 text-sm text-ink-secondary">Loading…</div>;
  }

  const canRollback = release.state === 'PROTECTED_ROLLOUT' || release.state === 'AT_RISK';
  const canCommit = canRollback;
  const canActivateContract = release.state === 'READY';

  return (
    <div className="mx-auto max-w-4xl px-4 py-6 md:px-8 md:py-8">
      <div className="mb-1 font-mono text-xs uppercase tracking-wide text-ink-tertiary">
        {release.serviceId}
      </div>
      <h1 className="mb-4 text-2xl text-ink-primary">
        {release.previousVersionLabel} <span className="text-ink-tertiary">→</span>{' '}
        {release.candidateVersionLabel}
      </h1>

      <div className="mb-6 flex items-center gap-6">
        <StatusBadge status={reversibility.status} />
        <span className="text-sm text-ink-secondary">
          state: <span className="font-mono text-ink-primary">{release.state}</span>
        </span>
        <span className="text-sm text-ink-secondary">
          epoch: <span className="font-mono text-ink-primary">{release.epoch}</span>
        </span>
      </div>

      {error && <p className="mb-4 text-sm text-status-fail">{error}</p>}

      <div className="mb-6 overflow-hidden rounded border border-border bg-panel">
        {reversibility.checks.map((check) => (
          <CheckRow key={check.name} check={check} />
        ))}
      </div>

      {canActivateContract && <ContractForm releaseId={releaseId} onActivated={refresh} />}

      <div className="mb-10 flex flex-wrap gap-3">
        <button
          onClick={() => setRollbackDialogOpen(true)}
          disabled={!canRollback || pending !== null}
          className="rounded-sm border border-status-fail/50 px-4 py-2 text-sm text-status-fail hover:bg-status-fail/10 disabled:cursor-not-allowed disabled:opacity-40"
        >
          {pending === 'rollback' ? 'Rolling back…' : 'Roll back'}
        </button>
        <button
          onClick={handleCommit}
          disabled={!canCommit || pending !== null}
          className="rounded-sm border border-border-strong px-4 py-2 text-sm text-ink-primary hover:bg-panel-alt disabled:cursor-not-allowed disabled:opacity-40"
        >
          {pending === 'commit' ? 'Committing…' : 'Commit release'}
        </button>
      </div>

      <h2 className="mb-3 text-sm text-ink-secondary">Audit trail</h2>
      <div className="overflow-hidden rounded border border-border bg-panel">
        <table className="w-full text-left text-sm">
          <thead>
            <tr className="border-b border-border text-xs text-ink-tertiary">
              <th className="px-4 py-2 font-normal">Time</th>
              <th className="px-4 py-2 font-normal">Action</th>
              <th className="px-4 py-2 font-normal">Target</th>
              <th className="px-4 py-2 font-normal">Reason</th>
            </tr>
          </thead>
          <tbody>
            {audit.length === 0 && (
              <tr>
                <td colSpan={4} className="px-4 py-6 text-center text-ink-tertiary">
                  No audit events yet
                </td>
              </tr>
            )}
            {audit.map((event) => (
              <tr key={event.eventId} className="border-b border-border last:border-b-0 hover:bg-panel-alt">
                <td className="px-4 py-2 font-mono text-xs text-ink-tertiary">
                  {new Date(event.timestamp).toLocaleTimeString()}
                </td>
                <td className="px-4 py-2 font-mono text-xs text-ink-primary">{event.action}</td>
                <td className="px-4 py-2 text-xs text-ink-secondary">{event.target}</td>
                <td className="px-4 py-2 text-xs text-ink-tertiary">{event.reason ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <RollbackDialog
        open={rollbackDialogOpen}
        onCancel={() => setRollbackDialogOpen(false)}
        onConfirm={handleRollback}
      />
    </div>
  );
}
