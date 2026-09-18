'use client';

import { useEffect, useRef } from 'react';

interface RollbackDialogProps {
  open: boolean;
  onCancel: () => void;
  onConfirm: (reason: string) => void;
}

/**
 * Uses the native <dialog> element rather than a hand-rolled modal: real
 * focus trapping, Escape-to-close, and screen-reader semantics come from
 * the browser for free, and it was the window.prompt() call this replaces
 * that didn't fit the app's visual design -- not that prompt() itself was
 * inaccessible.
 */
export function RollbackDialog({ open, onCancel, onConfirm }: RollbackDialogProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const reasonRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open && !dialog.open) {
      dialog.showModal();
      reasonRef.current?.focus();
    } else if (!open && dialog.open) {
      dialog.close();
    }
  }, [open]);

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    const reason = reasonRef.current?.value.trim() || 'production issue discovered';
    onConfirm(reason);
  }

  return (
    <dialog
      ref={dialogRef}
      onCancel={onCancel}
      className="w-full max-w-md rounded border border-border-strong bg-panel p-0 text-ink-primary backdrop:bg-black/60"
    >
      <form onSubmit={handleSubmit} className="p-5">
        <h2 className="mb-1 text-base text-ink-primary">Roll back this release</h2>
        <p className="mb-4 text-sm text-ink-secondary">
          This invalidates the candidate epoch immediately. Queued work created under it will be
          cancelled, not executed.
        </p>
        <label htmlFor="rollback-reason" className="mb-1 block text-xs text-ink-tertiary">
          Reason
        </label>
        <textarea
          id="rollback-reason"
          ref={reasonRef}
          defaultValue="production issue discovered"
          rows={2}
          className="mb-4 w-full rounded-sm border border-border bg-base px-3 py-2 text-sm text-ink-primary focus:border-accent"
        />
        <div className="flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="rounded-sm border border-border px-3 py-1.5 text-sm text-ink-secondary hover:bg-panel-alt"
          >
            Cancel
          </button>
          <button
            type="submit"
            className="rounded-sm border border-status-fail/50 px-3 py-1.5 text-sm text-status-fail hover:bg-status-fail/10"
          >
            Roll back
          </button>
        </div>
      </form>
    </dialog>
  );
}
