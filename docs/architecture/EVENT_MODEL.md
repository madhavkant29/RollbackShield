# Event Model

Published via `EventPublisher` (`LoggingEventPublisher` locally,
`EventBridgeEventPublisher` in `aws`) — best-effort telemetry, never the
source of truth (the audit trail is). A publish failure is caught and
logged, never fails the triggering request.

## Events emitted today
`ReleaseCreated`, `RollbackProtectionActivated`, `RollbackStarted`,
`WorkEpochInvalidated`, `ReleaseRolledBack`, `ReleaseCommitStarted`,
`ReleaseCommitted` — all `eventVersion: 1`, shape:
```json
{"eventType","eventVersion","eventId","occurredAt","organizationId","releaseId","payload"}
```

## Not yet emitted
`MutationBlocked`, `RollbackRiskDetected` — these would come from the
SDK's telemetry, which isn't ingested by the control plane yet (see
LIMITATIONS.md).

## Consumer contract
No consumers exist yet in v0.1 (EventBridge bus has no rules/targets
wired). Any future consumer must tolerate duplicates — nothing guarantees
exactly-once past `PutEvents`.
