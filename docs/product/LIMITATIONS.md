# Known Limitations (v0.1, current state)

Honest list -- update as these are closed, don't let this drift from
reality.

## Correctness gaps

- **Epoch registry and job redemption ledger are in-memory**
  (`WorkFenceApplicationService`'s `EpochRegistry` and
  `ConcurrentHashMap<jobId, RedeemOutcome>`). Correct for a single backend
  instance (the CDK stack currently sets `desiredCount: 1`), but will NOT
  survive a restart or work correctly with more than one task running
  concurrently. Needs a DynamoDB-backed version with conditional writes
  before scaling past one instance. See ADR-005.
- Same caveat applies to nothing else currently -- Release, Contract,
  Organization, and AppService state all go through the repository ports
  and are DynamoDB-backed correctly under the `aws` profile.

## Verification status (updated after a real build/run)

- The Spring Boot backend **has now been compiled and run**: `mvn clean
  verify` is green (21 tests, including ArchUnit `ModuleBoundaryTest`,
  `ReleaseLifecycleIntegrationTest`, `TenantIsolationTest`,
  `CorsConfigurationTest`, `DynamoDbAdapterIntegrationTest`). Two
  build-blocking defects were fixed on the
  first real compile: an illegal `--` inside an XML comment in
  `backend/pom.xml` (the POM did not parse at all), and a spurious required
  `releaseId` field in `CreateContractRequest` that the path-variable API
  never used.
- `ProtectedDemo` **has now run against a live backend** (`local` profile):
  the full service -> release -> contract -> SDK policy fetch -> block/allow
  -> epoch-fenced work -> rollback -> idempotent redeem -> audit sequence
  works over real HTTP.
- The Next.js control room **has now been exercised end-to-end in a real
  browser** (headless Chromium) against the live backend: create service,
  create release, prepare, ready, activate contract, roll back, with the
  reversibility checks and audit trail rendering real data. Two gaps were
  found and fixed in the process: the backend had no CORS configuration
  (the browser blocked every call from the `:3000` origin), and the UI had
  no way to create a contract -- the Contracts page pointed at the release
  control room, which had no such control, so `PROTECTED_ROLLOUT` was
  unreachable from the UI.
- The CDK stacks were verified for real: `npm install` and
  `cdk synth --all` both succeed, producing valid CloudFormation for all
  five stacks. `cdk deploy` has not been run -- that needs your AWS
  account.
- Integration/E2E tests for the REST API boundary now exist and pass
  (`ReleaseLifecycleIntegrationTest`, `TenantIsolationTest`,
  `CorsConfigurationTest`).

## Scope not yet built

- Frontend Cognito auth: the control room fetches the API with no
  `Authorization` header, so it only works under the `local` profile. It
  needs a real token (Cognito Hosted UI / Amplify) before it can talk to
  the `aws` deployment. CORS origins are now configurable
  (`ROLLBACKSHIELD_ALLOWED_ORIGINS`, default `http://localhost:3000`) --
  set it to the real control-room origin in AWS.
- DynamoDB adapters are now verified against **DynamoDB Local** via
  `DynamoDbAdapterIntegrationTest` (Testcontainers; it creates the exact
  table/gsi1 from `data-stack.ts`): item mapping for every `*Item` class,
  gsi1 lookups, the contract rules JSON round-trip, and the
  `compareAndSave` optimistic-lock conditional write all behave correctly.
  Still **not** exercised against real AWS (IAM task-role auth, region
  resolution), and the EventBridge/SQS adapters have only ever been
  instantiated, never run -- both remain unverified outside the in-memory
  profile.
- `MutationBlocked`/`RollbackRiskDetected` domain events (§35) -- the SDK
  doesn't yet report blocked mutations back to the control plane via
  telemetry ingestion; only server-driven events (release/contract/work
  lifecycle) are published today.

## Security gap found and fixed during doc review

- `ReversibilityController`, `AuditController`, the work-enqueue endpoint,
  and `ContractController`'s create/get endpoints originally had NO
  organization-ownership check on `releaseId`/`contractId` -- any
  authenticated caller could read (or, for enqueue/create, write to) any
  organization's release or contract by guessing/enumerating its UUID.
  Found while writing `docs/features/REVERSIBILITY_STATUS.md` and fixed
  immediately in the same pass (all now check the caller's organization
  against the resource's, matching the pattern `ReleaseController`
  already used).
- `GET /work/poll` and `POST /work/{jobId}/redeem` remain intentionally
  unscoped by organization -- these are meant to be called by a worker
  process across all tenants, not by a per-tenant user. They need their
  own service-credential auth model (not a user JWT) before being exposed
  outside a trusted network. Not fixed yet.
- `GET /contracts/{contractId}/policy` (the SDK's fetch endpoint) is also
  unauthenticated by design today -- the SDK sends no Authorization
  header, since it runs inside the protected application's process, not
  a browser session. An unguessable contract UUID is the only protection
  right now. A per-contract fetch credential (API key or mTLS) is the
  correct fix and is not yet built.
