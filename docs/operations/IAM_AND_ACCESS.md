# IAM and Access

Full setup steps are in `AWS_DEPLOYMENT.md` §0–2; this doc is the
reference summary.

## Human access (deployment)
Preferred: IAM Identity Center, temporary/federated credentials via
`aws sso login`. Fallback: IAM user `rollbackshield-deployer` (MFA
required) that can only `sts:AssumeRole` into
`RollbackShieldDeploymentRole` — the user has no direct permissions of
its own.

## Workload access (runtime)
ECS **execution role**: pulls the image from ECR, writes to CloudWatch
Logs — nothing else (`AmazonECSTaskExecutionRolePolicy`).
ECS **task role**: the backend process's own runtime identity — scoped to
exactly the one DynamoDB table, one EventBridge bus, one SQS queue this
deployment owns (`grantReadWriteData` / `grantPutEventsTo` /
`grantSendMessages`+`grantConsumeMessages` in `control-plane-stack.ts`,
nothing broader).

## Never
Root access keys, static `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` in
any ECS task environment variable, IAM user credentials embedded in code,
`.env`, Docker images, or CI config.

## Rotation
`rollbackshield-deployer`'s access key: every 90 days (create new, update
local profile, delete old). Revoke immediately by deleting the key — the
role's trust policy is useless without it.
