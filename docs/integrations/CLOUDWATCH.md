# CloudWatch Logs connector

`connectors/aws/adapter/CloudWatchLogAdapter` — capability
`LOG_DISCOVERY`.

- Discovery: `DescribeLogGroups` → `LOG_GROUP` resources.
- Evidence: `recentErrors(logGroup, since, limit)` runs a bounded
  `FilterLogEvents` with the pattern `?ERROR ?Error ?error ?Exception
  ?exception`, hard-capped at 100 events, each truncated to 1000
  characters.

This connector exists to surface operational evidence (deployment/runtime
errors, rollback execution evidence) around a release. It is explicitly
not a log-ingestion pipeline, and nothing waits on it synchronously.

Verified against LocalStack: creates a log group and stream, writes an
ERROR event, asserts discovery and evidence retrieval
(`AwsConnectorsLocalStackIntegrationTest`).
