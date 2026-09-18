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

## Adding a new module
Copy this shape. If the module has no persistence of its own (e.g.
`enforcement`), it can omit `adapter/`/`api/`. Never put persistence
details in `domain/`, never put HTTP concerns in `application/`.
