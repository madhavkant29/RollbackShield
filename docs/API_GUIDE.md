# API Guide

Base URL: `http://localhost:8080` locally, the ALB DNS name in AWS.
Auth: under the `local` Spring profile every request is treated as a fixed
dev principal (no token needed). Under `aws`, every request needs a valid
Cognito JWT (`Authorization: Bearer <token>`).

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
POST /api/v1/releases/{releaseId}/work   {"jobType","payload"}       → 201 {jobId, releaseId, releaseEpoch, ...}
GET  /api/v1/work/poll?max=10                                        → 200 WorkJobResponse[]
POST /api/v1/work/{jobId}/redeem  {"releaseId","releaseEpoch"}        → 200 {jobId, outcome: EXECUTE|CANCEL}
```

## Reversibility & audit

```
GET /api/v1/releases/{releaseId}/reversibility  → 200 {releaseId, status, checks:[{name,passed,blockerCode,blockerDescription}]}
GET /api/v1/releases/{releaseId}/audit          → 200 AuditEventResponse[]
```

## Error codes

`RELEASE_NOT_FOUND`, `SERVICE_NOT_FOUND`, `ORGANIZATION_NOT_FOUND`,
`INVALID_RELEASE_TRANSITION` (409, with `from`/`to` in `details`),
`STATE_TRANSITION_FAILED` (409, concurrent write — retry),
`CONTRACT_VERSION_CONFLICT` (409, release already has an active contract),
`INVALID_ROLLBACK_CONTRACT`, `VALIDATION_FAILED` (400, field errors in
`details`), `INTERNAL_ERROR` (500, unexpected).
