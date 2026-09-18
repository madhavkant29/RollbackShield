# ADR 002: ECS Fargate for the Spring Boot control plane

## Status
Accepted

## Context
The control plane needs to run a long-lived JVM process serving REST
traffic, with predictable startup/health-check behavior and simple
background scheduling (e.g. a future policy-cache-warming job). Lambda is
a poor fit for a stateful, connection-pooling, long-running Spring Boot
app: JVM cold starts are slow, and container reuse behavior fights
against predictable latency.

## Decision
ECS Fargate, one service, fronted by an Application Load Balancer.
Task/execution IAM roles are separate (ADR is not needed for this split
specifically -- it's covered under the security architecture docs, but
the decision to use two roles at all is recorded here since it shapes the
CDK stack structure: `ExecutionRole` only pulls images and writes logs,
`TaskRole` is the only identity with DynamoDB/EventBridge/SQS access).

## Consequences
- No NAT Gateway: the ECS service runs in public subnets with a security
  group restricting inbound to the ALB (see ADR-need in
  `docs/operations/COST_MODEL.md`) -- keeps a personal AWS account's bill
  predictable.
- Scaling is a `desiredCount` change away if ever needed; not built into
  v0.1 since a hackathon demo doesn't need it.
