# Security Architecture

## AuthN
Cognito User Pool (JWT, signature+issuer+audience+expiry all validated by
Spring's `JwtDecoder` — never hand-decoded) under `aws`; a fixed
`LocalDevAuthFilter` principal under `local`, never both in the same
process (`SecurityConfig`'s two `@Profile`-gated filter chains are
mutually exclusive).

## AuthZ / tenant scoping
Every principal carries `organizationId` from a Cognito custom claim
(`custom:organization_id`), never a client-supplied value. Every
per-release/contract endpoint calls `ReleaseApplicationService.get()` (or
equivalent) with the *caller's* org before returning data — proven in
`TenantIsolationTest`. A mismatch returns `404`, never `403` (never
confirms the resource exists to an unauthorized caller).

## Fixed during this build (see LIMITATIONS.md for detail)
`ReversibilityController`, `AuditController`, work-enqueue, and
`ContractController`'s create/get were missing this check when first
written; found during doc review and fixed in the same pass.

## Known open gaps
- `GET /work/poll`, `POST /work/{jobId}/redeem` — worker-trust, not
  per-tenant; need a service credential, not a user JWT.
- `GET /contracts/{contractId}/policy` — the SDK sends no auth header;
  an unguessable UUID is the only protection today.

## CORS
The control room is a separate origin from the control plane. Allowed
origins are an explicit allow-list from `ROLLBACKSHIELD_ALLOWED_ORIGINS`
(default `http://localhost:3000`), wired into both Spring Security filter
chains via a `CorsConfigurationSource` bean. `*` is never used and
credentials are disabled (the UI sends no cookies; AWS auth is meant to be
a Bearer token). Preflight from an unconfigured origin is rejected (403),
asserted by `CorsConfigurationTest`. This was missing entirely until the
control room was first run against the backend.

## Workload IAM
ECS execution role (image pull, logs) and task role (DynamoDB/
EventBridge/SQS, scoped to exactly this table/bus/queue) are separate
(§46, `control-plane-stack.ts`). No static AWS keys anywhere in
configuration.

## Input validation
`jakarta.validation` (`@NotBlank`, `@NotEmpty`) on every request DTO,
enforced by `GlobalExceptionHandler`'s `MethodArgumentNotValidException`
handler → structured `400 VALIDATION_FAILED`.

## Logging
No JWTs, passwords, or AWS credentials are logged anywhere in this
codebase (grep-checked manually, not yet an automated lint rule).
