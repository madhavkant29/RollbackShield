# Handoff: RollbackShield v0.1

**Delete this file once every item in "Immediate next steps" below is
resolved.** It's a one-time briefing for whichever agent picks this up
next, not standing project documentation — that's `AGENTS.md` and
`docs/`. If you're reading this and everything below is already done,
delete this file in the same commit that confirms it.

## What you're picking up

RollbackShield: a deployment reversibility control plane (hackathon
v0.1). Full product context: `docs/PRODUCT_OVERVIEW.md`. This codebase
was built by an AI assistant (Claude) across an extended chat session,
in a sandboxed environment with **no access to Maven Central and no
browser/screenshot capability**. That constraint shaped what could and
couldn't be verified — read the next section carefully before assuming
anything works.

## What's been verified vs. still open (updated after a real build/run)

**Verified for real, with evidence:**
- Pure-domain Java (records, sealed interfaces, state machines) compiles
  clean with `javac` directly.
- The SDK's `HttpPolicySource` + `MinimalJson` + `PolicyCache` +
  `RollbackGuard` chain, proven end-to-end against a real local
  `HttpServer` — genuine HTTP fetch, real JSON parse, correct
  block/allow decisions, service-credential header asserted. `sdk-java`
  `mvn install` is green (14 tests).
- The entire Spring Boot backend now compiles and `mvn clean verify` is
  green (32 tests: ArchUnit `ModuleBoundaryTest`,
  `ReleaseLifecycleIntegrationTest`, `TenantIsolationTest`,
  `CorsConfigurationTest`, `ServiceCredentialAuthTest`,
  `ReversibilityEvaluatorTest`, `WorkFenceApplicationServiceTest`,
  `DynamoDbAdapterIntegrationTest` against DynamoDB Local, and
  `AwsAdapterLocalStackIntegrationTest` against LocalStack). Two real
  build-blockers were fixed on first compile: an illegal `--` in an XML
  comment in `backend/pom.xml`, and a spurious required `releaseId` body
  field in `CreateContractRequest`.
- `ProtectedDemo` now runs against a live backend (`local` profile) over
  real HTTP — full block/allow, fencing, idempotent redeem, audit flow.
- Frontend: builds, and has now been rendered and driven end-to-end in a
  real headless browser against the live backend (service -> release ->
  prepare -> ready -> contract -> rollback). CORS was missing and was
  added; the contract-activation control was missing from the UI and was
  added.
- Infrastructure: `npm install && npx cdk synth --all` succeeds,
  producing valid CloudFormation for all 5 stacks.
- `backend/Dockerfile` now builds; the container serves
  `/actuator/health` = `UP`.

Also verified since: work-fence epoch registry + redemption ledger are
DynamoDB-backed (ADR-005, concurrent-redeem test); worker + SDK endpoints
require a shared service credential; the control room implements Cognito
PKCE sign-in and attaches the Bearer token (Playwright-tested); frontend
`npm run lint` passes; EventBridge/SQS run against LocalStack.

**Still open (needs your AWS account, or is deliberately deferred):**
- `cdk deploy` to a real AWS account — never attempted. Real IAM task-role
  auth and region resolution are therefore unverified.
- Real Cognito Hosted UI not driven end-to-end (client flow + Bearer
  attachment are implemented and tested against a fake token).
- `MutationBlocked`/`RollbackRiskDetected` telemetry ingestion is not
  built (SDK buffers, does not report) — see LIMITATIONS.md.

## Immediate next steps, in order

1. **DONE — `sdk-java mvn install` / `mvn test`** (12 tests green).
2. **DONE — `backend mvn verify`** (15 tests green). Fixed: illegal XML
   comment in `backend/pom.xml`; spurious required `releaseId` in
   `CreateContractRequest`.
3. **DONE — frontend built and driven in a real headless browser.**
   Fixed: backend CORS was missing entirely; the UI had no
   contract-activation control, so `PROTECTED_ROLLOUT` was unreachable.
   Design reviewed against the dark/operational brief — looks right.
4. **DONE — `ProtectedDemo` ran end-to-end against a live backend.**
   The predicted `ControlPlaneClient`/controller shape mismatch surfaced
   as the `CreateContractRequest.releaseId` field (now removed).
5. **DONE — `backend/Dockerfile` built; container health = UP.**
6. **DONE — LIMITATIONS gaps closed**: DynamoDB-backed work-fence ledger,
   worker/SDK service credential, frontend Cognito PKCE, DynamoDB Local +
   LocalStack adapter verification, frontend E2E + lint. The only entry
   left in `LIMITATIONS.md` is the live-AWS one below.
7. **OPEN — AWS deployment**: `docs/operations/AWS_DEPLOYMENT.md` has
   exact commands, expected outputs, and a troubleshooting handoff
   format. This file stays until that has actually run.

## Former risk areas (all now covered by tests)

- `WorkFenceApplicationService`'s constructor changed again when the
  epoch registry/ledger became ports; all call sites (Spring wiring,
  `TenantIsolationTest`) were updated and `WorkFenceApplicationServiceTest`
  pins the decision rule.
- `ContractApplicationService.toDomainRule`/`toDto` (wire ↔ DynamoDB JSON
  ↔ domain rules) is covered by the all-five-rule-types round-trip in
  `DynamoDbAdapterIntegrationTest`.
- `DynamoDbReleaseRepository.compareAndSave` + `@DynamoDbVersionAttribute`
  is confirmed by the optimistic-lock test in the same class.
- Every DynamoDB `*Item` class is exercised against DynamoDB Local, and
  the EventBridge/SQS wire formats against LocalStack.

## What to delete once things are working

- **This file (`HANDOFF.md`)**, once the checklist above is resolved.
- Nothing else currently lingers as scaffolding — no debug files, no
  commented-out code, no TODO stubs presented as real. If you find any
  while doing the above, that's a bug in this handoff, not something
  intentionally left for you to clean up.
- As gaps in `docs/product/LIMITATIONS.md` get closed, **update or
  remove those entries** rather than leaving them stale — don't let the
  docs drift from reality in either direction.
- Once `mvn verify` / `npm run build` / `cdk synth` have actually been
  run successfully in a real environment, update the "never verified"
  language in `README.md`, `AGENTS.md`, and this file's own claims
  wherever they're now out of date (this file gets deleted at that
  point anyway, but the others don't).

## Everything else you need

`AGENTS.md` (standing rules), `docs/` (40 files: product, architecture,
operations, development, ADRs — all written to match what's actually
implemented, not aspirational). Start with `docs/PRODUCT_OVERVIEW.md` and
`docs/architecture/SYSTEM_DESIGN.md` if you want the full picture before
diving into code.
