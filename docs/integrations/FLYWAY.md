# Flyway / migration analysis

`connectors/flyway/domain/MigrationAnalyzer` (deterministic classification)
and `connectors/flyway/adapter/FlywayMigrationAnalyzer` (capability
`DATABASE_MIGRATION_ANALYSIS`).

The analyzer is conservative by construction: a statement it cannot
positively recognize becomes `REQUIRES_REVIEW` and is listed in
`unsupportedStatements`. It is not a SQL parser and does not pretend to
be.

## Classifications

| Statement | Classification |
| --- | --- |
| `CREATE TABLE`, `CREATE INDEX`, `DROP INDEX`, `CREATE EXTENSION` | SAFE |
| `CREATE TYPE` (incl. `AS ENUM` — additive) | SAFE |
| `INSERT INTO` | SAFE |
| `ALTER TABLE ... ADD COLUMN` (nullable or with DEFAULT) | SAFE |
| `ALTER TABLE ... ADD COLUMN ... NOT NULL` (no DEFAULT) | UNSAFE |
| `ALTER TABLE ... DROP COLUMN` | UNSAFE |
| `ALTER TABLE ... RENAME ...` | UNSAFE |
| `ALTER COLUMN ... TYPE ...`, `SET NOT NULL` | UNSAFE |
| `DROP TABLE`, `DROP SCHEMA`, `TRUNCATE`, `DELETE FROM`, `DROP TYPE` | UNSAFE |
| `ALTER TYPE ... RENAME VALUE` / `RENAME TO` / `ALTER ATTRIBUTE` | UNSAFE |
| `ALTER DOMAIN ... NOT NULL` / `DROP CONSTRAINT` | UNSAFE |
| `ALTER TYPE ... ADD VALUE` (previous release may not deserialize it) | REQUIRES_REVIEW |
| `ALTER COLUMN ... DROP NOT NULL` (loosens data shape) | REQUIRES_REVIEW |
| `ALTER TABLE ... ADD/DROP CONSTRAINT`, other `ALTER TABLE`, `UPDATE`, `ALTER DOMAIN` | REQUIRES_REVIEW |
| anything unrecognized | REQUIRES_REVIEW + `unsupportedStatements` |

The worst statement in a file decides the file's classification. Every
finding cites the statement's line inside the file, and the raw statement
text is preserved in the finding/unsupported lists.

## Blocker payload

A destructive candidate migration produces a single, self-explanatory
blocker (this is the exact shape pinned by tests):

```
code: DESTRUCTIVE_DATABASE_MIGRATION
description: V219__remove_legacy_tax_code.sql:
             unsafe: line 1: ALTER TABLE orders DROP COLUMN legacy_tax_code
             -- previous version still depends on this representation;
             the rollback target would be unable to operate
             -- checkout@task-definition:106 would be unable to operate
             (previous version still depends on this representation)
```

with evidence rows:

```
V21957     --destructive-change-->  db/migration/V219__remove_legacy_tax_code.sql   (flyway)
<path>     --breaks------------->   checkout@task-definition:106                    (flyway)
```

## Where the candidate migration set comes from

Preflight does not guess from version numbers. `MigrationCompatibilityService`:

1. reads the two most recent observed commits for the service (recorded
   by deployment observation from the bound repository),
2. asks GitHub for **added** migration files between them,
3. fetches and analyzes exactly those files.

If no baseline exists, the result is UNKNOWN with
`MIGRATION_ANALYSIS_UNAVAILABLE` — never "safe".

Destructive changes produce:

```
blocker: DESTRUCTIVE_DATABASE_MIGRATION   verdict: CANNOT_ROLLBACK
evidence: V219 --destructive-change--> ALTER TABLE customers DROP COLUMN ...
          .../V219__....sql --breaks--> payments@task-definition:1
```

Verified by `MigrationAnalyzerTest` (safe/drop/rename/type/nullability/
enum/unknown/comments/aggregate cases) and by the destructive vs safe
preflight paths in `ConnectedReleaseFlowTest`.
