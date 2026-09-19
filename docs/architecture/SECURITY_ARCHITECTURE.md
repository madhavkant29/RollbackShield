# Security Architecture

## AuthN
Cognito User Pool (JWT, signature+issuer+audience+expiry all validated by
Spring's `JwtDecoder` — never hand-decoded) under `aws`; a fixed
`LocalDevAuthFilter` principal under `local`, never both in the same
process (`SecurityConfig`'s two `@Profile`-gated filter chains are
mutually exclusive).

## Frontend sign-in
The control room signs in through Cognito Hosted UI using Authorization
Code + PKCE (`frontend/lib/auth.ts`) when `NEXT_PUBLIC_COGNITO_DOMAIN` and
`NEXT_PUBLIC_COGNITO_CLIENT_ID` are configured; it attaches
`Authorization: Bearer <access token>` to every API call and refreshes the
token before expiry. No client secret is used (public SPA client). When
those variables are unset (local dev) the app sends no token and talks to
the `local` profile. The Bearer-attachment path is covered by
`frontend/e2e/auth.spec.ts`; a real Hosted UI round-trip is not yet
exercised (LIMITATIONS.md).

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

## Service-credential endpoints
`GET /work/poll`, `POST /work/{jobId}/redeem`, and
`GET /contracts/{contractId}/policy` are called by infrastructure, not a
tenant user, so they are not organization-scoped. They require the shared
credential in `X-RollbackShield-Service-Credential`, checked in constant
time by `ServiceCredentialAuthFilter` and authorized by the `SERVICE`
authority the filter grants. A user JWT cannot reach them, and a valid
service credential grants nothing on tenant endpoints (the filter only
authenticates those paths; everything else still needs a user principal).
Under `local` the credential defaults to `local-dev-service-credential`;
under `aws` there is deliberately no default — blank fails closed and
every service call is rejected. The SDK (`HttpPolicySource`) and
`demo-worker` send it from `ROLLBACKSHIELD_SERVICE_CREDENTIAL`.

Remaining hardening (tracked, not hidden): one shared secret rather than a
per-contract credential; rotation guidance lives in `RUNBOOK.md`.

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

## Connector credentials and tenant isolation (connectivity layer)

- `IntegrationCredentialReference` persists references only; values live in
  the process environment (`local`) or Secrets Manager under
  `rollbackshield/*` (`aws`). No API returns a secret; connector errors
  are length-capped before audit/response.
- AWS customer mode is STS AssumeRole with an external id; the trust
  policy condition and the `RollbackShieldObservationRole` naming are
  documented in `docs/operations/AWS_DEPLOYMENT.md`. Static customer keys
  are rejected by validation (`INVALID_CREDENTIAL_REFERENCE`).
- The task role's observation permissions and the single mutating call
  (`ecs:UpdateService`) are separate statements in
  `infrastructure/lib/control-plane-stack.ts`; rollback execution is its
  own capability, never implied by read access.
- Tenant scope on the new endpoints (`/integrations/**`,
  `/services/*/mapping`, `/services/*/observations`) always comes from
  `CurrentPrincipal`; resources are resolved through the owning
  application service, which throws `NOT_FOUND` for another org's ids so
  existence never leaks.
- Rollback authorization requires both ownership (release org == caller
  org) and a valid state transition (READY/PROTECTED_ROLLOUT/AT_RISK);
  every execution step is audited (`ROLLBACK_EXECUTION_STEP`) and a failed
  step records `ROLLBACK_EXECUTION_FAILED` with the provider message.
