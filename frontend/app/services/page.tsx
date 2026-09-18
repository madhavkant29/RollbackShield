'use client';

import { useEffect, useState } from 'react';
import { api, ApiError, type ServiceSummary } from '@/lib/api';

export default function ServicesPage() {
  const [services, setServices] = useState<ServiceSummary[]>([]);
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  function load() {
    api.listServices().then(setServices).catch((e) =>
      setError(e instanceof ApiError ? e.message : 'Could not reach the control plane'),
    );
  }

  useEffect(load, []);

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    setCreating(true);
    try {
      await api.createService(name.trim());
      setName('');
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to create service');
    } finally {
      setCreating(false);
    }
  }

  return (
    <div className="mx-auto max-w-2xl px-4 py-6 md:px-8 md:py-8">
      <h1 className="mb-6 text-2xl text-ink-primary">Services</h1>

      <form onSubmit={handleCreate} className="mb-8 flex gap-2">
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Service name, e.g. checkout"
          className="flex-1 rounded-sm border border-border bg-panel px-3 py-2 text-sm text-ink-primary placeholder:text-ink-tertiary focus:border-accent"
        />
        <button
          type="submit"
          disabled={creating}
          className="rounded-sm border border-border-strong px-4 py-2 text-sm text-ink-primary hover:bg-panel-alt disabled:opacity-40"
        >
          Add service
        </button>
      </form>

      {error && <p className="mb-4 text-sm text-status-fail">{error}</p>}

      <ul className="divide-y divide-border overflow-hidden rounded border border-border bg-panel">
        {services.length === 0 && (
          <li className="px-4 py-6 text-center text-sm text-ink-tertiary">No services yet</li>
        )}
        {services.map((service) => (
          <li key={service.serviceId} className="flex items-center justify-between px-4 py-3 text-sm">
            <span className="text-ink-primary">{service.name}</span>
            <span className="font-mono text-xs text-ink-tertiary">{service.serviceId}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
