# Known Limitations (current state)

Honest list -- update as these are closed, don't let this drift from
reality.

## Correctness gaps

None open in the core lifecycle. Release, Contract, Organization,
AppService, Integration, ServiceMapping and DeploymentObservation state all
go through repository ports; the work-fence epoch registry and redemption
ledger are DynamoDB-backed (ADR-005). The one deliberate behavioural
tradeoff is unchanged: a worker that crashes after redeeming `EXECUTE` but
before performing the side effect forfeits that effect (at-most-once) rather
than risking a duplicate (see `docs/features/WORK_FENCING.md`).

## Verification status (as of the last real build/run)

- `sdk-java` `mvn install`: green (14 tests).
- `backend` `mvn clean verify`: green (93 tests). Includes:
  - ArchUnit `ModuleBoundaryTest` (6 rules, incl. vendor-SDK confinement),
    release/contract/tenant/work-fence tests;
  - `AwsProfileContextTest`: the `aws` profile starts and resolves
    DynamoDB-backed connectivity repositories;
  - `DynamoDbConnectivityAdapterIntegrationTest`: Integration,
    DiscoveredResource, ServiceMapping and DeploymentObservation adapters
    against DynamoDB Local (replacement semantics and latest-observation
    ordering included);
  - `AwsConnectorsLocalStackIntegrationTest` against real LocalStack for
    STS, SQS, CloudWatch Logs, EventBridge;
  - `PostgresDatabaseAdapterIntegrationTest` against a real PostgreSQL 16
    (Testcontainers);
  - `KubernetesRuntimeAdapterTest` against the fabric8 Kubernetes mock API
    server with CRUD semantics;
  - `GitHubConnectorTest` over real HTTP against a local stub, including
    RS256 GitHub App JWT minting verified with the matching public key;
  - `EcsRuntimeAdapterTest` / `EcrArtifactAdapterTest` as SDK contract
    tests (see the live-AWS gap below);
  - `ConnectedReleaseFlowTest`: connect -> sync -> import -> observe ->
    release -> preflight (destructive migration blocks, artifact-missing
    blocks) -> rollback through the runtime connector.
- `frontend`: `npm run build` (11 routes) and `npm run lint` pass.
- `cli`: `npm test` passes (4 tests against a real local HTTP server);
  `npm run build` type-checks with `strict` + `noImplicitAny`.
- `npx cdk synth --all` produces valid CloudFormation for all five stacks.

## Still not verified / not built

- **Live AWS verified (2026-09-19).** All six CDK stacks were deployed to a
  real account (`ap-south-1`); ECS discovery, candidate/previous task
  definition detection, ECR tag→digest resolution, preflight evidence,
  rollback execution (ECS restored to the previous task definition, health
  verified), and SQS epoch fencing (stale v2 work redeemed `CANCEL`) were
  all exercised against live AWS. Full record:
  `docs/LIVE_AWS_VERIFICATION.md`. Two deployment defects were found and
  fixed by that run (JWT issuer property nesting; missing `curl` in the
  runtime image) plus one diagnosability defect (unlogged unexpected
  exceptions).
- **No real GitHub installation.** The GitHub App JWT + installation-token
  exchange is exercised against a local stub that verifies the signature.
  Real discovery *was* performed live through the connector's public mode
  against api.github.com (11 repositories discovered, including this
  repository with its real default branch and push timestamp); a real App
  installation/private-repository read still awaits a customer account.
- **Real Cognito Hosted UI is not exercised.** The frontend PKCE flow and
  Bearer attachment are implemented and tested against a fake token; no
  real user pool has been driven end-to-end.
- **GitHub Actions composite actions are not executed on a real runner.**
  They are real API calls (no local decisions), but only reviewed, not
  run in CI.
- `MutationBlocked`/`RollbackRiskDetected` telemetry ingestion: the SDK
  buffers these decisions but does not yet report them to the control
  plane; only server-driven events are published.
- **Synchronous rollback can outlive the HTTP request.** Observed live:
  rollback completed server-side (`ROLLED_BACK`, ECS `:1`, health verified)
  while the caller received `504` at the ALB's 60-second idle timeout. Until
  asynchronous rollback execution/status ships, check the final state by
  polling `GET /releases/{id}` after a 504 instead of treating it as failure.
- One shared service credential, not per-contract credentials/mTLS.
- Kubernetes rollback execution is deliberately not implemented
  (`KubernetesRuntimeAdapter` declares no ROLLBACK_EXECUTION capability).
  Rollback for Kubernetes would need policy decisions this product should
  not silently make.
- The Flyway analyser is conservative lexical classification, not a SQL
  parser: statements it does not recognize become REQUIRES_REVIEW and are
  listed in `unsupportedStatements`. Dialect-specific constructs it cannot
  classify will never be reported as SAFE.
- EventBridge is used for domain events and bus discovery; it is not a
  synchronous dependency anywhere.

## Security posture (current)

- Tenant scope always comes from `CurrentPrincipal`; every per-service,
  per-integration and per-contract endpoint checks caller-org against
  resource-org, including the newer mapping and observation endpoints.
- Credentials are stored as references only (`IntegrationCredentialReference`);
  local profile resolves them from environment variables, AWS profile from
  Secrets Manager under the task role. No secret is returned by any API.
- AWS customer integration uses STS AssumeRole with an external id; static
  customer keys are rejected by validation.
- `GET /work/poll`, `POST /work/{jobId}/redeem`, and
  `GET /contracts/{contractId}/policy` require the shared service
  credential in both profiles.

## Deployed frontend/API edge (hackathon)

The browser reaches the API directly through API Gateway HTTPS (	4dv6crzic.execute-api.ap-south-1.amazonaws.com) with a public HTTP proxy to the internet-facing ALB; CORS is restricted to the Amplify origin. This is a deliberate hackathon tradeoff (public ALB remains reachable, HTTP hop inside AWS, ~30s API Gateway integration timeout so synchronous rollback can 504 while continuing -- poll release state). See `docs/architecture/FRONTEND_API_EDGE.md` and `docs/adr/006-api-gateway-public-alb-hackathon-edge.md`; post-hackathon target is VPC Link + private ALB plus async rollback operations.
