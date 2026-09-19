'use client';

import { useState } from 'react';
import { api, ApiError, type DiscoveredResource, type ImportedService } from '@/lib/api';

const IMPORTABLE = new Set(['RUNTIME_SERVICE', 'KUBERNETES_DEPLOYMENT']);

/**
 * Resources a sync actually discovered for one integration. Importable
 * runtimes get an import action; everything else is evidence you can
 * inspect (queue attributes, digests, migration paths...). Nothing here is
 * fabricated: an empty table means the provider returned nothing.
 */
export function IntegrationResources({ integrationId,
                                       onImported }: {
  integrationId: string;
  onImported: (service: ImportedService) => void;
}) {
  const [resources, setResources] = useState<DiscoveredResource[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [importing, setImporting] = useState<string | null>(null);

  async function load() {
    try {
      setResources(await api.listIntegrationResources(integrationId));
      setError(null);
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Failed to load resources');
    }
  }

  async function importResource(resource: DiscoveredResource) {
    setImporting(resource.resourceId);
    try {
      const imported = await api.importService({
        integrationId,
        resourceExternalId: resource.externalId,
      });
      onImported(imported);
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Import failed');
    } finally {
      setImporting(null);
    }
  }

  if (resources === null) {
    return (
      <button onClick={load} className="text-xs text-accent hover:underline">
        Load discovered resources
      </button>
    );
  }

  return (
    <div>
      <div className="mb-2 flex items-center gap-3">
        <span className="text-xs text-ink-tertiary">
          {resources.length} discovered resource{resources.length === 1 ? '' : 's'}
        </span>
        <button onClick={load} className="text-xs text-accent hover:underline">
          Refresh
        </button>
      </div>
      {error && <p className="mb-2 text-xs text-status-fail">{error}</p>}
      <table className="w-full text-left text-sm">
        <thead>
          <tr className="border-b border-border text-xs text-ink-tertiary">
            <th className="py-1.5 pr-4 font-normal">Type</th>
            <th className="py-1.5 pr-4 font-normal">External id</th>
            <th className="py-1.5 pr-4 font-normal">Details</th>
            <th className="py-1.5 font-normal" />
          </tr>
        </thead>
        <tbody>
          {resources.length === 0 && (
            <tr>
              <td colSpan={4} className="py-4 text-center text-xs text-ink-tertiary">
                No resources discovered. Run a sync first.
              </td>
            </tr>
          )}
          {resources.map((resource) => (
            <tr key={resource.resourceId} className="border-b border-border last:border-b-0">
              <td className="py-2 pr-4 font-mono text-xs text-ink-secondary">
                {resource.resourceType}
              </td>
              <td className="py-2 pr-4 font-mono text-xs text-ink-primary">{resource.externalId}</td>
              <td className="max-w-md truncate py-2 pr-4 font-mono text-[11px] text-ink-tertiary"
                  title={JSON.stringify(resource.metadata)}>
                {Object.entries(resource.metadata)
                  .slice(0, 3)
                  .map(([key, value]) => `${key}=${value}`)
                  .join('  ')}
              </td>
              <td className="py-2 text-right">
                {IMPORTABLE.has(resource.resourceType) && (
                  <button
                    onClick={() => importResource(resource)}
                    disabled={importing !== null}
                    className="text-xs text-accent hover:underline disabled:opacity-40"
                  >
                    {importing === resource.resourceId ? 'Importing…' : 'Import as service'}
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
