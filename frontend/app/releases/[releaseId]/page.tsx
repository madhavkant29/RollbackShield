'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  api,
  ApiError,
  type AuditEvent,
  type DeploymentObservation,
  type Release,
  type ReversibilityReport,
} from '@/lib/api';
import { StatusBadge } from '@/components/StatusBadge';
import { CheckRow } from '@/components/CheckRow';
import { RollbackDialog } from '@/components/RollbackDialog';
import { ContractForm } from '@/components/ContractForm';

interface PageProps {
  params: { releaseId: string };
}

const VERDICT_STYLE: Record<string, string> = {
  CAN_ROLLBACK: 'text-status-pass',
  CANNOT_ROLLBACK: 'text-status-fail',
  UNKNOWN: 'text-status-warn',
};

/** The product's fixed reversibility dimensions, in control-room order. */
const DIMENSIONS: Array<{ label: string; checks: string[] }> = [
  { label: 'COMPUTE', checks: ['Compute restore path', 'Rollback execution'] },
  { label: 'ARTIFACT', checks: ['Rollback artifact availability'] },
  { label: 'DATABASE', checks: ['Database compatibility'] },
  { label: 'ASYNC WORK', checks: ['Queued work fencing'] },
  { label: 'POLICY', checks: ['Data compatibility', 'Policy freshness'] },
  { label: 'PREVIOUS VERSION HEALTH', checks: ['Runtime health observability'] },
];

/**
 * The release control room: "can this system safely return to the previous
 * release right now?", with the evidence path for every claim. Dense
 * operational view, no KPI cards.
 */
