# Enforcement Plane

Lives entirely inside `sdk-java/`, embedded in the protected application's
own process — not a separate deployable, not a sidecar (that's a possible
future SDK variant, not v0.1).

## Components
`PolicySource` (network port; `HttpPolicySource` is the only
implementation) → `PolicyCache` (lock-free reads via `AtomicReference`,
background refresh, retains last-known-good on fetch failure,
classifies `PolicyState`) → `ContractEvaluator` (pure function, indexed
rule lookup, no I/O) → `RollbackGuard` (the public entry point; applies
`EnforcementConfig` mode/fail-behavior, enqueues to `TelemetryBuffer`).

## Why it's local
See ADR-003. Verified end-to-end in Phase F against a real local
`HttpServer`: fetch, parse, cache, evaluate, block/allow — all correct,
zero network calls after the initial fetch.

## Zero dependencies
`sdk-java`'s only runtime deps are the JDK itself — no Jackson, no Spring,
no AWS SDK. `MinimalJson` (a ~150-line, scope-limited JSON reader) is the
deliberate cost of that constraint; see its own doc comment for when to
revisit it.
