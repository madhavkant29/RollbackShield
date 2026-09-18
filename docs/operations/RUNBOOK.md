# Runbook

## Service won't start (ECS task keeps restarting)
```
aws ecs describe-services --cluster rollbackshield --services <service-name> --profile <profile> --region <region>
aws logs tail /rollbackshield/backend --profile <profile> --region <region> --since 15m
```
Common causes: missing `COGNITO_ISSUER_URI` (task env misconfigured),
DynamoDB table not yet deployed (deploy Data stack before ControlPlane),
image not pushed to ECR (`ResourceInitializationError` in ECS events).

## ALB health check failing
`GET /actuator/health` must return `{"status":"UP"}`. If the task is
running but unhealthy: check security group allows ALB → task on 8080
(`control-plane-stack.ts` wires this; verify it wasn't hand-edited), and
that the app actually bound to `0.0.0.0:8080` (Spring Boot default is
fine).

## Release stuck / can't transition
`GET /api/v1/releases/{id}` to see current `state`. Cross-reference with
`docs/RELEASE_LIFECYCLE.md`'s transition table — an `INVALID_
RELEASE_TRANSITION` error's `details.from`/`details.to` tells you exactly
what was attempted vs. allowed.

## Suspected cross-tenant data leak
This would be a critical bug — see `docs/architecture/SECURITY_
ARCHITECTURE.md` for what's supposed to prevent it
(`TenantIsolationTest`). If reproduced, capture the exact request
(caller's org, target resource id) and treat as P0.

## Rollback isn't fencing work correctly
Check `docs/adr/005-work-epoch-fencing.md` — if running with
`desiredCount > 1`, this is the known gap (in-memory epoch registry not
yet shared across instances). Reduce to `desiredCount: 1` as an immediate
mitigation.

## Rotating a leaked credential
IAM user access key: `aws iam create-access-key` → update local profile
→ `aws iam delete-access-key --access-key-id <old>`. Cognito is
unaffected (JWTs are short-lived and validated per-request, not a shared
secret).
