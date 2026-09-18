import type { ReversibilityStatus } from '@/lib/api';

const STYLES: Record<ReversibilityStatus, { label: string; dot: string; text: string }> = {
  REVERSIBLE: { label: 'Reversible', dot: 'bg-status-pass', text: 'text-status-pass' },
  AT_RISK: { label: 'At risk', dot: 'bg-status-warn', text: 'text-status-warn' },
  COMMITTED: { label: 'Committed', dot: 'bg-ink-secondary', text: 'text-ink-secondary' },
  UNKNOWN: { label: 'Unknown', dot: 'bg-ink-tertiary', text: 'text-ink-tertiary' },
};

export function StatusBadge({ status }: { status: ReversibilityStatus }) {
  const s = STYLES[status];
  return (
    <span className="inline-flex items-center gap-2 font-mono text-sm">
      <span className={`h-2 w-2 rounded-full ${s.dot}`} aria-hidden />
      <span className={s.text}>{s.label}</span>
    </span>
  );
}
