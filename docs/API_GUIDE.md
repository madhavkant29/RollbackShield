# API Guide

Base URL: `http://localhost:8080` locally, the ALB DNS name in AWS.
Auth: under the `local` Spring profile every request is treated as a fixed
dev principal (no token needed). Under `aws`, every request needs a valid
Cognito JWT (`Authorization: Bearer <token>`).

Three endpoints are worker/infrastructure calls rather than tenant calls
(`GET /work/poll`, `POST /work/{jobId}/redeem`,
`GET /contracts/{contractId}/policy`) and require the shared service
credential in `X-RollbackShield-Service-Credential` instead of a JWT.
Locally it defaults to `local-dev-service-credential`; under `aws` it is
`ROLLBACKSHIELD_SERVICE_CREDENTIAL` with no default (blank fails closed).
Missing/invalid → `401 INVALID_SERVICE_CREDENTIAL`.

All responses are JSON. Errors follow the structured shape in
`shared.api.ApiError` — always `{code, message, timestamp, requestId,
details}`; clients should switch on `code`, never parse `message`.

## Organizations & services

```
POST /api/v1/organizations          {"name": "..."}                → 201 OrganizationResponse
POST /api/v1/services                {"name": "..."}                → 201 ServiceResponse (org from caller's token)
GET  /api/v1/services                                                → 200 ServiceResponse[]
```

## Releases

```
POST /api/v1/releases                {"serviceId","previousVersionLabel","candidateVersionLabel"} → 201
GET  /api/v1/releases/{releaseId}                                    → 200 ReleaseResponse
GET  /api/v1/releases?serviceId=...                                  → 200 ReleaseResponse[]
POST /api/v1/releases/{releaseId}/prepare                            → 200
POST /api/v1/releases/{releaseId}/ready                              → 200
POST /api/v1/releases/{releaseId}/rollback   {"reason": "..."}        → 200
POST /api/v1/releases/{releaseId}/commit                             → 200
```

## Contracts

```
POST /api/v1/releases/{releaseId}/contracts
     {"rollbackWindowSeconds", "candidateEpochRequiredForAsyncWork", "rules":[...]}
     → 201 ContractResponse (also drives release to PROTECTED_ROLLOUT)

GET  /api/v1/contracts/{contractId}                                  → 200 ContractResponse
GET  /api/v1/contracts/{contractId}/policy                           → 200 PolicyResponse
     (requires the service credential — see Auth above)
```
`GET .../policy` is the ONE endpoint the Java SDK calls — only from its
background `PolicyCache` refresh, never per mutation. See
`docs/architecture/POLICY_DISTRIBUTION.md`.

Rule shapes (discriminated by `type`):
```json
{"type":"ENUM_ALLOWED_VALUES","entity":"Order","field":"status","previousVersionSupports":["CREATED","PAID"]}
{"type":"NULLABILITY","entity":"Order","field":"customerId","previousVersionAllowsNull":false}
{"type":"NUMERIC_RANGE","entity":"Order","field":"discountPercent","min":0,"max":100}
{"type":"REQUIRED_FIELD","entity":"Order","field":"shippingAddress"}
{"type":"FORBIDDEN_VALUE","entity":"Order","field":"status","forbiddenValues":["VOID_LEGACY"]}
```

## Work fencing

```
POST /api/v1/releases/{releaseId}/work   {"jobType","payload"}       → 201 {jobId, releaseId, releaseEpoch, ...}   (user/JWT)
GET  /api/v1/work/poll?max=10                                        → 200 WorkJobResponse[]                       (service credential)
POST /api/v1/work/{jobId}/redeem  {"releaseId","releaseEpoch"}        → 200 {jobId, outcome: EXECUTE|CANCEL}        (service credential)
```

## Integrations & connectors

```
POST   /api/v1/integrations                        {name,type,endpoint,credential,configuration} → 201 Integration (CONNECTING)
GET    /api/v1/integrations                        → 200 Integration[]
GET    /api/v1/integrations/{id}                   → 200 Integration
POST   /api/v1/integrations/{id}/test              → 200 {success,message,checkedAt,details}   (real provider call)
POST   /api/v1/integrations/{id}/sync              → 200 {discoveredCount,errorCount,errors[]} (partial failure keeps previous state)
POST   /api/v1/integrations/{id}/disconnect        → 200 Integration (DISCONNECTED)
DELETE /api/v1/integrations/{id}                   → 204
GET    /api/v1/integrations/{id}/resources?type=X  → 200 DiscoveredResource[]
GET    /api/v1/integrations/{id}/services          → 200 DiscoveredResource[] (importable runtimes)
```

