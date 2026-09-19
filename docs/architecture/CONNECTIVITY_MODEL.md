# Connectivity model

## Two planes, one boundary

```
CONTROL PLANE (observes, coordinates)          ENFORCEMENT PLANE (local, fast)
------------------------------------------     ---------------------------------
dedicated AWS account / ECS task role          customer application process
connectors -> AWS / GitHub / K8s / SQL         RollbackShield Java SDK
discovery, mapping, observation                cached policy
preflight, rollback orchestration              deterministic evaluation
DynamoDB, EventBridge, SQS                     customer persistence
```

The enforcement path never calls the control plane per mutation. Policy is
fetched, cached by `(contractId, policyVersion)`, and evaluated locally
deterministically; the SDK's blocked/allowed decisions are buffered for
asynchronous telemetry (telemetry ingestion itself is still on the
roadmap). Connector outages therefore never take an application hot path
down.

## Observation plane rules

- Discovery and sync are asynchronous background operations.
- Preflight is user/pipeline-initiated and may call providers; it is not
  on a mutation path.
- Rollback execution is explicit, authorized, idempotent, and audited.
- CloudWatch/GitHub/ECS/K8s calls never happen inside an application
  mutation.
- Clients and connections are cached (AWS clients per
  integration/region/credentials; Kubernetes clients per integration).
- Discovery avoids N+1 provider calls (batched `DescribeServices`,
  paginators, capped attribute sampling) and documents caps in resource
  metadata.

## Integration state model

Every integration carries two independent, evidence-backed aspects:

```
Integration
├── IntegrationConnection   state: CONNECTING|CONNECTED|ERROR|DISCONNECTED
│                           health: HEALTHY|DEGRADED|UNHEALTHY|UNKNOWN
│                           lastError (connection failures only)
└── SyncState               status: NEVER_SYNCED|SUCCEEDED|FAILED
                            lastAttemptedAt, lastSuccessfulAt, lastDiscoveredCount
                            lastError (sync failures only)
```

`IntegrationConnection` only becomes CONNECTED from a real provider
connection test; `SyncState.lastSuccessfulAt` is only set by a sync that
completed. A failed sync preserves the previous successful timestamp,
count, and the previously discovered resource set -- an outage degrades
honestly instead of erasing observed state. The API exposes both aspects
(flat fields plus health detail), and the UI renders them verbatim.

## Credential boundaries

- The RollbackShield deployment IAM role (task role) is **not** the
  customer-observation role. They are different security boundaries.
- Hackathon mode uses the control-plane role to observe its own account.
- Customer mode uses STS AssumeRole with an external id; the control plane
  never accepts static customer keys.
- Provider secrets (GitHub app key, kubeconfig, DB password) live in
  Secrets Manager (AWS) or environment variables (local), referenced by
  `IntegrationCredentialReference`, never stored in DynamoDB as values and
  never returned by an API.

## Failure honesty

A failed connector call degrades the product honestly: sync keeps the last
real state and reports errors; connection state becomes ERROR with the
provider's message; preflight reports the dimension as UNKNOWN with a
blocker rather than defaulting to safe. No code path fabricates a healthy
state.
