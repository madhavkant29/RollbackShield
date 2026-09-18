# Testing Strategy

## Layers
- **Domain unit tests** (`sdk-java`, `backend`): `ContractEvaluatorTest`,
  `RollbackGuardTest`, `ReversibilityEvaluatorTest`,
  `WorkFenceApplicationServiceTest` — pure functions and small object
  graphs, no Spring context, fast.
- **SDK HTTP test**: `HttpPolicySourceTest` — a real local `HttpServer`
  proves the SDK sends (and, when unconfigured, omits) the service
  credential header.
- **Architecture tests**: `ModuleBoundaryTest` (ArchUnit) — domain code
  can't import Spring/AWS SDK/Jackson; application code can't reach into
  another module's adapter package. Enforced by the build, not review.
- **Integration tests**: `ReleaseLifecycleIntegrationTest` (`@SpringBootTest`
  + MockMvc, real dispatcher, in-memory persistence) — the full §38 E2E
  scenario plus a negative-path transition test. `CorsConfigurationTest`
  proves the control-room origin allow-list is enforced in both directions.
- **Tenant isolation test**: `TenantIsolationTest` — application-service
  layer, two distinct `OrganizationId`s, proves cross-tenant reads throw.
- **DynamoDB adapter test**: `DynamoDbAdapterIntegrationTest` — Testcontainers
  runs DynamoDB Local and drives every `*Item` repository against the real
  table/gsi1 topology from `data-stack.ts` (round-trips, gsi1 queries,
  contract JSON rules, optimistic-lock conditional writes, work-fence
  ledger including an 8-thread concurrent-redeem test). Skips when Docker
  is unavailable (`disabledWithoutDocker`).
- **Auth test**: `ServiceCredentialAuthTest` — worker/policy endpoints
  reject a missing or wrong service credential and accept the configured
  one; tenant endpoints still use the user principal.
- **LocalStack adapter test**: `AwsAdapterLocalStackIntegrationTest` —
  Testcontainers runs LocalStack and exercises the SQS work-queue wire
  format and the EventBridge publish path (including a rule delivering to
  an SQS sink). Skips without Docker.
- **Frontend E2E**: `frontend/e2e` (Playwright) drives the real browser
  flow against a live backend and asserts the Cognito Bearer header is
  attached when configured.

## What's NOT tested yet
JMH latency benchmarks (see `PERFORMANCE_AND_LATENCY.md`), the AWS
adapters against **real** AWS (they are covered against DynamoDB Local and
LocalStack — see above — but both need Docker and skip without it), and
the Next.js frontend has no component/unit tests (only the committed
Playwright E2E suite).

## Running
```
cd sdk-java && mvn install
cd backend && mvn verify        # DynamoDB Local + LocalStack tests need Docker
cd frontend && npm run build && npm run lint
cd frontend && npm run test:e2e # needs the backend running
```
All are green as of this build: `sdk-java` 14 tests, `backend` 32 tests,
frontend build + lint clean, Playwright 2 tests passing.

## Philosophy
No tests for getters/setters, no mocking the method under test. Every
test here targets a specific invariant (a rule blocks the right value, a
transition is rejected, a tenant can't cross a boundary) — see §39.
