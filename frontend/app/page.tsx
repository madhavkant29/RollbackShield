'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { api, ApiError, type ServiceSummary } from '@/lib/api';

export default function OverviewPage() {
  const [services, setServices] = useState<ServiceSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.listServices().then(setServices).catch((e) =>
      setError(e instanceof ApiError ? e.message : 'Could not reach the control plane'),
    );
  }, []);

  return (
    <div className="mx-auto max-w-4xl px-4 py-6 md:px-8 md:py-8">
      <h1 className="mb-1 text-2xl text-ink-primary">Overview</h1>
      <p className="mb-8 text-sm text-ink-secondary">
        Deployment reversibility across your services.
      </p>

      {error && (
        <p className="mb-6 text-sm text-status-fail">
          {error} — is the backend running at{' '}
          <code className="font-mono">http://localhost:8080</code>?
        </p>
      )}

      {services && services.length === 0 && !error && (
        <p className="text-sm text-ink-tertiary">
          No services yet. Create one from the <Link href="/services" className="text-accent underline">Services</Link> page.
        </p>
      )}

      {services && services.length > 0 && (
        <ul className="divide-y divide-border overflow-hidden rounded border border-border bg-panel">
          {services.map((service) => (
            <li key={service.serviceId}>
              <Link
                href={`/releases?serviceId=${service.serviceId}`}
                className="flex items-center justify-between px-4 py-3 text-sm text-ink-primary hover:bg-panel-alt"
              >
                <span>{service.name}</span>
                <span className="font-mono text-xs text-ink-tertiary">{service.serviceId}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
