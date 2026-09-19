# SQS connector and async work fencing

`connectors/aws/adapter/SqsQueueAdapter` — capability `QUEUE_DISCOVERY`.
Queue discovery lists queues and samples attributes (approximate counts,
visibility timeout, redrive presence), capped at 50 attribute lookups per
sync with the cap recorded in metadata.

## Where fencing actually happens

Epoch fencing is enforced by the control plane's work-fence
(`workfence/`, ADR-005), not vendor-specifically by the SQS connector:

- candidate work is enqueued as a `WorkJob` carrying
  `releaseId` + `releaseEpoch`;
- `GET /work/poll` hands the job to the worker;
- `POST /work/{jobId}/redeem` validates and redeems the epoch **before**
  any irreversible side effect;
- rollback invalidates the candidate epoch, so any later redeem of
  candidate work returns `CANCEL`;
- redemption is a durable ledger, so duplicate SQS delivery cannot execute
  the effect twice (concurrent-redemption test in
  `DynamoDbAdapterIntegrationTest`);
- the known tradeoff is at-most-once for a worker that crashes between
  redeem and effect (see `docs/features/WORK_FENCING.md`).

`QUEUE_FENCING` remains a reserved capability declared by no provider —
fencing is real, but it lives in the work-fence module rather than in a
vendor adapter, and the capability table says so.

SQS wire-format round-trip is verified against LocalStack
(`AwsAdapterLocalStackIntegrationTest`).
