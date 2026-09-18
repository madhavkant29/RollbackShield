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
`WorkJob`, `RedeemOutcome` (`EXECUTE`/`CANCEL`), and three ports:
`EpochRegistry`, `RedemptionLedger`, `WorkQueue`. Adapters:
`InMemoryEpochRegistry` / `InMemoryRedemptionLedger` / `InMemoryWorkQueue`
(local dev) and `DynamoDbEpochRegistry` / `DynamoDbRedemptionLedger` /
`SqsWorkQueueAdapter` (`aws`).

## The atomicity that makes this safe under at-least-once delivery
`WorkFenceApplicationService.redeem()` computes a candidate outcome from
epoch validity, then calls `RedemptionLedger.commitIfAbsent(jobId, ...)`:
the *first* committed outcome for a job is final, and every later attempt
for the same `jobId` — regardless of any epoch change in between —
observes it. In local dev that is `ConcurrentHashMap.putIfAbsent`; under
`aws` it is a DynamoDB conditional put (`attribute_not_exists(pk)`) whose
losing concurrent writer re-reads the winner's outcome, so two backend
tasks cannot both decide. See ADR-005.

## Consistency
The fence is durable and shared under `aws`: epoch invalidation survives a
restart and is visible to every backend task, so this is no longer a
reason to pin `desiredCount: 1` (verified against DynamoDB Local,
including concurrent redemptions).

## Failure cases
- Worker crashes after redeeming `EXECUTE` but before performing the side
  effect → the job is "spent" but never executed. This is a deliberate
  at-most-once tradeoff: for an irreversible side effect (e.g. a refund),
  skipping once is safer than risking a duplicate. Recovery is
  operational — re-enqueue a new job (new jobId) for the affected order
  once the worker is healthy; a saga/outbox that makes this automatic is
  not built.
- SQS delivers a message the redemption ledger has never seen — handled
  correctly (first-seen decides).

## Security
Only the control plane invalidates epochs (via the rollback endpoint);
nothing else can. The worker endpoints require the shared service
credential — see `docs/architecture/SECURITY_ARCHITECTURE.md`.

## Tests
`WorkFenceApplicationServiceTest` pins the decision rule in isolation
(EXECUTE stays EXECUTE after a later invalidation; CANCEL stays CANCEL),
`ReleaseLifecycleIntegrationTest` covers enqueue → rollback → redeem twice
→ `CANCEL` over HTTP, and `DynamoDbAdapterIntegrationTest` covers the
durable adapters including an 8-thread concurrent-redeem test.

## Future
Once the redemption ledger is DynamoDB-backed, `QUARANTINE` as a third
`RedeemOutcome` (§12's "optional later") becomes worth adding for jobs
whose epoch validity is ambiguous rather than forcing a binary decision.
