# Scalability

## Current state: not scaled, but no longer blocked
`desiredCount: 1` in `control-plane-stack.ts` is now a default, not a hard
requirement. The work-fence epoch registry and redemption ledger are
DynamoDB-backed (ADR-005), so two tasks cannot disagree about whether a
job's epoch is valid. No Application Auto Scaling policy is configured
yet, so scaling out is still a manual `desiredCount` change (and is
unverified in a live account — see LIMITATIONS.md).

## Path to scaling out
1. (Done) `EpochRegistry` + `RedemptionLedger` are DynamoDB-backed; the
   first redemption is a conditional `PutItem` (`attribute_not_exists(pk)`)
   with first-writer-wins semantics.
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
