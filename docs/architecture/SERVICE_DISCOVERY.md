# Service discovery

## Principle

Nothing about a customer's services is invented. Discovery records exactly
what a provider returned (`DiscoveredResource` with external id, metadata,
timestamp); import is an explicit operator action that creates the
RollbackShield service and its initial mapping.

## Flow

```
Integration (AWS)
   -> IntegrationSyncService
        -> ECS provider  -> RUNTIME_CLUSTER, RUNTIME_SERVICE
        -> ECR provider  -> ARTIFACT_REPOSITORY
        -> SQS provider  -> QUEUE
        -> EventBridge   -> EVENT_BUS
        -> CloudWatch    -> LOG_GROUP
   -> persisted per integration (replaced atomically on full success)
```

A partial sync is a failed sync: previous resources are kept and every
provider error is reported. The Integrations UI shows discovered counts
from these rows, never a stored counter.

## Import

`POST /api/v1/services/import` (or the UI's "Import as service") turns one
importable runtime (`RUNTIME_SERVICE`, `KUBERNETES_DEPLOYMENT`) into an
`AppService` plus a `ServiceMapping`:

- runtime binding: HIGH confidence, evidence records the import action,
- artifact repository binding: HIGH when the runtime's image reference
  resolves to a discovered repository,
- repository binding: HIGH when a deployment file references the service;
  MEDIUM ("confirm this mapping") when only the repository name matches,
- migration source binding: HIGH when the repository contains Flyway
  migration files or Liquibase changelogs.

Queues, event buses and databases are never guessed; they are bound when
discovered-and-confirmed by an operator through the mapping API.

## From observation to release (the automatic workflow)

After import, the normal path is a single operation — observe the runtime:

```
POST /api/v1/services/{serviceId}/observations
```

which performs the whole flow:

1. read candidate and previous revisions from the connected runtime,
2. resolve tag-only images to immutable digests through ECR,
3. record the running source commit (only from a HIGH-confidence
   repository binding),
4. create the release (or reuse it when the candidate revision is
   unchanged) with the observed version labels,
5. advance the release to READY — the deployment already happened, so the
   operator's next decision is protect-or-commit,
6. run the first preflight and record `RELEASE_PREFLIGHT_EVALUATED` with
   the verdict, plus a `ReleasePreflightEvaluated` domain event.

Protection (the rollback window) activates when a rollback contract is
created for a READY release — from the UI, the contracts API, or the
GitHub Actions `rollbackshield-protect` action. Rule sets are not
invented; activation stays an explicit step.

Manual creation (`POST /api/v1/releases`) and the explicit
`POST /api/v1/services/{id}/releases` remain for local development and
unsupported systems.

## Kubernetes

Kubernetes deployments are discovered the same way (type
`KUBERNETES_DEPLOYMENT`, external id `namespace/name`) and import through
the same endpoint.

## Voice of the data

Each discovered resource carries the provider vocabulary
(cluster/service, repository URI, queue URL, log group ARN). The UI shows
these strings verbatim so operators can match them against their cloud
console without translation.
