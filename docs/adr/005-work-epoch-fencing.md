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
`WorkFenceApplicationService` holds an `EpochRegistry` (invalidated
atomically on rollback) and a redemption ledger
(`ConcurrentHashMap<jobId, RedeemOutcome>`) where the *first* `redeem()`
call for a given `jobId` decides `EXECUTE`/`CANCEL` based on epoch
validity at that instant, and every subsequent call for the same `jobId`
-- regardless of how many, regardless of any later epoch change --
observes that same committed outcome. This was proven directly (Phase C/D
demo): delivering the same job twice always produces
`EXECUTE`-then-`EXECUTE-cached` or `CANCELLED`-then-`CANCELLED-cached`,
never two real executions.

## Consequences
- The queue transport's delivery guarantees (at-least-once) don't need to
  be exactly-once for correctness -- the redemption ledger absorbs
  duplicates.
- The DynamoDB-backed version of this ledger (not yet built -- currently
  in-memory inside `WorkFenceApplicationService`, which is correct for a
  single backend instance but won't survive a restart or scale past one
  task) needs the same atomicity via a DynamoDB conditional put
  (`attribute_not_exists(pk)`) before this is safe to run with
  `desiredCount > 1`. Tracked in `docs/product/ROADMAP.md` /
  `docs/product/LIMITATIONS.md`.
