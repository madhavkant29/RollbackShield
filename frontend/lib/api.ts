import { getAccessToken } from '@/lib/auth';

export const API_BASE = process.env.NEXT_PUBLIC_ROLLBACKSHIELD_API_URL ?? 'http://localhost:8080';

export type ReleaseState =
  | 'DRAFT' | 'PREPARING' | 'READY' | 'PROTECTED_ROLLOUT' | 'AT_RISK'
  | 'ROLLING_BACK' | 'ROLLED_BACK' | 'COMMITTING' | 'COMMITTED' | 'FAILED' | 'CANCELLED';

export type ReversibilityStatus = 'REVERSIBLE' | 'AT_RISK' | 'COMMITTED' | 'UNKNOWN';
export type ReversibilityVerdict = 'CAN_ROLLBACK' | 'CANNOT_ROLLBACK' | 'UNKNOWN';

export interface ServiceSummary {
  serviceId: string;
  organizationId: string;
  name: string;
}

export interface Release {
  releaseId: string;
  organizationId: string;
  serviceId: string;
  previousVersionLabel: string;
  candidateVersionLabel: string;
  state: ReleaseState;
  epoch: number;
}

export interface ReversibilityCheck {
  name: string;
  passed: boolean;
  blockerCode: string | null;
  blockerDescription: string | null;
  blockerSeverity: 'BLOCKING' | 'UNKNOWN' | 'REVIEW' | null;
}

export interface ReversibilityEvidence {
  subject: string;
  relation: string;
  object: string;
  source: string;
  detail: string;
}

export interface ReversibilityReport {
  releaseId: string;
  status: ReversibilityStatus;
  verdict: ReversibilityVerdict;
  checks: ReversibilityCheck[];
  evidence: ReversibilityEvidence[];
  evaluatedAt: string;
}

export interface CompatibilityRule {
  type: string;
  entity: string;
  field: string;
  previousVersionSupports?: string[];
  previousVersionAllowsNull?: boolean;
  min?: number;
  max?: number;
  forbiddenValues?: string[];
}

export interface RollbackContract {
  contractId: string;
  releaseId: string;
  contractVersion: number;
  policyVersion: number;
  status: string;
}

export interface CreateContractInput {
  rollbackWindowSeconds: number;
  candidateEpochRequiredForAsyncWork: boolean;
  rules: CompatibilityRule[];
}

export interface AuditEvent {
  eventId: string;
  actor: string;
  action: string;
  target: string;
  timestamp: string;
  reason: string | null;
  previousState: string | null;
  newState: string | null;
}

export interface IntegrationSummary {
  integrationId: string;
  organizationId: string;
  name: string;
  connectorType: string;
  category: string;
  endpoint: string;
  connectionState: 'CONNECTING' | 'CONNECTED' | 'ERROR' | 'DISCONNECTED';
  healthState: 'HEALTHY' | 'DEGRADED' | 'UNHEALTHY' | 'UNKNOWN';
  healthDetail: string;
  lastAttemptedSyncAt: string | null;
  lastSuccessfulSyncAt: string | null;
  lastError: string | null;
  capabilities: string[];
  configuration: Record<string, string>;
  discoveredResourceCount: number;
  createdAt: string;
}

export interface CreateIntegrationInput {
  name: string;
  type: string;
  endpoint: string;
  credential: {
    kind: string;
    secretReference?: string;
    roleArn?: string;
    externalId?: string;
  };
  configuration?: Record<string, string>;
}

export interface ConnectionTestResponse {
  success: boolean;
  message: string;
  checkedAt: string;
  details: Record<string, string>;
}

export interface SyncResultResponse {
  integrationId: string;
  discoveredCount: number;
  removedCount: number;
  errorCount: number;
  errors: string[];
  completedAt: string;
}

export interface DiscoveredResource {
  resourceId: string;
  resourceType: string;
  externalId: string;
  displayName: string;
  region: string | null;
  metadata: Record<string, string>;
  discoveredAt: string;
}

export interface ImportedService {
  serviceId: string;
  organizationId: string;
  name: string;
  mapping: MappingResponse;
}

export interface BindingResponse {
  role: string;
  integrationId: string;
  resourceType: string;
  externalId: string;
  confidence: 'HIGH' | 'MEDIUM' | 'REQUIRES_CONFIRMATION';
  evidence: string;
  boundAt: string;
}

export interface MappingResponse {
  serviceId: string;
  mappingId: string;
  bindings: BindingResponse[];
  updatedAt: string;
}

export interface DeploymentObservation {
  observationId: string;
  serviceId: string;
  integrationId: string;
  runtimeName: string;
  candidateRevision: string;
  previousRevision: string | null;
  candidateArtifactDigest: string | null;
  previousArtifactDigest: string | null;
  commitSha: string | null;
  branch: string | null;
  artifactRepository: string | null;
  deploymentStatus: string | null;
  releaseId: string | null;
  releaseState: ReleaseState | null;
  releaseCreated: boolean;
  observedAt: string;
}

