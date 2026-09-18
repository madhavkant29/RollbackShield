# Feature: Reversibility Status

## Problem
"Is this release still safe to roll back" needs to be an answerable,
evidence-backed question at any point during a rollout — not a fake
percentage, not a guess.

## User workflow
`GET /releases/{id}/reversibility` — computed fresh on every request by
`ReversibilityApplicationService.evaluate()`, never cached, never stored.

## Domain objects
`ReversibilityStatus` (`REVERSIBLE`/`AT_RISK`/`COMMITTED`/`UNKNOWN`),
`ReversibilityCheck` (name + pass/fail + `ReversibilityBlocker`),
`ReversibilityReport`, `ReversibilityEvaluator` (pure function: release
state + checks → status, no scoring).

## The three checks (v0.1)
1. **Data compatibility** — `PASS` if an active contract protects the
   release (enforcement guarantees no `BLOCK`ed write ever reached
   persistence, so "protected" implies "compatible so far").
2. **Queued work fencing** — `PASS` if the active contract's
   `candidateEpochRequiredForAsyncWork` is true.
3. **Policy freshness** — `PASS` if the contract is still within its
   `rollbackWindow`, or the release has already rolled back/committed
   (window is moot at that point).

No active contract at all → all three checks `FAIL` with
`NO_ACTIVE_CONTRACT`, status `AT_RISK` (or `UNKNOWN` if the release
hasn't reached a state with any checks yet, e.g. still `DRAFT`).

## Status derivation
`COMMITTED` release state always yields `COMMITTED` status, regardless of
checks (the rollback window is closed by definition — see
`ReversibilityEvaluatorTest`). Otherwise: no checks yet → `UNKNOWN`; all
pass → `REVERSIBLE`; any fail → `AT_RISK`.

## Consistency
Always reads current repository state at request time — there's no
staleness to reason about here (unlike the SDK's own policy cache, which
this feature doesn't touch).

## Failure cases
If the release doesn't exist: `404 RELEASE_NOT_FOUND`. No other failure
mode — this endpoint never throws for "checks failed", only for "release
doesn't exist".

## Security
Scoped by organization: the controller calls
`ReleaseApplicationService.get(releaseId, callerOrg)` first, which throws
a `404` (never a `403` that would confirm the release exists) if the
release belongs to a different organization — same pattern as every other
per-release endpoint (§30/§37).

## Tests
`ReversibilityEvaluatorTest` covers all four status outcomes at the pure
domain-function level. `ReversibilityApplicationService`'s check assembly
(reading real contract state) is exercised indirectly through
`ReleaseLifecycleIntegrationTest`, not in isolation yet.

## Future
More checks as new rule types are added (e.g. a future `OBJECT_VERSION`
rule would likely add a "schema version compatibility" check).
