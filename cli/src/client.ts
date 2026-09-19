import { CliConfig } from './config.js';

export class ApiError extends Error {
  constructor(public readonly status: number, public readonly code: string, message: string) {
    super(message);
  }
}

export class ControlPlaneClient {
  constructor(private readonly config: CliConfig) {}

  private headers(): Record<string, string> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      // Tell the server to close the connection after each response so the
      // process can exit cleanly without a forced process.exit() racing
      // undici's socket teardown (which crashes on some Node/Windows builds).
      Connection: 'close',
    };
    if (this.config.token) {
      headers.Authorization = `Bearer ${this.config.token}`;
    }
    return headers;
  }

  async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const response = await fetch(`${this.config.apiUrl}${path}`, {
      method,
      headers: this.headers(),
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok) {
      const payload = (await response.json().catch(() => ({}))) as { code?: string; message?: string };
      throw new ApiError(response.status, payload.code ?? 'UNKNOWN_ERROR',
        payload.message ?? `${method} ${path} failed with HTTP ${response.status}`);
    }
    if (response.status === 204) {
      return undefined as T;
    }
    return (await response.json()) as T;
  }

  listIntegrations() {
    return this.request<IntegrationSummary[]>('GET', '/api/v1/integrations');
  }

  createIntegration(input: {
    name: string;
    type: string;
    endpoint: string;
    credential: { kind: string; secretReference?: string; roleArn?: string; externalId?: string };
    configuration?: Record<string, string>;
  }) {
    return this.request<IntegrationSummary>('POST', '/api/v1/integrations', input);
  }

  testIntegration(integrationId: string) {
    return this.request<ConnectionTestResult>('POST', `/api/v1/integrations/${integrationId}/test`);
  }

  syncIntegration(integrationId: string) {
    return this.request<SyncResult>('POST', `/api/v1/integrations/${integrationId}/sync`);
  }

  listIntegrationServices(integrationId: string) {
    return this.request<DiscoveredResource[]>('GET', `/api/v1/integrations/${integrationId}/services`);
  }

  importService(integrationId: string, resourceExternalId: string, name?: string) {
    return this.request<ImportedService>('POST', '/api/v1/services/import',
      { integrationId, resourceExternalId, name });
  }

  listServices() {
    return this.request<ServiceSummary[]>('GET', '/api/v1/services');
  }

  getMapping(serviceId: string) {
    return this.request<ServiceMapping>('GET', `/api/v1/services/${serviceId}/mapping`);
  }

  observe(serviceId: string) {
    return this.request<DeploymentObservation>('POST', `/api/v1/services/${serviceId}/observations`);
  }

  createReleaseFromObservation(serviceId: string) {
    return this.request<ObservedRelease>('POST', `/api/v1/services/${serviceId}/releases`);
  }

  listReleases(serviceId: string) {
    return this.request<Release[]>('GET', `/api/v1/releases?serviceId=${encodeURIComponent(serviceId)}`);
  }

  getRelease(releaseId: string) {
    return this.request<Release>('GET', `/api/v1/releases/${releaseId}`);
  }

  prepareRelease(releaseId: string) {
    return this.request<Release>('POST', `/api/v1/releases/${releaseId}/prepare`);
  }

  markReleaseReady(releaseId: string) {
    return this.request<Release>('POST', `/api/v1/releases/${releaseId}/ready`);
  }

  preflight(releaseId: string) {
    return this.request<Preflight>('GET', `/api/v1/releases/${releaseId}/reversibility`);
  }

  rollback(releaseId: string, reason: string) {
    return this.request<Release>('POST', `/api/v1/releases/${releaseId}/rollback`, { reason });
  }

  commit(releaseId: string) {
    return this.request<Release>('POST', `/api/v1/releases/${releaseId}/commit`);
  }

  audit(releaseId: string) {
    return this.request<AuditEntry[]>('GET', `/api/v1/releases/${releaseId}/audit`);
  }
}

export interface IntegrationSummary {
  integrationId: string;
  name: string;
  connectorType: string;
  endpoint: string;
  connectionState: string;
  healthState: string;
  healthDetail: string;
  lastSuccessfulSyncAt: string | null;
  lastError: string | null;
  capabilities: string[];
}

export interface ConnectionTestResult {
  success: boolean;
  message: string;
  checkedAt: string;
  details: Record<string, string>;
}

export interface SyncResult {
  discoveredCount: number;
  errorCount: number;
  errors: string[];
}

export interface DiscoveredResource {
  resourceId: string;
  resourceType: string;
  externalId: string;
  displayName: string;
  region: string | null;
  metadata: Record<string, string>;
}

export interface ImportedService {
  serviceId: string;
  organizationId: string;
  name: string;
}

export interface ServiceSummary {
  serviceId: string;
  name: string;
}

export interface ServiceMapping {
  serviceId: string;
  bindings: Array<{
    role: string;
    integrationId: string;
    externalId: string;
    confidence: string;
    evidence: string;
  }>;
}

export interface DeploymentObservation {
  observationId: string;
  candidateRevision: string;
  previousRevision: string | null;
  candidateArtifactDigest: string | null;
  previousArtifactDigest: string | null;
  commitSha: string | null;
  releaseId: string | null;
}

export interface ObservedRelease {
  releaseId: string;
  previousVersionLabel: string;
  candidateVersionLabel: string;
  state: string;
}

export interface Release {
  releaseId: string;
  serviceId: string;
  previousVersionLabel: string;
  candidateVersionLabel: string;
  state: string;
  epoch: number;
}

export interface Preflight {
  releaseId: string;
  status: string;
  verdict: string;
  checks: Array<{
    name: string;
    passed: boolean;
    blockerCode: string | null;
    blockerDescription: string | null;
    blockerSeverity: string | null;
  }>;
  evidence: Array<{ subject: string; relation: string; object: string; source: string }>;
}

export interface AuditEntry {
  action: string;
  actor: string;
  timestamp: string;
  reason: string | null;
}