export interface ObservedRelease {
  releaseId: string;
  observationId: string;
  previousVersionLabel: string;
  candidateVersionLabel: string;
  state: ReleaseState;
}

class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  // Cognito access token when the control room is configured for it; null
  // under the local profile, where the backend accepts the dev principal.
  const token = await getAccessToken();
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init?.headers,
    },
    cache: 'no-store',
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new ApiError(response.status, body.code ?? 'UNKNOWN', body.message ?? response.statusText);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return response.json();
}

export const api = {
  listServices: () => request<ServiceSummary[]>('/api/v1/services'),
  createService: (name: string) =>
    request<ServiceSummary>('/api/v1/services', { method: 'POST', body: JSON.stringify({ name }) }),
  importService: (input: { integrationId: string; resourceExternalId: string; name?: string }) =>
    request<ImportedService>('/api/v1/services/import', {
      method: 'POST',
      body: JSON.stringify(input),
    }),
  getMapping: (serviceId: string) =>
    request<MappingResponse>(`/api/v1/services/${serviceId}/mapping`),
  addBinding: (serviceId: string,
               input: { role: string; integrationId: string; externalId: string; evidence?: string }) =>
    request<MappingResponse>(`/api/v1/services/${serviceId}/mapping/bindings`, {
      method: 'POST',
      body: JSON.stringify(input),
    }),
  removeBinding: (serviceId: string, role: string, externalId: string) =>
    request<MappingResponse>(
      `/api/v1/services/${serviceId}/mapping/bindings?role=${encodeURIComponent(role)}`
        + `&externalId=${encodeURIComponent(externalId)}`,
      { method: 'DELETE' },
    ),

  listIntegrations: () => request<IntegrationSummary[]>('/api/v1/integrations'),
  createIntegration: (input: CreateIntegrationInput) =>
    request<IntegrationSummary>('/api/v1/integrations', { method: 'POST', body: JSON.stringify(input) }),
  testIntegration: (integrationId: string) =>
    request<ConnectionTestResponse>(`/api/v1/integrations/${integrationId}/test`, { method: 'POST' }),
  syncIntegration: (integrationId: string) =>
    request<SyncResultResponse>(`/api/v1/integrations/${integrationId}/sync`, { method: 'POST' }),
  disconnectIntegration: (integrationId: string) =>
    request<IntegrationSummary>(`/api/v1/integrations/${integrationId}/disconnect`, { method: 'POST' }),
  deleteIntegration: (integrationId: string) =>
    request<void>(`/api/v1/integrations/${integrationId}`, { method: 'DELETE' }),
  listIntegrationResources: (integrationId: string, type?: string) =>
    request<DiscoveredResource[]>(
      `/api/v1/integrations/${integrationId}/resources${type ? `?type=${encodeURIComponent(type)}` : ''}`),
  listIntegrationServices: (integrationId: string) =>
    request<DiscoveredResource[]>(`/api/v1/integrations/${integrationId}/services`),

  observeService: (serviceId: string) =>
    request<DeploymentObservation>(`/api/v1/services/${serviceId}/observations`, { method: 'POST' }),
  listObservations: (serviceId: string) =>
    request<{ observations: DeploymentObservation[] }>(`/api/v1/services/${serviceId}/observations`),
  latestObservation: (serviceId: string) =>
    request<DeploymentObservation>(`/api/v1/services/${serviceId}/observations/latest`),
  createReleaseFromObservation: (serviceId: string) =>
    request<ObservedRelease>(`/api/v1/services/${serviceId}/releases`, { method: 'POST' }),

  listReleases: (serviceId: string) =>
    request<Release[]>(`/api/v1/releases?serviceId=${encodeURIComponent(serviceId)}`),
  getRelease: (releaseId: string) => request<Release>(`/api/v1/releases/${releaseId}`),
  createRelease: (input: { serviceId: string; previousVersionLabel: string; candidateVersionLabel: string }) =>
    request<Release>('/api/v1/releases', { method: 'POST', body: JSON.stringify(input) }),
  prepareRelease: (releaseId: string) =>
    request<Release>(`/api/v1/releases/${releaseId}/prepare`, { method: 'POST' }),
  markReleaseReady: (releaseId: string) =>
    request<Release>(`/api/v1/releases/${releaseId}/ready`, { method: 'POST' }),
  rollbackRelease: (releaseId: string, reason: string) =>
    request<Release>(`/api/v1/releases/${releaseId}/rollback`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    }),
  commitRelease: (releaseId: string) =>
    request<Release>(`/api/v1/releases/${releaseId}/commit`, { method: 'POST' }),

  createContract: (releaseId: string, input: CreateContractInput) =>
    request<RollbackContract>(`/api/v1/releases/${releaseId}/contracts`, {
      method: 'POST',
      body: JSON.stringify(input),
    }),

  getReversibility: (releaseId: string) =>
    request<ReversibilityReport>(`/api/v1/releases/${releaseId}/reversibility`),
  getAuditTrail: (releaseId: string) =>
    request<AuditEvent[]>(`/api/v1/releases/${releaseId}/audit`),
};

export { ApiError };
