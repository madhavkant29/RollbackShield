# ADR 004: DynamoDB single-table design, JSON column for contract rules

## Status
Accepted

## Context
Access patterns (`docs/architecture/DATA_MODEL.md`) are all
key-based lookups or narrow range queries -- get release by id, list
releases for a service, get active contract for a release, list audit
events for a release. None need a Scan. A single DynamoDB table with one
GSI covers every pattern in the P0 scope.

The one modeling wrinkle is `RollbackContract.rules`: a sealed hierarchy
of five rule types. Modeling each as native DynamoDB attributes (a map
per rule type, or a `List<Map>` with per-type attribute converters) is
real work with limited payoff before the rule set stabilizes.

## Decision
- Single table `rollbackshield`, `pk`/`sk` as documented in each
  `*DynamoDbItem` class's Javadoc, one GSI (`gsi1`) for the two
  organization/service-scoped listing patterns.
- Audit events are stored under the *same partition* as their release
  (`pk = RELEASE#<releaseId>`, `sk = AUDIT#<epochMillis>#<eventId>`) so
  listing a release's audit trail, in order, is one Query.
- Contract rules are serialized to JSON (via the same flat `RuleDto`
  shape the REST API and SDK already share) and stored as a single
  string attribute (`rulesJson`), not modeled natively.

## Consequences
- Adding a sixth rule type only touches `CompatibilityRule`, `RuleDto`,
  and the two `toDomainRule`/`toDto` mapping functions -- no DynamoDB
  schema migration.
- The `rulesJson` column isn't independently queryable (e.g. "find all
  contracts with an ENUM_ALLOWED_VALUES rule on Order.status") --
  acceptable for v0.1, since no current feature needs that; revisit if
  one does.
