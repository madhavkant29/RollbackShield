# ADR 005: Epoch-based work fencing with idempotent redemption

## Status
Accepted

## Context
Async work created during a protected rollout (e.g. a refund webhook job)
must not perform its irreversible side effect after the release that
created it has been rolled back -- and the queue delivering that work
(SQS in AWS, in-memory locally) may deliver a message more than once
(§12).

## Decision
Every `WorkJob` carries the `releaseEpoch` active when it was created.
`WorkFenceApplicationService` depends on two ports: `EpochRegistry`
(invalidated atomically on rollback) and `RedemptionLedger`. The *first*
`redeem()` call for a given `jobId` decides `EXECUTE`/`CANCEL` based on
epoch validity at that instant, commits it, and every subsequent call for
the same `jobId` -- regardless of how many, regardless of any later epoch
change -- observes that same committed outcome. Delivering the same job
twice always produces `EXECUTE`-then-`EXECUTE-cached` or
`CANCEL`-then-`CANCEL-cached`, never two real executions.

Local dev uses in-memory adapters (`InMemoryEpochRegistry`,
`InMemoryRedemptionLedger`). Under `aws` the adapters are DynamoDB-backed:
invalidation is a durable `PutItem`, and the first redemption is a
conditional put (`attribute_not_exists(pk)`) whose losing concurrent
writer re-reads and returns the winner's outcome.

## Consequences
- The queue transport's delivery guarantees (at-least-once) don't need to
  be exactly-once for correctness -- the redemption ledger absorbs
  duplicates.
- The fence is now durable and shared: invalidation survives restarts and
  is visible to every backend task, so `desiredCount > 1` no longer needs
  a pinned single instance for correctness. Verified against DynamoDB
  Local in `DynamoDbAdapterIntegrationTest`, including an 8-thread
  concurrent-redeem test that asserts exactly one committed outcome.
- The commit-then-execute ordering gap (a worker that crashes after
  redeeming `EXECUTE` but before performing the side effect) is still
  open and tracked in `docs/features/WORK_FENCING.md`, not solved here.
