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

## Path classification (connectivity layer)

Latency-sensitive (must stay local/fast):

- application mutations → SDK policy cache + deterministic evaluator
  (never a control-plane call);
- work `redeem` → DynamoDB conditional write (bounded, single round trip;
  duplicate delivery is idempotent by ledger key).

Background (must never block a hot path):

- connector sync/discovery (paginated, capped attribute lookups, batched
  `DescribeServices`);
- CloudWatch evidence queries (capped at 100 events);
- Kubernetes/AWS client construction (cached per integration/region/
  credentials);
- domain event publication (best effort, duplicate-tolerant).

User/pipeline-initiated (allowed provider calls, bounded):

- connection tests, deployment observation, preflight (live runtime +
  artifact reads), rollback execution (poll interval 5s, timeout 600s by
  default, configurable via
  `rollbackshield.rollback.monitor-*`).
