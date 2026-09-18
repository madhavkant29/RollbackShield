# Scalability

## Current state: intentionally not scaled
`desiredCount: 1` in `control-plane-stack.ts`. This is a hard requirement
today, not just a default — the work-fence epoch registry and redemption
ledger are in-memory inside the single running instance (see ADR-005,
LIMITATIONS.md). Running more than one task right now would let two
instances disagree about whether a job's epoch is valid.

## Path to scaling out
1. Move `EpochRegistry` + the redemption ledger to DynamoDB, using a
   conditional `PutItem` (`attribute_not_exists(pk)`) for the same
   first-writer-wins semantics `ConcurrentHashMap.computeIfAbsent`
   currently gives for free in one process.
2. Bump `desiredCount` and add an Application Auto Scaling policy on
   CPU/request count.
3. Everything else (Release, Contract, Organization, AppService) is
   already DynamoDB-backed and stateless per-request — no code change
   needed for those to scale horizontally.

## DynamoDB
`PAY_PER_REQUEST` billing — scales automatically with load, no capacity
planning for v0.1's traffic profile.

## SDK side
`PolicyCache` is per-process, in-memory, lock-free reads — scales with
the protected application's own instance count for free; each instance
independently refreshes from the control plane on its own schedule.
