# Module Boundaries

Enforced by `ModuleBoundaryTest` (ArchUnit), not just this document.

## Rules
1. `*.domain.*` classes never import `org.springframework..`,
   `jakarta.servlet..`, `software.amazon.awssdk..`, or
   `com.fasterxml.jackson..`.
2. `*.application.*` classes never import another module's `*.adapter.*`
   package — cross-module calls go through the other module's
   `application` service.
3. Every `@RestController` class name ends in `Controller`.

## Layout per module
```
<module>/
  domain/        pure Java: records, sealed interfaces, enums, ports (interfaces)
  application/   @Service classes: orchestration, transactions, audit/event emission
  adapter/       @Repository/@Component: implementations of domain ports (in-memory, DynamoDB, SQS, EventBridge)
  api/           @RestController + DTOs
```

## Current modules

Lifecycle and enforcement: `catalog` (orgs/services), `release`,
`contract`, `reversibility`, `rollback`, `workfence`, `audit`,
`enforcement`, `identity`, `shared`.

Connectivity: `integrations` (Integration, mapping, discovery,
observation domain + application + API), `servicemapping`,
`deployment`, plus `connectors/<provider>/` which contains the only
provider SDK code:

```
connectors/aws/adapter/         AWS SDK (ECS, ECR, SQS, EventBridge, CloudWatch, STS)
connectors/github/adapter/      GitHub REST over java.net.http
connectors/kubernetes/adapter/  fabric8
connectors/postgres/adapter/    JDBC (org.postgresql)
connectors/flyway/domain/       pure deterministic SQL classification
connectors/flyway/adapter/      directory/port wiring
```

`connectors/*` is downstream of `integrations.domain` only: core
release/reversibility code must not import anything under `connectors/`.
Application-layer connector access goes through `ConnectorRegistry`,
never through a provider class.

## Adding a new module
Copy this shape. If the module has no persistence of its own (e.g.
`enforcement`), it can omit `adapter/`/`api/`. Never put persistence
details in `domain/`, never put HTTP concerns in `application/`, and
never add a provider SDK import outside `connectors/<provider>/adapter/`.
