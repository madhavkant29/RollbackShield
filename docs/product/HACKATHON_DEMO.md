# Hackathon Demo (3 minutes) — real deployed proof

Everything below was executed against the deployed RollbackShield control
plane (six CDK stacks, `ap-south-1`) with real Cognito auth, real AWS
resources and a real SQS queue. Full record: `docs/LIVE_AWS_VERIFICATION.md`
and `docs/FINAL_PRODUCT_AUDIT.md`. Nothing in this script is staged.

## Pre-show state

- Control plane: healthy on image `sha256:ee89b542…`, ALB `/actuator/health` = UP.
- Demo: ECS `rollbackshield-demo/payments` running task definition **`:1`**
  (after the verified rollback below). Candidate `:2` is registered with
  digest `sha256:114dff0f…`; previous `:1` digest `sha256:0adf7ee8…`.
- CLI already logged in; Cognito test user bound to an organization.

## Script (timings are for a 3:00 talk)

**0:00 — The thesis.**
"Deployment tools can move your code backwards. RollbackShield makes sure
your system can actually go back."

**0:15 — CONNECT (Integrations page).**
Show the AWS integration row: `CONNECTED · HEALTHY`, message
`sts:GetCallerIdentity ok for account …`, last successful sync, real
discovered resource count. Point out that CONNECTED only appears after that
STS call.

**0:30 — DISCOVER → IMPORT (Integrations → Resources → Services).**
Open discovered resources: 2 ECS services, 3 ECR repositories, 3 queues,
2 event buses, 5 log groups. Import the ECS service as **`payments-verify`**
and open its mapping: RUNTIME (HIGH, "imported by operator from AWS
RUNTIME_SERVICE") and ARTIFACT_REPOSITORY (HIGH, evidence cites the real
image URI). Say: "these edges are evidence, not guesses."

**0:55 — OBSERVE (Services → Observe deployment).**
Candidate `task-definition:2`, previous `task-definition:1`, digests
resolved from ECR (`sha256:114dff0f…` / `sha256:0adf7ee8…`). The release is
**created automatically** from the observation and reaches READY.

**1:15 — PREFLIGHT (Release control room).**
Walk the dimensions top-to-bottom:
COMPUTE PASS (rollback target `:1`), ARTIFACT PASS (previous digest verified
in ECR), PREVIOUS VERSION HEALTH PASS, ROLLBACK EXECUTION PASS,
DATABASE UNKNOWN (`MIGRATION_ANALYSIS_UNAVAILABLE` — no migration source is
mapped for this demo; we do not fake it), ASYNC/POLICY pending until the
contract exists.

**1:35 — PROTECT.**
Activate the contract → `PROTECTED_ROLLOUT`, epoch 1.

**1:45 — BLOCK the incompatible mutation (SDK, local).**
Run `ProtectedDemo` against a live backend:
`PARTIALLY_REFUNDED -> BLOCK (CROSSES_ROLLBACK_HORIZON)` and the write never
reaches persistence; `PAID -> ALLOW`. The policy was fetched once; the
decision is local.

**2:00 — QUEUE the candidate work.**
Enqueue the v2 webhook job: it lands on the real SQS queue carrying
`releaseId` + `releaseEpoch=1`.

**2:10 — ROLLBACK.**
Trigger rollback. The orchestrator authorizes, invalidates the epoch,
verifies the previous artifact in ECR, calls ECS `UpdateService` back to
`:1`, monitors the rollout, verifies health, and only then records
ROLLED_BACK.

**2:35 — VERIFY independently and show the fence.**
Terminal: `aws ecs describe-services --cluster rollbackshield-demo
--services payments` → task definition `:1`, running 1, desired 1,
deployment COMPLETED. Redeem the queued job → **CANCEL** (epoch
invalidated; stale v2 work can no longer act). Audit timeline shows
`ROLLBACK_REQUESTED → EPOCH_INVALIDATED → ROLLBACK_EXECUTION_STEP×N →
ROLLBACK_COMPLETED → WORK_CANCELLED`, and `HEALTHY (running 1/1 tasks)`.

**2:55 — Close.**
Authenticated `GET /services` still returns 200 with the same token.
"Connect it, and it keeps your ability to roll back — with evidence."
Thesis line again, word for word.

## The one operational caveat (say it if asked)

The synchronous rollback HTTP request can exceed the ALB 60-second idle
timeout and return **504 even though the rollback continues successfully
server-side** — that is exactly what happened in the verified run: the
caller timed out, and the release still reached `ROLLED_BACK` with ECS on
`:1` and health verified. Until asynchronous execution/status ships, check
final state by polling `GET /releases/{id}` rather than treating the 504 as
failure.

## What not to claim

- No live GitHub App installation (public-repo discovery is real; App is
  tested against a verified stub).
- No real Kubernetes cluster; Kubernetes rollback execution is unsupported
  by design and preflight says so.
- Database migration analysis is exercised in tests and by the local live
  run, not by this nginx demo (it has no database).
