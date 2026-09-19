# Kubernetes connector

`connectors/kubernetes/adapter/KubernetesRuntimeAdapter` — capabilities
`RUNTIME_DISCOVERY`, `DEPLOYMENT_STATUS`, `HEALTH_VERIFICATION`.
**No `ROLLBACK_EXECUTION`** — deliberately.

- Credential: `KUBERNETES_KUBECONFIG` (kubeconfig YAML from a secret
  store). `KubernetesClients` caches clients per integration/master URL and
  closes them on shutdown; clients own connection pools, so discovery
  reuses them.
- Discovery: deployments across namespaces (capped at 500) with revision
  annotation, change cause, image, desired/available replicas, conditions.
- Observation: candidate revision from
  `deployment.kubernetes.io/revision`; previous revision from owned
  ReplicaSets with the highest revision below the candidate (fallback:
  second-newest owned ReplicaSet when annotations are absent). Digests are
  parsed only from pinned `image@sha256:` references.
- Health: desired vs available/ready replicas and the Progressing
  condition. Scaled-to-zero reports UNKNOWN; zero available reports
  UNHEALTHY.

Rollback execution for Kubernetes would require deployment-tool policy
decisions (which controller owns the desired state?) that RollbackShield
should not silently make; the capability is absent rather than faked, and
the registry refuses to route a rollback to this connector.

## Connection and provider independence (Phase 6)

`KubernetesConnector` owns the real connection test (`getKubernetesVersion()`
+ namespace list), so Kubernetes integrations can be created, tested and
synced through the same API/UI/CLI as AWS or GitHub. The runtime adapter is
just a `CapabilityProvider`; core code sees only
`DeploymentObservationPort`/`HealthVerificationPort`. Two architecture tests
pin that:

- `ModuleBoundaryTest.corePackagesMustNotDependOnConnectorImplementations`
  — nothing outside `connectors/` may import a connector class;
- `ProviderIndependenceTest.coreCodeContainsNoProviderBranchingLiterals` —
  core application packages contain no `ConnectorType.<provider>` branches
  or provider-name literals; capability lookups go through
  `ConnectorRegistry.port(...)` / `portForCapability(...)`.

Unsupported rollback execution is reported at three layers: the registry
refuses `ROLLBACK_EXECUTION` (`CAPABILITY_NOT_SUPPORTED`), preflight adds a
`Rollback execution` check failing with `ROLLBACK_EXECUTION_UNSUPPORTED`
(UNKNOWN, so the release is never claimed `CAN_ROLLBACK`), and the control
room disables the rollback button with the reason; an API/CLI attempt ends
`FAILED` with `resolve-rollback-executor` and the registry message.

Verified against the fabric8 Kubernetes mock API server with CRUD
semantics (`KubernetesRuntimeAdapterTest`): discovery metadata, revision
and previous-image extraction from owned ReplicaSets, missing deployment,
healthy/unhealthy health paths. A real cluster has not been driven yet;
see `docs/product/LIMITATIONS.md`.
