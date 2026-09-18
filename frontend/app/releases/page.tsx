'use client';

import { Suspense, useEffect, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import Link from 'next/link';
import { api, ApiError, type Release } from '@/lib/api';

function ReleasesList() {
  const searchParams = useSearchParams();
  const serviceId = searchParams.get('serviceId') ?? '';

  const [releases, setReleases] = useState<Release[]>([]);
  const [previousVersionLabel, setPreviousVersionLabel] = useState('v1');
  const [candidateVersionLabel, setCandidateVersionLabel] = useState('v2');
  const [error, setError] = useState<string | null>(null);

  function load() {
    if (!serviceId) return;
    api.listReleases(serviceId).then(setReleases).catch((e) =>
      setError(e instanceof ApiError ? e.message : 'Could not reach the control plane'),
    );
  }

  useEffect(load, [serviceId]);

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    try {
      await api.createRelease({ serviceId, previousVersionLabel, candidateVersionLabel });
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to create release');
    }
  }

  async function advance(release: Release) {
    try {
      if (release.state === 'DRAFT') await api.prepareRelease(release.releaseId);
      else if (release.state === 'PREPARING') await api.markReleaseReady(release.releaseId);
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Transition failed');
    }
  }

  if (!serviceId) {
    return (
      <p className="text-sm text-ink-tertiary">
        Pick a service from the <Link href="/services" className="text-accent underline">Services</Link> page first.
      </p>
    );
  }

  return (
    <>
      <form onSubmit={handleCreate} className="mb-8 flex flex-wrap items-end gap-2">
        <div>
          <label className="mb-1 block text-xs text-ink-tertiary">Previous version</label>
          <input
            value={previousVersionLabel}
            onChange={(e) => setPreviousVersionLabel(e.target.value)}
            className="rounded-sm border border-border bg-panel px-3 py-2 text-sm text-ink-primary focus:border-accent"
          />
        </div>
        <div>
          <label className="mb-1 block text-xs text-ink-tertiary">Candidate version</label>
          <input
            value={candidateVersionLabel}
            onChange={(e) => setCandidateVersionLabel(e.target.value)}
            className="rounded-sm border border-border bg-panel px-3 py-2 text-sm text-ink-primary focus:border-accent"
          />
        </div>
        <button
          type="submit"
          className="rounded-sm border border-border-strong px-4 py-2 text-sm text-ink-primary hover:bg-panel-alt"
        >
          Create release
        </button>
      </form>

      {error && <p className="mb-4 text-sm text-status-fail">{error}</p>}

      <ul className="divide-y divide-border overflow-hidden rounded border border-border bg-panel">
        {releases.length === 0 && (
          <li className="px-4 py-6 text-center text-sm text-ink-tertiary">No releases yet</li>
        )}
        {releases.map((release) => (
          <li key={release.releaseId} className="flex items-center justify-between px-4 py-3 text-sm">
            <div>
              <span className="text-ink-primary">
                {release.previousVersionLabel} → {release.candidateVersionLabel}
              </span>
              <span className="ml-3 font-mono text-xs text-ink-tertiary">{release.state}</span>
            </div>
            <div className="flex items-center gap-3">
              {(release.state === 'DRAFT' || release.state === 'PREPARING') && (
                <button
                  onClick={() => advance(release)}
                  className="text-xs text-accent hover:underline"
                >
                  {release.state === 'DRAFT' ? 'Prepare' : 'Mark ready'}
                </button>
              )}
              <Link href={`/releases/${release.releaseId}`} className="text-xs text-accent hover:underline">
                Open control room →
              </Link>
            </div>
          </li>
        ))}
      </ul>
    </>
  );
}

export default function ReleasesPage() {
  return (
    <div className="mx-auto max-w-3xl px-4 py-6 md:px-8 md:py-8">
      <h1 className="mb-6 text-2xl text-ink-primary">Releases</h1>
      <Suspense fallback={<p className="text-sm text-ink-tertiary">Loading…</p>}>
        <ReleasesList />
      </Suspense>
    </div>
  );
}
