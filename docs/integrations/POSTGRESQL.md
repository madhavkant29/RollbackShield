# PostgreSQL connector

`connectors/postgres/adapter/PostgresDatabaseAdapter` — capability
`DATABASE_DISCOVERY`. Read-only: only `information_schema` is queried.
RollbackShield is not a migration tool and never writes to a customer
database.

- Credential: `POSTGRES_PASSWORD` (secret reference). Configuration:
  `jdbcUrl` (or the integration endpoint), `username`.
- Discovery: the database itself (name, user, version) plus up to 200
  tables/views with column counts; `discoverSchemaObjects` returns full
  column metadata (name, type, nullability) used as evidence for schema
  compatibility questions.
- Connection timeouts are bounded (10s connect, 30s socket, 15s query).
- The schema objects are bound to a service as a `DATABASE` binding
  (operator-confirmed) and are what a "does the rollback target's expected
  column still exist?" question can be answered from. Migration *content*
  analysis is the Flyway connector's job (see `FLYWAY.md`); PostgreSQL
  itself is never a migration runner here.

Service mapping binds a `DATABASE` binding when an operator confirms it
(discovery does not guess which database a service uses).

Verified against a real PostgreSQL 16 container (Testcontainers):
discovers the database, tables with correct column counts, view kind, and
column nullability (`PostgresDatabaseAdapterIntegrationTest`).
