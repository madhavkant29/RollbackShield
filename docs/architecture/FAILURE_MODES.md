# Failure Modes

| Failure | Behavior |
|---|---|
| Control plane unreachable from SDK | `PolicyCache` retains last-known-good; state degrades FRESH→STALE→EXPIRED by age; `EnforcementConfig.FailureBehavior` decides BLOCK vs UNKNOWN once EXPIRED/MISSING. Mutation evaluation itself never throws or blocks on I/O. |
| Malformed policy response | `PolicyFetchException`, snapshot not replaced, same degrade-by-age path as above. |
| Concurrent release transition (two callers) | `ReleaseRepository.compareAndSave()` fails for the loser → `409 STATE_TRANSITION_FAILED`. Caller retries with fresh state. |
| Duplicate SQS delivery of a work job | `WorkFenceApplicationService.redeem()`'s `computeIfAbsent` makes only the first delivery decide the outcome; every later delivery observes it, never re-executes. |
| Worker crashes after redeem, before side effect | **Not yet handled** — job is marked EXECUTE but the effect may never happen. See LIMITATIONS.md. |
| Backend restart / scale to >1 task | Epoch registry + redemption ledger are in-memory today — state is lost / inconsistent across instances. **Known gap**, see ADR-005 and LIMITATIONS.md. |
| EventBridge publish fails | Caught and logged inside `EventBridgeEventPublisher`; never fails the triggering request (events are telemetry, audit trail is the source of truth). |
| Invalid release state transition requested | `ReleaseTransitionException` (pure domain, no HTTP dependency) → `GlobalExceptionHandler` → `409 INVALID_RELEASE_TRANSITION` with `from`/`to` in `details`. |
| Cross-tenant resource access attempt | `404`, never `403` — existence is never confirmed to an unauthorized caller. |