export default function ReleaseControlRoom({ params }: PageProps) {
  const { releaseId } = params;
  const [release, setRelease] = useState<Release | null>(null);
  const [reversibility, setReversibility] = useState<ReversibilityReport | null>(null);
  const [audit, setAudit] = useState<AuditEvent[]>([]);
  const [observation, setObservation] = useState<DeploymentObservation | null>(null);
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
      api.latestObservation(releaseData.serviceId)
        .then((latest) => setObservation(latest.releaseId === releaseId ? latest : null))
        .catch(() => setObservation(null));
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
      await refresh();
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

  const rollbackUnsupported = reversibility.checks.some((check) =>
    !check.passed && check.blockerCode === 'ROLLBACK_EXECUTION_UNSUPPORTED');
  const canRollback = (release.state === 'PROTECTED_ROLLOUT' || release.state === 'AT_RISK')
    && !rollbackUnsupported;
  const canCommit = release.state === 'PROTECTED_ROLLOUT' || release.state === 'AT_RISK';
  const canActivateContract = release.state === 'READY';
  const blockers = reversibility.checks.filter((check) => !check.passed);

  return (
    <div className="mx-auto max-w-5xl px-4 py-6 md:px-8 md:py-8">
      <div className="mb-1 font-mono text-xs uppercase tracking-wide text-ink-tertiary">
        {release.serviceId}
      </div>
      <h1 className="mb-4 text-2xl text-ink-primary">
        {release.previousVersionLabel} <span className="text-ink-tertiary">→</span>{' '}
        {release.candidateVersionLabel}
      </h1>

      <div className="mb-6 flex flex-wrap items-center gap-6">
        <StatusBadge status={reversibility.status} />
        <span className={`font-mono text-sm ${VERDICT_STYLE[reversibility.verdict] ?? 'text-ink-secondary'}`}>
          {reversibility.verdict}
        </span>
        <span className="text-sm text-ink-secondary">
          state: <span className="font-mono text-ink-primary">{release.state}</span>
        </span>
        <span className="text-sm text-ink-secondary">
          epoch: <span className="font-mono text-ink-primary">{release.epoch}</span>
        </span>
        <span className="text-xs text-ink-tertiary">
          evaluated {new Date(reversibility.evaluatedAt).toLocaleTimeString()}
        </span>
      </div>

      {error && <p className="mb-4 text-sm text-status-fail">{error}</p>}

      <div className="grid gap-6 lg:grid-cols-2">
        <div>
          <h2 className="mb-2 text-sm text-ink-secondary">Checks</h2>
          <div className="overflow-hidden border border-border bg-panel">
            {DIMENSIONS.map((dimension) => {
              const dimensionChecks = reversibility.checks.filter((check) =>
                dimension.checks.includes(check.name));
              return (
                <div key={dimension.label}>
                  <div className="border-b border-border bg-panel-alt px-4 py-1.5 font-mono text-[11px] tracking-wide text-ink-secondary">
                    {dimension.label}
                  </div>
                  {dimensionChecks.length === 0 && (
                    <div className="border-b border-border px-4 py-2 text-xs text-ink-tertiary">
                      no evidence evaluated for this dimension
                    </div>
                  )}
                  {dimensionChecks.map((check) => (
                    <CheckRow key={check.name} check={check} />
                  ))}
                </div>
              );
            })}
          </div>

          {blockers.length > 0 && (
            <div className="mt-3 border border-status-fail/40 bg-panel p-3">
              <div className="mb-1 font-mono text-xs text-status-fail">BLOCKERS</div>
              <ul className="flex flex-col gap-1">
                {blockers.map((blocker) => (
                  <li key={`${blocker.name}:${blocker.blockerCode}`} className="text-xs text-ink-secondary">
                    <span className="font-mono text-status-fail">{blocker.blockerCode}</span>{' '}
                    {blocker.blockerDescription}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>

        <div>
          <h2 className="mb-2 text-sm text-ink-secondary">Observation</h2>
          <div className="border border-border bg-panel p-3 text-xs">
            {observation ? (
              <dl className="grid grid-cols-[9rem_1fr] gap-y-1">
                <dt className="text-ink-tertiary">runtime</dt>
                <dd className="font-mono text-ink-primary">{observation.runtimeName}</dd>
                <dt className="text-ink-tertiary">candidate revision</dt>
                <dd className="font-mono text-ink-primary">{observation.candidateRevision}</dd>
                <dt className="text-ink-tertiary">previous revision</dt>
                <dd className="font-mono text-ink-primary">{observation.previousRevision ?? '—'}</dd>
                <dt className="text-ink-tertiary">candidate digest</dt>
                <dd className="font-mono text-ink-primary">{observation.candidateArtifactDigest ?? 'tag only'}</dd>
                <dt className="text-ink-tertiary">previous digest</dt>
                <dd className="font-mono text-ink-primary">{observation.previousArtifactDigest ?? 'tag only'}</dd>
                <dt className="text-ink-tertiary">commit</dt>
                <dd className="font-mono text-ink-primary">{observation.commitSha ?? 'unknown'}</dd>
                <dt className="text-ink-tertiary">artifact repository</dt>
                <dd className="font-mono text-ink-primary">{observation.artifactRepository ?? '—'}</dd>
                <dt className="text-ink-tertiary">observed at</dt>
                <dd className="font-mono text-ink-primary">
                  {new Date(observation.observedAt).toLocaleString()}
                </dd>
              </dl>
            ) : (
              <p className="text-ink-tertiary">
                This release was not created from a deployment observation.
              </p>
            )}
          </div>

          {reversibility.evidence.length > 0 && (
            <>
              <h2 className="mb-2 mt-5 text-sm text-ink-secondary">Evidence path</h2>
              <div className="overflow-hidden border border-border bg-panel">
                <table className="w-full text-left text-xs">
                  <tbody>
                    {reversibility.evidence.map((edge, index) => (
                      <tr key={`${edge.subject}:${edge.relation}:${index}`}
                          className="border-b border-border last:border-b-0">
                        <td className="px-3 py-2 font-mono text-ink-primary">{edge.subject}</td>
                        <td className="px-2 py-2 text-ink-tertiary">—{edge.relation}→</td>
                        <td className="px-3 py-2 font-mono text-ink-primary">{edge.object}</td>
                        <td className="px-3 py-2 text-ink-tertiary">{edge.source}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </div>
      </div>

      {canActivateContract && (
        <div className="mt-6">
          <ContractForm releaseId={releaseId} onActivated={refresh} />
        </div>
      )}

      <div className="mb-10 mt-6 flex flex-wrap gap-3">
        <button
          onClick={() => setRollbackDialogOpen(true)}
          disabled={!canRollback || pending !== null}
          title={rollbackUnsupported
            ? 'The mapped runtime connector cannot execute rollbacks in this build'
            : undefined}
          className="rounded-sm border border-status-fail/50 px-4 py-2 text-sm text-status-fail hover:bg-status-fail/10 disabled:cursor-not-allowed disabled:opacity-40"
        >
          {pending === 'rollback' ? 'Rolling back…'
            : rollbackUnsupported ? 'Rollback unsupported for this runtime' : 'Roll back'}
        </button>
        <button
          onClick={handleCommit}
          disabled={!canCommit || pending !== null}
          className="rounded-sm border border-border-strong px-4 py-2 text-sm text-ink-primary hover:bg-panel-alt disabled:cursor-not-allowed disabled:opacity-40"
        >
          {pending === 'commit' ? 'Committing…' : 'Commit release'}
        </button>
      </div>

      <h2 className="mb-3 text-sm text-ink-secondary">Timeline</h2>
      <div className="overflow-hidden border border-border bg-panel">
        <table className="w-full text-left text-sm">
          <thead>
            <tr className="border-b border-border text-xs text-ink-tertiary">
              <th className="px-4 py-2 font-normal">Time</th>
              <th className="px-4 py-2 font-normal">Action</th>
              <th className="px-4 py-2 font-normal">Transition</th>
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
            {[...audit].reverse().map((event) => (
              <tr key={event.eventId} className="border-b border-border last:border-b-0 hover:bg-panel-alt">
                <td className="px-4 py-2 font-mono text-xs text-ink-tertiary">
                  {new Date(event.timestamp).toLocaleTimeString()}
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

      <RollbackDialog
        open={rollbackDialogOpen}
        onCancel={() => setRollbackDialogOpen(false)}
        onConfirm={handleRollback}
      />
    </div>
  );
}
