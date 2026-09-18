# Feature: Data Compatibility Enforcement

## Problem
A candidate release can write data the previous release's code doesn't
know how to handle. Rolling compute back doesn't undo the write.

## User workflow
1. Operator defines a `RollbackContract` with `CompatibilityRule`s when
   activating protection for a release (`POST /releases/{id}/contracts`).
2. The candidate application embeds the Java SDK, wraps every protected
   mutation in `RollbackGuard.evaluate(MutationRequest)`, and branches on
   the `MutationDecision` — a `BLOCK` means the caller must not perform
   the write.
3. The SDK's decision is entirely local (`ContractEvaluator`, a pure
   function over an already-fetched `PolicySnapshot`) — no network call
   per mutation.

## Domain objects
`CompatibilityRule` (sealed: `EnumAllowedValues`, `Nullability`,
`NumericRange`, `RequiredField`, `ForbiddenValue`), `MutationRequest`,
`MutationDecision` (`ALLOW`/`BLOCK`/`UNKNOWN` + `ReasonCode`).

## APIs
`POST /releases/{id}/contracts` (define rules), `GET
/contracts/{id}/policy` (SDK fetches).

## State transitions
None directly — this feature gates whether a release's reversibility
report shows the "Data compatibility" check as `PASS`.

## Consistency
The SDK's cached policy can be stale relative to the latest contract
version; see `docs/architecture/POLICY_DISTRIBUTION.md` for freshness
states and fail-open/fail-closed behavior.

## Failure cases
- Policy `MISSING`/`EXPIRED`/`INVALID` at evaluation time →
  `EnforcementConfig.FailureBehavior` decides `BLOCK` (fail-closed) or
  `UNKNOWN` (fail-open), never a silent `ALLOW`.
- Malformed rule in the contract creation request → `400
  VALIDATION_FAILED` or, for an unrecognized `type`, `409
  INVALID_ROLLBACK_CONTRACT`.

## Security
Rules are only ever created by an authenticated, org-scoped caller
through the control plane; the SDK never writes rules, only reads them.

## Latency
Verified via a real local `HttpServer` in Phase F: fetch + parse +
evaluate completes well under the aspirational p50 < 1ms / p95 < 3ms
target for the evaluation step itself (fetch is a one-time background
cost, not counted against the hot path). No JMH benchmark has been run
yet — see LIMITATIONS.md.

## Observability
`rollbackshield.policy.allow.count` / `.block.count` metrics are named in
`docs/architecture/OBSERVABILITY.md` but not yet wired to Micrometer.

## Tests
`ContractEvaluatorTest` (all five rule types, both directions),
`RollbackGuardTest` (fail-open/closed, last-known-good retention,
telemetry buffer never blocking).

## Limitations / future
`OBJECT_VERSION` rule type (contract §8's "if time permits") not built.
The SDK doesn't yet report `MutationBlocked` back to the control plane as
telemetry — see LIMITATIONS.md.
