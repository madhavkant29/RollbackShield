# Live AWS Verification

Date: 2026-09-19. Region: `ap-south-1`. Account: the
`rollbackshield-deploy` account (id omitted per repository policy).
Profile: `rollbackshield-deploy` (assumed
`RollbackShieldDeploymentRole`, MFA; no permanent access keys, no new IAM
user).

## Stacks deployed (CDK)

`RollbackShield-Network-dev`, `-Data-dev`, `-Identity-dev`,
`-ControlPlane-dev`, `-Observability-dev`, `-Demo-dev` — all
`CREATE_COMPLETE`. CDK bootstrap was created in the same region.

Control plane: ALB `Rollba-Alb16-kNzYBOVDcjAq-831746081.ap-south-1.elb.amazonaws.com`,
`GET /actuator/health` = `{"status":"UP"}`.

## Demo application (ECS + ECR)

| Item | Value |
| --- | --- |
| Cluster / service | `rollbackshield-demo` / `payments` |
| Repository | `<account>.dkr.ecr.ap-south-1.amazonaws.com/rollbackshield-demo-payments` |
| v1 image digest | `sha256:0adf7ee8d1aa6c27e54c99ebd2e28a098a07ba2ae28fb20afb26dbba583d1a07` |
| v2 image digest | `sha256:114dff0fc8ee3d0200c3a12c60e3e2b79d0920dd953175ecb78a0b157425b25e` |
| Candidate task definition | `rollbackshield-demo-payments:2` (running v2) |
| Previous task definition | `rollbackshield-demo-payments:1` (running v1) |

Both digests were read from ECR `DescribeImages` (tags recorded only as
labels; identity resolved to the immutable digest).

## Verification sequence (all against live AWS)

1. **Connect**: integration `aws-live` (`AWS_CONTROL_PLANE_ROLE`,
   `ap-south-1`) → `sts:GetCallerIdentity ok for account ...` →
   `CONNECTED`/`HEALTHY`.
2. **Discover**: sync found **17** resources with 0 errors, including
   `RUNTIME_CLUSTER` ×2, `RUNTIME_SERVICE` ×2, `ARTIFACT_REPOSITORY` ×3,
   `QUEUE` ×3, `EVENT_BUS` ×2, `LOG_GROUP` ×5.
3. **Map / import**: imported the discovered ECS service
   `rollbackshield-demo/payments` as service `payments`; the artifact
   repository binding came from the image URI (no fixtures).
4. **Observe** (automatic): candidate
   `arn:aws:ecs:ap-south-1:...:task-definition/rollbackshield-demo-payments:2`,
   previous `...:1`;
   candidate digest `sha256:114dff0f...`, previous digest
   `sha256:0adf7ee8...`; release auto-created and advanced to **READY**
   (`releaseCreated=true`).
5. **Preflight** (before contract): `status=AT_RISK`,
   `verdict=CANNOT_ROLLBACK` with `NO_ACTIVE_CONTRACT` blockers, and
   **COMPUTE PASS, ARTIFACT PASS, HEALTH PASS, ROLLBACK EXECUTION PASS**.
   Database remained `MIGRATION_ANALYSIS_UNAVAILABLE` because the demo
   service has no migration source — deliberately not faked; there is no
   database to map for an nginx demo.
6. **Protect**: contract activated → release `PROTECTED_ROLLOUT`, epoch 1.
7. **Local SDK enforcement** (`ProtectedDemo` against a live backend):
   `PARTIALLY_REFUNDED -> BLOCK (CROSSES_ROLLBACK_HORIZON)` and the write
   never reached persistence; `PAID -> ALLOW`. Policy fetched once over
   HTTP; decisions evaluated locally.
8. **Async work fence**: candidate work enqueued to the real SQS queue
   `rollbackshield-demo-jobs` carrying release + epoch 1.
9. **Rollback** (orchestrator, real AWS): `ROLLING_BACK` → epoch
   invalidated → previous artifact verified in ECR → ECS `UpdateService`
   to task definition `:1` → monitored to stable → health
   (desired=running=1) → `ROLLED_BACK`.
10. **Independent AWS verification** (not RollbackShield's store):
    `aws ecs describe-services --cluster rollbackshield-demo --services payments`
    after rollback: `taskDefinition=.../rollbackshield-demo-payments:1`,
    `running=1`, `desired=1`.
11. **Stale-work proof**: the queued v2 job was polled from real SQS and
    redeemed after rollback → `CANCEL` (epoch invalidated). A second
    redeem also returned `CANCEL` (idempotent).
12. **Audit** (release trail):
    `RELEASE_CREATED, RELEASE_OBSERVED, RELEASE_PREFLIGHT_EVALUATED,
    CONTRACT_CREATED, CONTRACT_ACTIVATED, WORK_ENQUEUED,
    ROLLBACK_REQUESTED, EPOCH_INVALIDATED, ROLLBACK_EXECUTION_STEP ×N,
    ROLLBACK_COMPLETED, WORK_CANCELLED`.

CLI parity: `rollbackshield services list`, `status <service>`,
`release inspect <service>` returned the same state, verdict, blockers,
evidence and audit trail as the API.

## Defects found by this live run (all fixed)

1. **`aws` profile could not start**: the JWT issuer was declared under
   top-level `security:` instead of `spring.security:` in
   `application.yml`, so no `JwtDecoder` bean existed. Fixed; a
   regression test now resolves the issuer from `COGNITO_ISSUER_URI`.
2. **Container health check failed forever**: `eclipse-temurin:21-jre-alpine`
   has no `curl`, which the ECS health check invokes. Dockerfile now
   installs it.
3. **Unexpected 500s were undiagnosable**: `GlobalExceptionHandler`
   swallowed unlogged exceptions. It now logs the cause with the request
   id while keeping the response generic.

## Cost and teardown

Running cost while left up: ControlPlane ALB + Fargate ≈ $35–50/month,
demo task ≈ $9–12/month. Stop the compute with:

```
aws ecs update-service --cluster rollbackshield-demo --service payments --desired-count 0 --region ap-south-1 --profile rollbackshield-deploy
aws ecs update-service --cluster rollbackshield --service <control-plane-service> --desired-count 0 --region ap-south-1 --profile rollbackshield-deploy
```

Full teardown: `npx cdk destroy --all` (the DynamoDB table and Cognito
pool are `RETAIN` by design; see `docs/operations/CLEANUP.md`).

## Not verified by this run

- Cognito Hosted UI was not driven end-to-end (the deployed control plane
  serves real JWTs, but no user signed in through the hosted UI here).
- Real GitHub App installation, real Kubernetes cluster, Kubernetes
  rollback execution (unsupported by design), SDK telemetry ingestion.
  These remain in `docs/product/LIMITATIONS.md`.
