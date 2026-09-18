# Testing Strategy

## Layers
- **Domain unit tests** (`sdk-java`, `backend`): `ContractEvaluatorTest`,
  `RollbackGuardTest`, `ReversibilityEvaluatorTest` — pure functions and
  small object graphs, no Spring context, fast.
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
  contract JSON rules, optimistic-lock conditional writes). Skips when
  Docker is unavailable (`disabledWithoutDocker`).

## What's NOT tested yet
JMH latency benchmarks (see `PERFORMANCE_AND_LATENCY.md`), the DynamoDB
adapters against real AWS (they are covered against DynamoDB Local — see
above — but that needs Docker and skips without it), EventBridge/SQS
adapters, the Next.js frontend (no committed component/e2e tests),
`WorkFenceApplicationService` in isolation (only exercised indirectly via
the integration test).

## Running
```
cd sdk-java && mvn test
cd backend && mvn verify
```
Both are green as of this build (12 and 15 tests respectively). The
frontend was additionally driven end-to-end manually in a headless
browser against a live backend; that is not yet a committed test.

## Philosophy
No tests for getters/setters, no mocking the method under test. Every
test here targets a specific invariant (a rule blocks the right value, a
transition is rejected, a tenant can't cross a boundary) — see §39.
