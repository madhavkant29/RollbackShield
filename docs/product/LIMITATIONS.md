# Known Limitations (v0.1, current state)

Honest list -- update as these are closed, don't let this drift from
reality.

## Correctness gaps

None open.

- Release, Contract, Organization, and AppService state all go through
  repository ports; the work-fence epoch registry and redemption ledger
  are DynamoDB-backed ports too (ADR-005) -- durable across restarts and
  safe with more than one backend task.
- The one remaining behavioural tradeoff is deliberate, not a bug: a
  worker that crashes after redeeming `EXECUTE` but before performing the
  side effect forfeits that effect (at-most-once) rather than risking a
  duplicate. Documented in `docs/features/WORK_FENCING.md`.

## Verification status (as of the last real build/run)

- `sdk-java` `mvn install` is green (14 tests).
- `backend` `mvn clean verify` is green (32 tests): ArchUnit
  `ModuleBoundaryTest`, `CorsConfigurationTest`, the full
  `ReleaseLifecycleIntegrationTest`, `TenantIsolationTest`,
  `ServiceCredentialAuthTest`, `ReversibilityEvaluatorTest`,
  `WorkFenceApplicationServiceTest`, `DynamoDbAdapterIntegrationTest`
  (DynamoDB Local via Testcontainers, incl. concurrent redemptions), and
  `AwsAdapterLocalStackIntegrationTest` (EventBridge + SQS via LocalStack).
- `ProtectedDemo` runs end-to-end against a live backend over real HTTP.
- The control room is exercised in a real browser by committed Playwright
  tests (`frontend/e2e`): the service -> release -> contract -> rollback
  flow, and a Bearer-header test that runs against a Cognito-configured
  build.
- `npx cdk synth --all` produces valid CloudFormation for all five stacks.
- `backend/Dockerfile` builds; the container serves `/actuator/health` =
  `UP`.

## Still not verified / not built

- **No live AWS run.** The DynamoDB, EventBridge, and SQS adapters are
  verified against local emulators (DynamoDB Local, LocalStack), not a
  real account; `cdk deploy` has never been run. IAM task-role auth and
  region resolution in real AWS are therefore unverified. This is the one
  item here that needs something a normal dev environment cannot provide.
- **Real Cognito Hosted UI is not exercised.** The frontend PKCE flow and
  `Authorization: Bearer` attachment are implemented, and the token
  attachment is covered by a test; no real Cognito user pool has been
  driven end-to-end.
- `MutationBlocked`/`RollbackRiskDetected` telemetry ingestion (§35): the
  SDK buffers these decisions but does not yet report them to the control
  plane; only server-driven events are published.
- One shared service credential, not per-contract credentials/mTLS.

## Security gaps found and fixed during this build

- `ReversibilityController`, `AuditController`, the work-enqueue endpoint,
  and `ContractController`'s create/get endpoints originally had NO
  organization-ownership check on `releaseId`/`contractId` -- any
  authenticated caller could read (or, for enqueue/create, write to) any
  organization's release or contract by guessing/enumerating its UUID.
  Found while writing `docs/features/REVERSIBILITY_STATUS.md` and fixed
  immediately in the same pass (all now check the caller's organization
  against the resource's, matching the pattern `ReleaseController`
  already used).
- `GET /work/poll`, `POST /work/{jobId}/redeem`, and
  `GET /contracts/{contractId}/policy` were unauthenticated. They are
  worker/infrastructure calls, not tenant calls, so they now require a
  shared service credential (`X-RollbackShield-Service-Credential`,
  `ServiceCredentialAuthFilter`, constant-time compared) plus the
  `SERVICE` authority. A user JWT cannot reach them; the service
  credential cannot reach tenant endpoints. `local` has a fixed dev
  default; `aws` has no default and fails closed. A per-contract
  credential beyond a single shared secret remains future hardening
  (ROADMAP v0.2).
