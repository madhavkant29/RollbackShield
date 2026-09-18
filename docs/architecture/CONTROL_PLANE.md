# Control Plane

The Spring Boot app in `backend/`. Owns: organizations/services, release
lifecycle, rollback contracts, work-fence epoch registry + redemption
ledger, reversibility computation, audit trail. Runs on ECS Fargate
(ADR-002), one task by default.

## Request flow
`api/` controllers → `application/` services (transactions, orchestration,
audit/event emission) → `domain/` (pure logic, state machines, value
objects) and `adapter/` (persistence, queue, event-bus implementations of
`domain/`-defined ports). Controllers never touch a repository directly.

## What it is not
Not on the mutation hot path — see `POLICY_DISTRIBUTION.md`. The control
plane can be down for seconds-to-minutes without blocking a single
protected write in the applications it governs; it only degrades their
policy freshness (`STALE`/`EXPIRED`), handled explicitly by
`EnforcementConfig`.

## Persistence modes
`rollbackshield.persistence`: `in-memory` (default, `local` profile) or
`dynamodb` (`aws` profile) — same ports, swapped adapters, selected via
`@ConditionalOnProperty`. See `DATA_MODEL.md`.
