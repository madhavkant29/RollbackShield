# System Design

## Problem / scope
See `docs/PRODUCT_OVERVIEW.md`. In scope for v0.1: release lifecycle,
rollback contracts, deterministic compatibility rules, local SDK
enforcement, work-epoch fencing, reversibility status, audit trail, one
demo app. Out of scope: everything in `docs/product/ROADMAP.md`.

## Container view
```mermaid
graph TD
    SDK[Java Enforcement SDK<br/>embedded in protected app] -->|GET policy, background only| API
    Worker[demo-worker] -->|poll / redeem| API
    UI[Next.js control room] -->|REST| API
    API[Spring Boot control plane<br/>ECS Fargate] --> DDB[(DynamoDB)]
    API --> EB[EventBridge]
    API --> SQS[SQS work queue]
    Cognito[Cognito] -.JWT.-> API
```

## Release state machine
See `docs/RELEASE_LIFECYCLE.md` for the full diagram.

## Mutation evaluation sequence
```mermaid
sequenceDiagram
    participant App as Protected app
    participant Guard as RollbackGuard (SDK, local)
    participant Cache as PolicyCache (SDK, local)
    App->>Guard: evaluate(MutationRequest)
    Guard->>Cache: get() (in-memory, no I/O)
    Cache-->>Guard: PolicySnapshot
    Guard->>Guard: ContractEvaluator.evaluate() (pure function)
    Guard-->>App: MutationDecision (ALLOW/BLOCK/UNKNOWN)
    Guard->>Guard: TelemetryBuffer.enqueue() (non-blocking)
```
No network call anywhere in this sequence.

## Policy refresh sequence
```mermaid
sequenceDiagram
    participant Cache as PolicyCache
    participant Source as HttpPolicySource
    participant API as Control plane
    loop background schedule
        Cache->>Source: fetch(contractId)
        Source->>API: GET /contracts/{id}/policy
        API-->>Source: JSON policy
        Source-->>Cache: PolicySnapshot
        Cache->>Cache: swap if policyVersion newer
    end
```

## Work fence sequence
See `docs/features/WORK_FENCING.md`.

## Rollback sequence
```mermaid
sequenceDiagram
    participant Op as Operator
    participant Release as ReleaseApplicationService
    participant Fence as WorkFenceApplicationService
    Op->>Release: POST /rollback
    Release->>Release: transition PROTECTED_ROLLOUT -> ROLLING_BACK
    Release->>Fence: invalidateEpoch(releaseId, epoch)
    Fence-->>Release: done
    Release->>Release: transition ROLLING_BACK -> ROLLED_BACK
    Release-->>Op: 200 ReleaseResponse
```

## Consistency model
Release transitions: optimistic concurrency via
`ReleaseRepository.compareAndSave()` (expected-state precondition),
backed by DynamoDB's version-attribute conditional put. Audit trail:
append-only, no update path. Work redemption: first-writer-wins via
`ConcurrentHashMap.computeIfAbsent` (in-memory today — see LIMITATIONS.md
for the DynamoDB-backed version needed before scaling past one instance).

## Known limitations, tradeoffs, future direction
See `docs/product/LIMITATIONS.md` and `docs/product/ROADMAP.md`.