`credential` is always a reference: `{kind, secretReference?, roleArn?, externalId?}`.
Kinds: `NONE`, `AWS_CONTROL_PLANE_ROLE`, `AWS_ASSUME_ROLE`, `GITHUB_APP`,
`GITHUB_TOKEN`, `KUBERNETES_KUBECONFIG`, `POSTGRES_PASSWORD`. No secret value is accepted or returned.

## Service mapping & deployment observation

```
POST   /api/v1/services/import                     {integrationId,resourceExternalId,name?} → 201 {serviceId,...,mapping}
GET    /api/v1/services/{serviceId}/mapping        → 200 {serviceId,bindings:[{role,integrationId,resourceType,externalId,confidence,evidence,boundAt}]}
POST   /api/v1/services/{serviceId}/mapping/bindings  {role,integrationId,externalId,evidence?} → 200 Mapping
DELETE /api/v1/services/{serviceId}/mapping/bindings?role=&externalId= → 200 Mapping
POST   /api/v1/services/{serviceId}/observations   → 201 DeploymentObservation + {releaseId, releaseState, releaseCreated}
       (automatic workflow: creates or reuses the release, advances it to READY, runs the first preflight)
GET    /api/v1/services/{serviceId}/observations   → 200 {observations:[...]}
GET    /api/v1/services/{serviceId}/observations/latest → 200 DeploymentObservation
POST   /api/v1/services/{serviceId}/releases       → 201 {releaseId,previousVersionLabel,candidateVersionLabel,state} (idempotent)
```

## Reversibility & audit

```
GET /api/v1/releases/{releaseId}/reversibility  → 200 {releaseId, status, verdict, checks:[{name,passed,blockerCode,blockerDescription,blockerSeverity}], evidence:[{subject,relation,object,source,detail}], evaluatedAt}
GET /api/v1/releases/{releaseId}/audit          → 200 AuditEventResponse[]
```

`verdict` is `CAN_ROLLBACK | CANNOT_ROLLBACK | UNKNOWN`; never a score.
`POST /releases/{releaseId}/rollback` executes through the rollback
orchestrator: it verifies the artifact, asks the runtime connector to
restore the previous revision, monitors convergence and verifies health.
Failure yields `FAILED` with the exact step, never `ROLLED_BACK`.

## Error codes

`RELEASE_NOT_FOUND`, `SERVICE_NOT_FOUND`, `ORGANIZATION_NOT_FOUND`,
`INVALID_RELEASE_TRANSITION` (409, with `from`/`to` in `details`),
`STATE_TRANSITION_FAILED` (409, concurrent write — retry),
`CONTRACT_VERSION_CONFLICT` (409, release already has an active contract),
`INVALID_ROLLBACK_CONTRACT`, `VALIDATION_FAILED` (400, field errors in
`details`), `INVALID_SERVICE_CREDENTIAL` (401, worker/SDK endpoints),
`INTERNAL_ERROR` (500, unexpected).

Connector and mapping error codes:

- `CONNECTOR_NOT_IMPLEMENTED` (404/400) — no connector for that
  `ConnectorType`, or the type is not implemented by this build.
- `CAPABILITY_NOT_SUPPORTED` (409) — the connector does not declare the
  requested capability.
- `INVALID_CREDENTIAL_REFERENCE` (400) — credential kind not valid for the
  connector type, or a required reference field is missing.
- `CREDENTIAL_UNAVAILABLE` (400) — the referenced secret could not be
  resolved (local: environment variable unset; AWS: Secrets Manager).
- `INVALID_INTEGRATION` (400), `INVALID_BINDING_ROLE` (400),
  `INVALID_RESOURCE_TYPE` (400).
- `INTEGRATION_NOT_FOUND`, `RESOURCE_NOT_FOUND` (404 — usually "sync
  first"), `DEPLOYMENT_NOT_OBSERVED` (404/409), `SERVICE_NOT_MAPPED` /
  `RUNTIME_NOT_MAPPED` (409 — import and map a runtime first).
