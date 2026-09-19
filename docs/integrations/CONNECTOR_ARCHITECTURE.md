# Connector architecture

## The rule

Core release/reversibility code never imports a provider SDK, and never
branches on a provider name. `backend/src/main/java/com/rollbackshield/integrations/`
defines the domain model; `backend/src/main/java/com/rollbackshield/connectors/<provider>/`
contains the only code allowed to speak to a provider.

Enforced by `ModuleBoundaryTest` (ArchUnit): `domain/` packages must not
depend on Spring, AWS SDK, or Jackson; `application/` packages must not
reach into any `adapter/` package.

```
                       +-------------------------------+
                       |  integrations.domain          |
                       |  Integration, DiscoveredResource,
                       |  ServiceMapping, DeploymentIdentity,
                       |  Connector, CapabilityProvider, ports
                       +---------------+---------------+
                                       |
        +------------------------------+------------------------------+
        |                              |                              |
  connectors/aws                 connectors/github            connectors/kubernetes
  connectors/ecr...              connectors/flyway            connectors/postgres
        |                              |                              |
  (AWS SDK clients)             (GitHub REST over HTTP)      (fabric8 / JDBC)
```

## Model

- **ConnectorType** — a connected system family: `AWS`, `GITHUB`,
  `KUBERNETES`, `POSTGRESQL`, `FLYWAY`. Anything else is rejected with
  `CONNECTOR_NOT_IMPLEMENTED`; the UI never shows a provider that is not
  real.
- **Connector** — one per family. Owns the connection test (a real
  provider call: STS GetCallerIdentity, GitHub API, kubeconfig access,
  JDBC connect, directory read) and declares the family's capabilities.
- **CapabilityProvider** — one per capability implementation inside a
  family. AWS has providers for ECS, ECR, SQS, EventBridge and CloudWatch;
  each declares exactly the capabilities it implements.
- **ConnectorRegistry** — resolves `(ConnectorType, ConnectorCapability)`
  to the provider implementing it and checks the expected port type.
  Missing provider → `CONNECTOR_NOT_IMPLEMENTED`; undeclared capability →
  `CAPABILITY_NOT_SUPPORTED`. There is no provider-name branching anywhere.

## Domain model (integrations)

| Concept | Responsibility |
| --- | --- |
| `Integration` | One configured connection: type, endpoint, credential reference, non-secret configuration, plus the connection and sync aspects below. |
| `IntegrationConnection` | Connection aspect: `ConnectionState` (CONNECTING/CONNECTED/ERROR/DISCONNECTED), `ConnectorHealth`, last connection error. Only a real `ConnectionTestResult` moves it to CONNECTED. |
| `SyncState` | Synchronization aspect: `NEVER_SYNCED/SUCCEEDED/FAILED`, last attempted at, last successful at, last discovered count, last error. A failed attempt preserves the last real success. |
| `ConnectionTestResult` | Outcome of a provider connection test: success, provider message, details, timestamp. |
| `DiscoveredResource` | One provider resource observed by a sync (type, external id, metadata, timestamp). |
| `ResourceBinding` | One service↔resource edge with role, confidence and evidence. |
| `ServiceMapping` | The set of bindings for a service. |
| `DeploymentObservation` | A runtime state observed at a moment: candidate/previous revision, digests, commit, linked release. |
| `RollbackTarget` | The exact runtime revision a rollback restores. |
| `SyncResult` | Outcome of one sync run: discovered/removed/error counts and per-provider errors. |

The flat accessors on `Integration` (`connectionState()`, `health()`,
`lastSuccessfulSyncAt()`, `lastError()`) delegate to the two value
objects; they exist for API/persistence mapping, not as separate state.

## Capabilities

| Capability | Port | Provider (this build) |
| --- | --- | --- |
| `RUNTIME_DISCOVERY`, `DEPLOYMENT_STATUS`, `ROLLBACK_EXECUTION`, `HEALTH_VERIFICATION` | `DeploymentObservationPort`, `RollbackExecutionPort`, `HealthVerificationPort` | ECS |
| `ARTIFACT_DISCOVERY`, `ARTIFACT_VERIFICATION` | `ArtifactVerificationPort` | ECR |
| `QUEUE_DISCOVERY` | `DiscoveryContributionPort` | SQS |
| `EVENT_DISCOVERY` | `DiscoveryContributionPort` | EventBridge |
| `LOG_DISCOVERY` | `LogDiscoveryPort` | CloudWatch Logs |
| `SOURCE_DISCOVERY`, `SOURCE_METADATA` | `SourceMetadataPort` | GitHub |
| `RUNTIME_DISCOVERY`, `DEPLOYMENT_STATUS`, `HEALTH_VERIFICATION` | `DeploymentObservationPort`, `HealthVerificationPort` | Kubernetes |
| `DATABASE_DISCOVERY` | `DatabaseDiscoveryPort` | PostgreSQL |
| `DATABASE_MIGRATION_ANALYSIS` | `DatabaseMigrationAnalysisPort` | Flyway |
| `DEPLOYMENT_EVENTS`, `DEPLOYMENT_GATE`, `QUEUE_FENCING` | — | reserved; declared by nobody (honest NOT IMPLEMENTED) |

`DEPLOYMENT_GATE` is implemented at the API/CLI/Actions layer rather than
as a provider capability today: the preflight endpoint is the gate.

## Sync

`IntegrationSyncService` iterates `CapabilityProvider`s that implement
`DiscoveryContributionPort` and persists the union as `DiscoveredResource`
rows. If any provider fails, the previous resource set is kept and the
sync is reported as failed with every provider error -- partial results
are never presented as current. Sync is a background operation; nothing on
an application mutation path waits for it.

## Credentials

`IntegrationCredentialReference` stores references, never values:
`AWS_CONTROL_PLANE_ROLE` (hackathon; process IAM role),
`AWS_ASSUME_ROLE` + external id (customer path), `GITHUB_APP`
(private-key secret reference), `GITHUB_TOKEN`, `KUBERNETES_KUBECONFIG`,
`POSTGRES_PASSWORD`, `NONE`. `CredentialResolver` resolves them: local
profile reads environment variables named by the reference; AWS profile
reads Secrets Manager under `rollbackshield/*`.

## Adding a provider

1. Implement `Connector` (if it is a new family) or add a
   `CapabilityProvider` + the capability port inside an existing family.
2. Declare capabilities truthfully. If a call can mutate a customer
   runtime, it needs its own explicit capability (`ROLLBACK_EXECUTION`).
3. Add a contract test; if the provider can run against a local emulator
   or mock server, add an integration test too.
4. Document it in `docs/integrations/<PROVIDER>.md` and update the
   capability table here and in `LIMITATIONS.md` if verification is
   incomplete.
