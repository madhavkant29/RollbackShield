# Observability

## Structured logging
Backend uses SLF4J; `GlobalExceptionHandler` attaches a `requestId` (MDC)
to every error response. Fields named in the original spec
(`traceId`, `organizationId`, `serviceId`, `releaseId`, `contractVersion`,
`policyVersion`, `durationMs`, `decision`, `reasonCode`) are **not yet
wired into a structured logging filter** — logging today is ad hoc
(`LoggingEventPublisher`, exception handlers). Tracked as a gap.

## Metrics
`spring-boot-starter-actuator` + Micrometer are on the classpath
(`/actuator/health`, `/actuator/metrics`) but no custom
`rollbackshield.*` metrics (policy evaluation duration, allow/block
counts, work execute/cancel counts, rollback duration) have been
registered yet. Named in the original spec; not implemented.

## What IS real
CloudWatch dashboard + two alarms (5xx rate, CPU) in
`infrastructure/lib/observability-stack.ts` — real CDK, synthesized
successfully. ECS→CloudWatch Logs wiring (`awsLogs` driver,
`/rollbackshield/backend` log group) is real infrastructure, just not yet
receiving traffic from a deployed service.

## Never logged
JWTs, passwords, AWS credentials, full request bodies containing
sensitive payloads — none of these appear in any logging call in this
codebase.
