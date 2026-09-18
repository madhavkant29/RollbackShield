'use client';

import { useState } from 'react';
import { api, ApiError } from '@/lib/api';

interface ContractFormProps {
  releaseId: string;
  onActivated: () => void;
}

/**
 * Create+activate a rollback contract. This is the step that moves a
 * READY release to PROTECTED_ROLLOUT -- in v0.1 creation and activation
 * are atomic (POST /releases/{id}/contracts); the draft/review workflow
 * is deliberately deferred to v0.2 (ROADMAP.md).
 *
 * One ENUM_ALLOWED_VALUES rule is exposed here because that is the rule
 * the demo uses; the backend supports all five types via the same flat
 * RuleDto, but a full rule editor is not v0.1 scope.
 */
export function ContractForm({ releaseId, onActivated }: ContractFormProps) {
  const [rollbackWindowSeconds, setRollbackWindowSeconds] = useState('7200');
  const [candidateEpochRequiredForAsyncWork, setCandidateEpochRequired] = useState(true);
  const [entity, setEntity] = useState('Order');
  const [field, setField] = useState('status');
  const [supports, setSupports] = useState('CREATED,PAID,CANCELLED,REFUNDED');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await api.createContract(releaseId, {
        rollbackWindowSeconds: Number(rollbackWindowSeconds),
        candidateEpochRequiredForAsyncWork,
        rules: [
          {
            type: 'ENUM_ALLOWED_VALUES',
            entity: entity.trim(),
            field: field.trim(),
            previousVersionSupports: supports
              .split(',')
              .map((value) => value.trim())
              .filter(Boolean),
          },
        ],
      });
      onActivated();
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : 'Failed to activate contract');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="mb-6 overflow-hidden rounded border border-border bg-panel">
      <div className="border-b border-border px-4 py-3">
        <h2 className="text-sm text-ink-primary">Activate rollback contract</h2>
        <p className="mt-1 text-xs text-ink-tertiary">
          This moves the release from READY to PROTECTED_ROLLOUT. The SDK fetches this policy and
          enforces it locally from then on.
        </p>
      </div>

      <div className="flex flex-wrap items-end gap-4 px-4 py-4">
        <div>
          <label className="mb-1 block text-xs text-ink-tertiary">Rollback window (seconds)</label>
          <input
            value={rollbackWindowSeconds}
            onChange={(e) => setRollbackWindowSeconds(e.target.value)}
            inputMode="numeric"
            className="w-40 rounded-sm border border-border bg-base px-3 py-2 text-sm text-ink-primary focus:border-accent"
          />
        </div>
        <div>
          <label className="mb-1 block text-xs text-ink-tertiary">Entity</label>
          <input
            value={entity}
            onChange={(e) => setEntity(e.target.value)}
            className="w-36 rounded-sm border border-border bg-base px-3 py-2 text-sm text-ink-primary focus:border-accent"
          />
        </div>
        <div>
          <label className="mb-1 block text-xs text-ink-tertiary">Field</label>
          <input
            value={field}
            onChange={(e) => setField(e.target.value)}
            className="w-36 rounded-sm border border-border bg-base px-3 py-2 text-sm text-ink-primary focus:border-accent"
          />
        </div>
        <div className="min-w-64 flex-1">
          <label className="mb-1 block text-xs text-ink-tertiary">
            Previous version supports (comma-separated)
          </label>
          <input
            value={supports}
            onChange={(e) => setSupports(e.target.value)}
            className="w-full rounded-sm border border-border bg-base px-3 py-2 text-sm text-ink-primary focus:border-accent"
          />
        </div>
        <label className="flex items-center gap-2 pb-2 text-xs text-ink-secondary">
          <input
            type="checkbox"
            checked={candidateEpochRequiredForAsyncWork}
            onChange={(e) => setCandidateEpochRequired(e.target.checked)}
          />
          Require epoch on async work
        </label>
        <button
          type="submit"
          disabled={submitting}
          className="rounded-sm border border-border-strong px-4 py-2 text-sm text-ink-primary hover:bg-panel-alt disabled:opacity-40"
        >
          {submitting ? 'Activating…' : 'Activate contract'}
        </button>
      </div>

      {error && <p className="px-4 pb-4 text-sm text-status-fail">{error}</p>}
    </form>
  );
}
