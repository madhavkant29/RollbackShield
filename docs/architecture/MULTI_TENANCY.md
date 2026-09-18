# Multi-Tenancy

Every aggregate carries `organizationId` (`Organization`, `AppService`,
`Release`; `RollbackContract`/`AuditEvent` inherit it transitively via
their release). Tenant scope always comes from `CurrentPrincipal.get()
.organizationId()` (sourced from the JWT/local-dev filter), never from a
path or body parameter — see `SECURITY_ARCHITECTURE.md`.

Proven by `TenantIsolationTest`: org A cannot read org B's services or
releases through the application-service layer every controller goes
through.

DynamoDB: no cross-tenant query is possible by construction — every key
either starts with the entity's own id (direct lookup, already
org-checked in code before the read) or an explicit `ORG#`/`SERVICE#`
prefix (the GSI patterns), never a table-wide Scan.
