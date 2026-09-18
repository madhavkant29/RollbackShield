# Rollback Contract

A `RollbackContract` (`contract.domain.RollbackContract`) is the versioned,
signed-in-effect agreement that a candidate release's writes remain
understandable by the previous release, for as long as the rollback window
is open.

## Lifecycle

`DRAFT → ACTIVE → SUPERSEDED | EXPIRED | REVOKED`. In v0.1, creation and
activation happen atomically in one call
(`POST /releases/{id}/contracts`) — there's no separate "draft, review,
then activate" workflow yet (tracked in ROADMAP as a natural v0.2
addition once contracts need human review before going live).

Activating a contract is the one thing that also drives the release from
`READY` to `PROTECTED_ROLLOUT` — see `ContractApplicationService
.createAndActivate()`, which calls
`ReleaseApplicationService.activateProtectedRollout()` rather than writing
release state directly, keeping the release state machine the single
source of truth for that transition.

## Structure

- `contractId`, `contractVersion`, `policyVersion` — a contract can be
  superseded by a new version; the SDK's `PolicyCache` tracks
  `policyVersion` to only ever move forward.
- `rollbackWindow` (`Duration`) — how long the contract stays `isActive()`
  after `activatedAt`.
- `rules: List<CompatibilityRule>` — see `docs/features/DATA_COMPATIBILITY.md`
  for the five rule types.
- `candidateEpochRequiredForAsyncWork: boolean` — if true, the
  reversibility evaluator requires epoch fencing on this release's async
  work to consider it reversible; see `docs/features/WORK_FENCING.md`.
- `contentHash` — SHA-256 of the rule set, for audit/debugging (does this
  contract's rules match what I think they are).

## Wire format

The one JSON shape both the backend (`ContractDtos.RuleDto`, serialized by
`ContractController.getPolicy()`) and the SDK
(`SdkCompatibilityRule` + hand-rolled parsing in `HttpPolicySource`) agree
on — see `docs/architecture/POLICY_DISTRIBUTION.md` for the full contract
and why it's flat rather than JSON-polymorphic.
