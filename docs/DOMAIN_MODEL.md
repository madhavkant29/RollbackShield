# Domain Model

Maps directly to `backend/src/main/java/com/rollbackshield/*/domain/`.
Every concept here is a real class, not aspirational.

## Core aggregates

- **Organization** (`catalog.domain.Organization`) — a tenant. Every other
  object belongs to exactly one.
- **AppService** (`catalog.domain.AppService`) — a protected application
  or service (named `AppService` in code, not `Service`, to avoid clashing
  with Spring's `@Service` stereotype in files that import both).
- **Release** (`release.domain.Release`) — one v(previous)→v(candidate)
  rollout attempt. Carries the explicit `ReleaseState` machine and a
  monotonic `epoch`, bumped each time the release enters
  `PROTECTED_ROLLOUT`. See `RELEASE_LIFECYCLE.md`.
- **RollbackContract** (`contract.domain.RollbackContract`) — a versioned,
  immutable-per-version bundle of `CompatibilityRule`s plus a rollback
  window, tied to one release. See `ROLLBACK_CONTRACT.md`.
- **WorkJob** (`workfence.domain.WorkJob`) — one unit of async work,
  tagged with the release epoch active when it was created. See
  `docs/features/WORK_FENCING.md`.
- **AuditEvent** (`audit.AuditEvent`) — one append-only, immutable fact
  about something that happened. No update/delete path exists.
- **ReversibilityReport** (`reversibility.domain.ReversibilityReport`) —
  not stored; computed fresh on every request from current release +
  contract state. See `docs/features/REVERSIBILITY_STATUS.md`.

## Value objects

- `OrganizationId`, `ServiceId`, `ReleaseId`, `ContractId` — typed UUID
  wrappers (`shared.domain`), never bare `String` in a method signature.
- `PolicyVersion` — a monotonic long, used by the SDK's cache to detect
  staleness.
- `CompatibilityRule` — a sealed interface with five variants
  (`EnumAllowedValues`, `Nullability`, `NumericRange`, `RequiredField`,
  `ForbiddenValue`). Deterministic only; nothing here is a probability or
  a score.

## What's a documented interface, not yet a full implementation

Per the hackathon freeze rule, these exist only as forward-looking names
in docs/ROADMAP, not as classes: `ConsumerLease`, `ReplayCertification`,
`ArtifactReference`, `DeploymentIntegration`, `ExternalCloudConnection`.
