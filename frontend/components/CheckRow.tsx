import type { ReversibilityCheck } from '@/lib/api';

export function CheckRow({ check }: { check: ReversibilityCheck }) {
  return (
    <div className="flex items-start justify-between border-b border-border px-4 py-3 last:border-b-0">
      <div>
        <div className="text-sm text-ink-primary">{check.name}</div>
        {!check.passed && check.blockerDescription && (
          <div className="mt-0.5 text-xs text-ink-tertiary">{check.blockerDescription}</div>
        )}
      </div>
      <span
        className={`font-mono text-xs ${check.passed ? 'text-status-pass' : 'text-status-fail'}`}
      >
        {check.passed ? 'PASS' : `FAIL · ${check.blockerCode}`}
      </span>
    </div>
  );
}
