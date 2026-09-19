# Final Product Audit

Date: 2026-09-19. Method: skeptical external engineer, evidence from live
runs only. Nothing graded PASS unless it was executed and observed.

## Status: deployed plane verified

Both planes are now verified end to end: the **local control plane against
live AWS** (`docs/LIVE_AWS_VERIFICATION.md`) and the **deployed control
plane** (six CDK stacks, real Cognito ID token, DynamoDB persistence,
real ECS/ECR/SQS). The deployed run imported a fresh service
(`payments-verify`), observed the live v2 deployment against v1, auto-created
and preflighted the release, activated a contract, enqueued real SQS work,
rolled ECS back to task definition `:1` (verified independently with AWS
CLI: running 1/desired 1, deployment COMPLETED), confirmed previous-version
health `HEALTHY`, redeemed the stale job as `CANCEL`, and produced the full
audit trail — with Cognito authentication still returning 200 afterwards.

Two issues were found and fixed by this run: shared-GSI partition mapping
(org-scoped and service-scoped items deserialized as foreign beans) and the
missing `iam:PassRole` grants (execution **and** task roles) for
`ecs:UpdateService`; plus the terminal-release reuse bug and the unwired
worker service credential (now a generated Secrets Manager secret).

## Requirement classification

| Question | Grade | Evidence |
| --- | --- | --- |
| Can I connect a real repository? | PASS | Public mode discovered 11 real repos from `api.github.com` incl. this repo (`GITHUB.md`). Private/App installation: PARTIAL (never installed). |
| Can I connect real infrastructure? | PASS | Live AWS via `sts:GetCallerIdentity` on local and deployed planes; 17 resources discovered. |
| Can RollbackShield discover actual services? | PASS | Live ECS/ECR/SQS/EventBridge/CloudWatch discovery (17, 0 errors). |
| Can it map a service? | PASS | Import of the discovered ECS service created runtime + artifact-repository bindings from real image URIs; MEDIUM bindings require confirmation. |
| Can it observe a real deployment? | PASS | Candidate `.../task-definition:2`, previous `:1` read from live ECS. |
| Can it identify candidate/previous revisions? | PASS | Same live observation; family/deployment history fallback covered by tests. |
| Can it find the actual rollback artifact? | PASS | ECR tag→digest resolution: candidate `sha256:114dff0f…`, previous `sha256:0adf7ee8…`; existence verified by digest. |
| Can it analyze database changes? | PARTIAL | Conservative Flyway classifier + commit-diff isolation are tested and feed preflight (`DESTRUCTIVE_DATABASE_MIGRATION`). No live service was mapped to a repository with migrations, so live DB analysis was not executed. |
| Can it fence asynchronous work? | PASS | Candidate work enqueued to real SQS with epoch; after rollback polling it and redeeming returned `CANCEL` (twice). |
| Can it explain why rollback is safe/unsafe? | PASS | `verdict` + per-dimension checks + `evidence[]` + structured `blockerPaths[]` (deterministic). |
| Can it execute rollback? | PASS | Live orchestrator restored ECS to task definition `:1`; artifact verified before the provider call; `ROLLED_BACK` only after convergence + health. |
| Can it verify the old version afterward? | PASS | Independent `aws ecs describe-services`: `...:1`, `running=1`, `desired=1`; health check in the orchestrator. |
| Can it produce real audit evidence? | PASS | 37 events on the live release incl. `EPOCH_INVALIDATED`, `ROLLBACK_COMPLETED`, `WORK_CANCELLED`. |
| Can CI call it? | PARTIAL | REST gate pattern documented and CLI gate verified; the composite GitHub Actions exist but were never executed on a runner. |
| Can CLI call it? | PASS | `services list`, `status`, `release inspect` matched the API on the live release; exit-code defect fixed and pinned by spawned-process tests. |
| Can a non-Java runtime benefit? | PASS (control plane) / PARTIAL (enforcement) | Observe/preflight/protect/rollback/audit via REST + CLI for any runtime; local SDK enforcement is Java-only (sidecar on roadmap). |
| Does the UI reflect real backend state? | PASS | No fixture data; Integrations/Services/Reversibility/Control room render API state; Playwright E2E green (Phase 8). |
| Does anything pretend to be connected? | PASS (no fakes found) | Connection state only from real provider tests; ERROR states render provider messages. |

## FAIL items found by this audit

None open for the deployed chain. Fixed and verified during the run
(regression-tested where code changed):

1. **Connectivity GSI partition collision** — org-scoped and service-scoped
   items shared `gsi1pk` with other entity types and were deserialized as
   foreign beans (`ServiceId.of(null)`, `CredentialKind.valueOf(null)`,
   `runtimeName` NPE). Each query now filters by its own sort prefix; a
   regression test writes all item types into one partition.
2. **`iam:PassRole` missing** for both the demo execution role and the task
   role — `ecs:UpdateService` failed from the deployed task role until both
   were granted.
3. **Terminal releases were reused** — re-observing after a rolled-back or
   failed release revived the closed release; now a new release is created
   (regression test added).
4. **Worker credential unwired** — deployed `/work/poll` and redeem failed
   closed; CDK now generates a Secrets Manager secret and injects it.

## Known limitation observed in this run

The synchronous rollback request exceeded the ALB's 60-second idle timeout
and the caller received `504` while the orchestrator completed the rollback
server-side (state `ROLLED_BACK`, health verified, audit intact). The
deployed UI/CI should tolerate this with the async execution endpoint listed
in the audit's implementation order; until then, poll the release state
after a 504 rather than treating it as failure.

## Fixes made during Phase 9

- `aws` profile JWT issuer property nesting (deployment was broken).
- `curl` missing from the runtime image (every ECS task failed health).
- Unexpected exceptions now logged with request id.
- Frontend Bearer token selection (ID token with the org claim).
- Regression test pinning issuer resolution from `COGNITO_ISSUER_URI`.

## Not applicable / out of scope here

- Billing, multi-org onboarding, RBAC — roadmap, not hackathon scope.
