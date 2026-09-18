# ADR 003: Local policy evaluation in the SDK, not a remote call per mutation

## Status
Accepted

## Context
Every protected mutation in a real application needs a compatibility
decision. If that decision required a network round-trip to the control
plane on every call, RollbackShield would add latency, a new availability
dependency, and cost to every write path it protects -- unacceptable for
what's supposed to be invisible infrastructure (§9, §10 of the product
spec).

## Decision
The Java SDK (`sdk-java/`) fetches a `PolicySnapshot` from the control
plane's `GET /api/v1/contracts/{contractId}/policy` endpoint on a
background schedule (`PolicyCache.refresh()`), and `RollbackGuard.evaluate()`
always reads the already-cached snapshot -- zero network calls on the
mutation hot path. The SDK is deliberately a separate Maven artifact with
zero runtime dependencies (not even Jackson -- see `MinimalJson`), sharing
only a JSON wire format with the backend's `contract.domain.CompatibilityRule`
hierarchy, not Java classes. This was verified end-to-end: `HttpPolicySource`
fetching from a real HTTP server, feeding a real `PolicyCache` and
`RollbackGuard`, correctly blocking/allowing mutations based on the fetched
policy.

## Consequences
- A stale or unreachable control plane degrades enforcement
  predictably (`PolicyState.STALE/EXPIRED/MISSING` +
  `EnforcementConfig.FailureBehavior`), never silently.
- The SDK can be embedded in any JVM application without pulling in
  Spring, AWS SDK, or any other dependency tree.
- Cost: the SDK owns its own (small) JSON parser rather than reusing a
  library. Scoped deliberately narrow -- see `MinimalJson`'s own doc
  comment for when to revisit this.
