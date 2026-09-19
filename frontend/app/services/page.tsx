'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import {
  api,
  ApiError,
  type BindingResponse,
  type DeploymentObservation,
  type MappingResponse,
  type Release,
  type ServiceSummary,
} from '@/lib/api';

/**
 * Services are imported from connected systems, not invented. This page
 * shows what each service is actually made of (bindings with confidence and
 * evidence), and drives the observe -> release flow from real runtime
 * observations.
 */
export default function ServicesPage() {
  const [services, setServices] = useState<ServiceSummary[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [mappings, setMappings] = useState<Record<string, MappingResponse>>({});
  const [activeReleases, setActiveReleases] = useState<Record<string, { state: string; verdict: string | null }>>({});
  const [observation, setObservation] = useState<DeploymentObservation | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [name, setName] = useState('');

  function load() {
    api.listServices()
      .then(setServices)
      .catch((e) => setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Control plane unreachable'));
  }

  useEffect(load, []);

  async function show(service: ServiceSummary) {
    if (selected === service.serviceId) {
      setSelected(null);
      setObservation(null);
      return;
    }
    setSelected(service.serviceId);
    setObservation(null);
    try {
      const [mapping, observation, releases] = await Promise.all([
        api.getMapping(service.serviceId),
        api.latestObservation(service.serviceId).catch(() => null),
        api.listReleases(service.serviceId).catch(() => [] as Release[]),
      ]);
      setMappings((current) => ({ ...current, [service.serviceId]: mapping }));
      setObservation(observation);
      const active = releases.find((release) =>
        ['PROTECTED_ROLLOUT', 'AT_RISK', 'ROLLING_BACK', 'READY'].includes(release.state))
        ?? releases[0] ?? null;
      if (active) {
        const report = await api.getReversibility(active.releaseId).catch(() => null);
        setActiveReleases((current) => ({
          ...current,
          [service.serviceId]: { state: active.state, verdict: report?.verdict ?? null },
        }));
      }
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Failed to load mapping');
    }
  }

  async function observe(service: ServiceSummary) {
    setBusy(`observe:${service.serviceId}`);
    setError(null);
    try {
      const result = await api.observeService(service.serviceId);
      setObservation(result);
      setNotice(result.releaseCreated
        ? `Observed ${result.candidateRevision} — release created automatically (READY), preflight evaluated`
        : `Observed ${result.candidateRevision} (previous ${result.previousRevision ?? 'none'}) — existing release reused`);
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Observation failed');
    } finally {
      setBusy(null);
    }
  }

  async function createRelease(service: ServiceSummary) {
    setBusy(`release:${service.serviceId}`);
    try {
      const result = await api.createReleaseFromObservation(service.serviceId);
      setNotice(`Release ${result.releaseId}: ${result.previousVersionLabel} → ${result.candidateVersionLabel}`);
      load();
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Release creation failed');
    } finally {
      setBusy(null);
    }
  }

  async function confirmBinding(service: ServiceSummary, binding: BindingResponse) {
    setBusy(`confirm:${service.serviceId}`);
    setError(null);
    try {
      const mapping = await api.addBinding(service.serviceId, {
        role: binding.role,
        integrationId: binding.integrationId,
        externalId: binding.externalId,
        evidence: 'confirmed by operator',
      });
      setMappings((current) => ({ ...current, [service.serviceId]: mapping }));
      setNotice(`Confirmed ${binding.role} → ${binding.externalId}`);
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Confirmation failed');
    } finally {
      setBusy(null);
    }
  }

  async function createManually(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    try {
      await api.createService(name.trim());
      setName('');
      load();
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : 'Failed to create service');
    }
  }

  return (
    <div className="mx-auto max-w-4xl px-4 py-6 md:px-8 md:py-8">
      <h1 className="mb-1 text-2xl text-ink-primary">Services</h1>
      <p className="mb-6 text-sm text-ink-secondary">
        Import services from a connected system on the{' '}
        <Link href="/integrations" className="text-accent underline">Integrations</Link> page.
        Manual creation remains for local development and unsupported systems.
      </p>

      {error && <p className="mb-4 text-sm text-status-fail">{error}</p>}
      {notice && <p className="mb-4 text-sm text-accent">{notice}</p>}

      <div className="mb-6 overflow-hidden border border-border bg-panel">
        <table className="w-full text-left text-sm">
          <thead>
            <tr className="border-b border-border text-xs text-ink-tertiary">
              <th className="px-4 py-2 font-normal">Service</th>
              <th className="px-4 py-2 font-normal">Bindings</th>
              <th className="px-4 py-2 font-normal">Latest observation</th>
              <th className="px-4 py-2 font-normal">Active release</th>
              <th className="px-4 py-2 font-normal" />
            </tr>
          </thead>
          <tbody>
            {services.length === 0 && (
              <tr>
                <td colSpan={4} className="px-4 py-8 text-center text-sm text-ink-tertiary">
                  No services yet.
                </td>
              </tr>
            )}
            {services.map((service) => {
              const mapping = mappings[service.serviceId];
              const isSelected = selected === service.serviceId;
              return (
                <tr key={service.serviceId} className="border-b border-border last:border-b-0 align-top">
                  <td className="px-4 py-3">
                    <div className="text-ink-primary">{service.name}</div>
                    <div className="font-mono text-[11px] text-ink-tertiary">{service.serviceId}</div>
                  </td>
                  <td className="px-4 py-3">
                    {mapping ? (
                      mapping.bindings.length === 0 ? (
                        <span className="text-xs text-ink-tertiary">none</span>
                      ) : (
                        <div className="flex flex-col gap-1">
                          {mapping.bindings.map((binding) => (
                            <div key={`${binding.role}:${binding.externalId}`} className="text-xs">
                              <span className="font-mono text-ink-secondary">{binding.role}</span>{' '}
                              <span className="text-ink-primary">{binding.externalId}</span>{' '}
                              <span className={`font-mono text-[11px] ${
                                binding.confidence === 'HIGH' ? 'text-status-pass' : 'text-status-warn'
                              }`}>
                                {binding.confidence}
                              </span>
                              <div className="text-[11px] text-ink-tertiary">{binding.evidence}</div>
                              {binding.confidence !== 'HIGH' && (
                                <button
                                  onClick={() => confirmBinding(service, binding)}
                                  disabled={busy !== null}
                                  className="mt-0.5 text-[11px] text-accent hover:underline disabled:opacity-40"
                                >
                                  {busy === `confirm:${service.serviceId}`
                                    ? 'Confirming…' : 'Confirm mapping'}
                                </button>
                              )}
                            </div>
                          ))}
                        </div>
                      )
                    ) : (
                      <span className="text-xs text-ink-tertiary">open to load</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-xs">
                    {isSelected && observation ? (
                      <>
                        <div className="font-mono text-ink-primary">{observation.candidateRevision}</div>
                        <div className="font-mono text-ink-tertiary">
                          previous: {observation.previousRevision ?? 'none'}
                        </div>
                        <div className="font-mono text-ink-tertiary">
                          commit: {observation.commitSha?.slice(0, 7) ?? 'unknown'}
                        </div>
                      </>
                    ) : (
                      <span className="text-ink-tertiary">—</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-xs">
                    {isSelected && activeReleases[service.serviceId] ? (
                      <>
                        <div className="font-mono text-ink-primary">
                          {activeReleases[service.serviceId].state}
                        </div>
                        <div className={`font-mono ${
                          activeReleases[service.serviceId].verdict === 'CAN_ROLLBACK'
                            ? 'text-status-pass'
                            : activeReleases[service.serviceId].verdict === 'CANNOT_ROLLBACK'
                              ? 'text-status-fail' : 'text-status-warn'
                        }`}>
                          {activeReleases[service.serviceId].verdict ?? 'no preflight'}
                        </div>
                      </>
                    ) : (
                      <span className="text-ink-tertiary">—</span>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex flex-col items-start gap-2">
                      <button onClick={() => show(service)} className="text-xs text-accent hover:underline">
                        {isSelected ? 'Hide mapping' : 'Open'}
                      </button>
                      {isSelected && (
                        <>
                          <Link href={`/releases?serviceId=${service.serviceId}`}
                                className="text-xs text-accent hover:underline">
                            Releases →
                          </Link>
                          <button
                            onClick={() => observe(service)}
                            disabled={busy !== null}
                            className="text-xs text-accent hover:underline disabled:opacity-40"
                          >
                            {busy === `observe:${service.serviceId}` ? 'Observing…' : 'Observe deployment'}
                          </button>
                          <button
                            onClick={() => createRelease(service)}
                            disabled={busy !== null || !observation}
                            className="text-xs text-accent hover:underline disabled:opacity-40"
                          >
                            Create release from observation
                          </button>
                          {observation?.releaseId && (
                            <Link href={`/releases/${observation.releaseId}`}
                                  className="text-xs text-accent hover:underline">
                              Open release →
                            </Link>
                          )}
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <form onSubmit={createManually} className="flex gap-2">
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Manual service name (local dev only)"
          className="flex-1 rounded-sm border border-border bg-panel px-3 py-2 text-sm text-ink-primary placeholder:text-ink-tertiary focus:border-accent"
        />
        <button
          type="submit"
          className="rounded-sm border border-border px-4 py-2 text-sm text-ink-secondary hover:bg-panel-alt"
        >
          Create manually
        </button>
      </form>
    </div>
  );
}
