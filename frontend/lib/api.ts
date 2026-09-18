import { getAccessToken } from '@/lib/auth';

const API_BASE = process.env.NEXT_PUBLIC_ROLLBACKSHIELD_API_URL ?? 'http://localhost:8080';

export type ReleaseState =
  | 'DRAFT' | 'PREPARING' | 'READY' | 'PROTECTED_ROLLOUT' | 'AT_RISK'
  | 'ROLLING_BACK' | 'ROLLED_BACK' | 'COMMITTING' | 'COMMITTED' | 'FAILED' | 'CANCELLED';

export type ReversibilityStatus = 'REVERSIBLE' | 'AT_RISK' | 'COMMITTED' | 'UNKNOWN';

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
}

export interface ReversibilityReport {
  releaseId: string;
  status: ReversibilityStatus;
  checks: ReversibilityCheck[];
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
