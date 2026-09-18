# Feature: Work Fencing

## Problem
Async work a candidate release queues (a refund webhook, a notification)
may still execute after the release has been rolled back, causing
production to keep changing after "rollback" completed.

## User workflow
1. The candidate application calls `POST /releases/{id}/work` to enqueue
   a job; the control plane stamps it with the release's *current epoch*
   and hands it to the `WorkQueue` (in-memory locally, SQS in AWS).
2. A worker process (`demo-worker`, or your own) polls
   `GET /work/poll`, and before performing the job's irreversible side
   effect, calls `POST /work/{jobId}/redeem` with the job's
   `releaseId`/`releaseEpoch`.
3. On rollback, `POST /releases/{id}/rollback` calls
   `WorkFenceApplicationService.invalidateEpoch()`, which is what makes
   subsequent redemptions for that epoch return `CANCEL`.

## Domain objects
`WorkJob`, `EpochRegistry`, `RedeemOutcome` (`EXECUTE`/`CANCEL`),
`WorkQueue` port (`InMemoryWorkQueue` / `SqsWorkQueueAdapter`).

## The atomicity that makes this safe under at-least-once delivery
`WorkFenceApplicationService.redeem()` uses
`ConcurrentHashMap.computeIfAbsent()` keyed by `jobId`: the *first*
redemption attempt for a job decides `EXECUTE`/`CANCEL` based on epoch
validity at that instant; every later attempt for the same `jobId` —
regardless of any epoch change in between — observes that same committed
outcome. Proven directly in the Phase C/D demo: delivering the same job
twice never produces two real executions. See ADR-005.

## Consistency
**Known gap** (see LIMITATIONS.md): the redemption ledger and
`EpochRegistry` are currently in-memory inside a single
`WorkFenceApplicationService` instance — correct for `desiredCount: 1`,
not yet safe for more than one backend task or a restart. Needs a
DynamoDB conditional-write version before scaling out.

## Failure cases
- Worker crashes after redeeming but before performing the side effect →
  the job is "spent" (marked `EXECUTE`) but never actually executed. Not
  yet handled — a real implementation needs the worker to only mark
  redemption *after* the side effect succeeds, or a saga/outbox pattern.
  Tracked in LIMITATIONS.md as a gap, not silently ignored.
- SQS delivers a message the redemption ledger has never seen — handled
  correctly (first-seen decides).

## Security
Only the control plane invalidates epochs (via the rollback endpoint);
nothing else can.

## Tests
`WorkFenceApplicationService` is covered indirectly through
`ReleaseLifecycleIntegrationTest`'s full rollback scenario (enqueue →
rollback → redeem twice → `CANCEL` both times). No isolated unit test for
`WorkFenceApplicationService` yet — worth adding.

## Future
Once the redemption ledger is DynamoDB-backed, `QUARANTINE` as a third
`RedeemOutcome` (§12's "optional later") becomes worth adding for jobs
whose epoch validity is ambiguous rather than forcing a binary decision.
