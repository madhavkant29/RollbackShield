# ADR 001: Modular monolith, not microservices

## Status
Accepted

## Context
RollbackShield's P0 scope spans release lifecycle, contracts, enforcement,
work fencing, reversibility, audit, identity, and organization concerns.
Splitting these into separately-deployed services would multiply
operational surface (more ECS services, more inter-service auth, more
places for a hackathon deadline to break something) for no benefit at this
stage -- nothing here has independent scaling requirements yet.

## Decision
One Spring Boot application (`backend/`), organized into packages by
business capability (`release/`, `contract/`, `enforcement/`,
`workfence/`, `reversibility/`, `audit/`, `identity/`, `organization`
(as `catalog/`), `shared/`), each with `domain/application/adapter/api`
sub-packages. Cross-module calls go through application-layer services
(e.g. `ContractApplicationService` calls `ReleaseApplicationService`,
never touches `ReleaseRepository` directly), not through HTTP.

## Consequences
- Single deployable, single ECS service, single set of logs -- easy to
  operate for a hackathon and for the near-term roadmap.
- Module boundaries are enforced by convention and code review today; an
  ArchUnit test (`docs/development/TESTING_STRATEGY.md`) is the next step
  to make them enforced by the build instead.
- If a module later needs independent scaling or a separate team
  boundary, its `domain`/`application` packages can be extracted into a
  new Maven module (as `sdk-java` already is) with minimal churn, because
  they don't import Spring or AWS types.
