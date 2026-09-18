# C4 Model

## Level 1: System context
```mermaid
graph LR
    Operator((Operator)) --> UI[RollbackShield control room]
    Dev((App developer)) --> SDK[RollbackShield Java SDK]
    UI --> CP[RollbackShield control plane]
    SDK --> CP
    App[Protected application] --> SDK
```

## Level 2: Containers
```mermaid
graph TD
    subgraph "RollbackShield"
        UI[Next.js control room]
        CP[Spring Boot control plane<br/>ECS Fargate]
        SDK[Java SDK<br/>embedded, zero deps]
        Worker[demo-worker]
    end
    DDB[(DynamoDB)]
    EB[(EventBridge)]
    SQS[(SQS)]
    Cognito[(Cognito)]

    UI -->|REST + JWT| CP
    SDK -->|GET policy, background only| CP
    Worker -->|poll/redeem| CP
    CP --> DDB
    CP --> EB
    CP --> SQS
    CP -.validates JWT.-> Cognito
```

## Level 3: Components (control plane)
```mermaid
graph TD
    subgraph "backend"
        release[release: domain/application/adapter/api]
        contract[contract: domain/application/adapter/api]
        enforcement[enforcement: domain]
        workfence[workfence: domain/application/adapter/api]
        reversibility[reversibility: domain/application/api]
        audit[audit: domain/adapter/api]
        catalog[catalog: domain/application/adapter/api]
        identity[identity]
        shared[shared: api, security, events, domain, dynamodb, sqs]
    end
    contract --> release
    reversibility --> contract
    reversibility --> release
    release --> workfence
    release --> audit
    contract --> audit
    workfence --> audit
```
Every arrow above is an application-layer service calling another
application-layer service — never a controller calling another module's
repository directly (enforced by `ModuleBoundaryTest`).

## Level 4: Code
See the Javadoc on each `domain` class and `docs/DOMAIN_MODEL.md` — code
is the source of truth at this level, not a diagram that will drift.
