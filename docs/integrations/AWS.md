# AWS connector

`connectors/aws/adapter/AwsConnector` + capability providers for ECS, ECR,
SQS, EventBridge and CloudWatch Logs. All AWS SDK usage is confined to
`connectors/aws/adapter/`.

## Connecting

| Mode | Credential kind | Use |
| --- | --- | --- |
| Control-plane role | `AWS_CONTROL_PLANE_ROLE` | Hackathon/dev: observe the account RollbackShield itself runs in via the task role / local profile. No keys. |
| Customer AssumeRole | `AWS_ASSUME_ROLE` + role ARN + external id | Production path. `AwsClients` builds an `StsAssumeRoleCredentialsProvider`; the session auto-refreshes. Static customer keys are never accepted. |

Configuration keys: `region` (or the integration endpoint), optional
`endpointOverride` (used by LocalStack tests), optional
`expectedAccountId` (connection test fails on account mismatch -- the
guard against assuming the wrong role).

Connection test: STS `GetCallerIdentity`. Details returned: account, ARN,
user id, region.

## Client lifecycle

`AwsClients` caches clients per `(client type, integration id, region,
credential fingerprint, endpoint override)` so calls reuse HTTP connection
pools. Assumed-role providers are cached per role/external id. This is a
background path: discovery/preflight never sit inside an application
mutation.

## Discovery (`IntegrationSyncService`)

- ECS: clusters and services (batched `DescribeServices`), each with task
  definition, image, desired/running counts, status.
- ECR: repositories with URI, tag mutability, scan-on-push.
- SQS: queues with approximate message counts, visibility timeout,
  redrive presence. Attribute lookups are capped at 50 per sync and the
  cap is recorded in metadata.
- EventBridge: event buses.
- CloudWatch Logs: log groups. Error evidence is a bounded
  `FilterLogEvents` query (max 100 events), never log ingestion.

## Verification status

STS/SQS/Logs/EventBridge are exercised against LocalStack
(`AwsConnectorsLocalStackIntegrationTest`). ECS/ECR are LocalStack **Pro**
features, so those adapters are SDK contract tests plus the fake-connector
end-to-end flow; live verification is `docs/operations/AWS_DEPLOYMENT.md`
step 6. Do not describe ECS/ECR as live-verified before then.
