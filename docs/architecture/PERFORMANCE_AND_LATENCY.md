# Performance and Latency

## The sacred path
`RollbackGuard.evaluate()` → `PolicyCache.get()` (single
`AtomicReference.get()`) → `ContractEvaluator.evaluate()` (indexed map
lookup + a handful of switch-based rule checks) → `TelemetryBuffer
.enqueue()` (non-blocking `offer()`). No locks beyond the atomic
reference, no reflection, no I/O, no per-call AWS/HTTP client
construction (`HttpPolicySource` builds its `HttpClient` once, in its
constructor).

## What's measured vs. asserted
**Not yet measured**: no JMH benchmark has been written or run (§10 asks
for one). The aspirational targets (p50 < 1ms, p95 < 3ms for a cached
evaluation) are **not claimed as achieved** — only that the design
(pre-indexed snapshots, no I/O on the hot path) is the right shape to hit
them. Verified instead: a real end-to-end run (Phase F) showing the fetch
→ cache → evaluate → decide chain completing correctly over a real HTTP
connection, with no attempt made to characterize its latency
distribution.

## Next step
Add a `sdk-java/src/jmh` benchmark module measuring: cache lookup alone,
evaluation with 1/5/100 rules, telemetry enqueue. Until that exists, treat
the latency section of any demo narrative as a design claim, not a
measured one.
