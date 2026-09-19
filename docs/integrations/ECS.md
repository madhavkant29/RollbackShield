# ECS connector

`connectors/aws/adapter/EcsRuntimeAdapter` — capabilities
`RUNTIME_DISCOVERY`, `DEPLOYMENT_STATUS`, `ROLLBACK_EXECUTION`,
`HEALTH_VERIFICATION`.

## Discovery

`ListClusters`/`ListServices`/`DescribeServices` (batches of 10). Each
service becomes a `RUNTIME_SERVICE` resource with external id
`<cluster>/<service>` and metadata: task definition ARN, resolved image,
desired/running counts, status.

## Deployment identity

`observeDeployment` reads `DescribeServices` and produces:

- candidate revision: `service.taskDefinition` (exact ARN),
- previous revision: the non-candidate deployment with the highest
  revision below the candidate; if the service reports only one
  deployment, the task-definition family list is consulted,
- candidate/previous artifact digests: a pinned `image@sha256:...` is used
  directly; a tag-only reference is resolved through ECR `DescribeImages`
  at observation time, because a mutable tag alone is not artifact
  identity. If the repository/tag is not visible to the role, the digest
  stays `null` and preflight reports `ARTIFACT_IDENTITY_UNKNOWN` rather
  than guessing,
- artifact repository: registry/repository parsed from the image.

`DescribeTaskDefinition` resolves the previous revision's image. This is
one extra call, executed only when a previous revision exists.

## Rollback execution

`requestRollback` calls `UpdateService` with the exact previous
task-definition ARN. Idempotent: if the service already runs the target,
it completes immediately without an API call. `monitorRollback` waits for
the primary deployment `rolloutState`:
`COMPLETED` → completed, `FAILED` → failed (with ECS's reason), otherwise
in progress. The orchestrator polls with a configurable timeout
(`rollbackshield.rollback.monitor-timeout-seconds`, default 600s).

Health: desired vs running vs pending, rollout state. `desired=0` reports
UNKNOWN (you cannot verify health of a scaled-to-zero service);
`running=0, desired>0` is UNHEALTHY; below desired or IN_PROGRESS is
DEGRADED.

## IAM

See `infrastructure/lib/control-plane-stack.ts`:
`ObserveEcsRuntimes` (describe/list) and `ExecuteControlledRollback`
(`ecs:UpdateService` on `service/*/*` only).

## Verification status

Contract tests pin discovery mapping, candidate/previous extraction
(both from deployments and from the family fallback), idempotent and
explicit rollback calls, monitor state mapping and health mapping. Live
ECS verification is pending (`docs/operations/AWS_DEPLOYMENT.md`).
