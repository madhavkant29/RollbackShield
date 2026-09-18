# Data Model

Single DynamoDB table, `rollbackshield` (ADR-004), one GSI (`gsi1`).

## Keys

| Entity | pk | sk | gsi1pk | gsi1sk |
|---|---|---|---|---|
| Organization | `ORG#<id>` | `ORG#<id>` | — | — |
| AppService | `SERVICEENTITY#<id>` | `SERVICEENTITY#<id>` | `ORG#<orgId>` | `SERVICEENTITY#<id>` |
| Release | `RELEASE#<id>` | `RELEASE#<id>` | `SERVICE#<serviceId>` | `RELEASE#<id>` |
| RollbackContract | `RELEASE#<releaseId>` | `CONTRACT#<id>` | `CONTRACT#<id>` | `CONTRACT#<id>` |
| AuditEvent | `RELEASE#<releaseId>` | `AUDIT#<epochMillis>#<eventId>` | — | — |

## Access patterns → query

| Pattern | Query |
|---|---|
| Get organization | `GetItem` pk=sk=`ORG#id` |
| List services for org | `Query` gsi1 pk=`ORG#id` |
| Get release | `GetItem` pk=sk=`RELEASE#id` |
| List releases for service | `Query` gsi1 pk=`SERVICE#id` |
| Get active contract for release | `Query` pk=`RELEASE#id`, sk begins_with `CONTRACT#`, filter status=ACTIVE |
| Get contract by id | `Query` gsi1 pk=`CONTRACT#id` |
| List audit for release | `Query` pk=`RELEASE#id`, sk begins_with `AUDIT#` (chronological for free) |

No Scan anywhere.

## Concurrency
Release writes use the Enhanced Client's `@DynamoDbVersionAttribute` for
optimistic locking, layered under `ReleaseRepository.compareAndSave()`'s
own expected-state check — two independent guards against the same class
of race (§22).

## Contract rules
Stored as a JSON string (`rulesJson`), not native attributes — a
documented v0.1 simplification (ADR-004), not an oversight.
