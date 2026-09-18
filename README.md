# RollbackShield

**Keep the path back alive.**

A deployment reversibility control plane. It answers one question
mechanically: *can this production system safely return to the previous
release right now?*

## Problem
Deployment tools can move compute backward reliably. They can't guarantee
the system can — a candidate release may already have written data the
previous release can't read, or queued work that fires after "rollback"
completes. A team can roll back compute and still have a broken,
actively-mutating production system.

## Concrete failure this prevents
A checkout service's `Order.status` gains `PARTIALLY_REFUNDED` in v2,
which also queues an async refund webhook. Roll back to v1 without
protection: v1 can't read the new status, and the queued job fires anyway
after "rollback." See `docs/product/HACKATHON_DEMO.md` for the full
before/after script — both paths are runnable, no staged output.

## Solution
1. A rollback contract defines deterministic compatibility rules for a
   release.
2. A zero-dependency Java SDK embedded in the protected app evaluates
   every mutation **locally**, against a policy fetched once in the
   background — never a network call per write.
3. Async work is tagged with a release epoch and atomically fenced on
   rollback, safe under at-least-once delivery.
4. A control plane computes reversibility status from real current state
   (never a cached or fake score) and keeps an append-only audit trail.

## Demo
```
cd backend && mvn spring-boot:run          # terminal 1
cd demo-app && mvn compile exec:java -Dexec.mainClass=com.rollbackshield.demo.run.ProtectedDemo   # terminal 2
```
Or the Next.js control room: `cd frontend && npm run dev`.

## Capabilities (v0.1)
Release state machine, versioned rollback contracts, five deterministic
compatibility rule types, local SDK enforcement, work-epoch fencing with
idempotent redemption, reversibility status with named checks, append-
only audit trail, Cognito auth (+ local-dev bypass), DynamoDB/EventBridge/
SQS adapters behind the same ports as their in-memory equivalents, a
Next.js control room, and CDK infrastructure for all of it.

## Architecture
See `docs/architecture/SYSTEM_DESIGN.md` for diagrams;
`docs/architecture/AWS_ARCHITECTURE.md` for the AWS topology.

## Tech stack
Java 21 + Spring Boot (modular monolith) · zero-dependency Java SDK ·
Next.js + TypeScript + Tailwind · AWS (ECS Fargate, DynamoDB, Cognito,
EventBridge, SQS, CDK).

## Repository structure
```
backend/            Spring Boot control plane
sdk-java/            Local-evaluation enforcement SDK
demo-app/            v1/v2 demo application (ProtectedDemo / UnprotectedDemo)
demo-worker/         Standalone async worker, zero dependencies
frontend/            Next.js control room
infrastructure/      AWS CDK (TypeScript) — verified: npm install && cdk synth --all succeeds
docs/                Product, architecture, operations, development, ADRs
```

## Quick start
`docs/development/LOCAL_DEVELOPMENT.md` — no Docker needed, `local`
profile is fully in-memory.

## Tests
`docs/development/TESTING_STRATEGY.md`. Status: run and green in a real
environment — `sdk-java` (14 tests) and `backend mvn verify` (32 tests),
including ArchUnit `ModuleBoundaryTest`, `ReleaseLifecycleIntegrationTest`,
`TenantIsolationTest`, `CorsConfigurationTest`, `ServiceCredentialAuthTest`,
`DynamoDbAdapterIntegrationTest` (DynamoDB Local via Testcontainers), and
`AwsAdapterLocalStackIntegrationTest` (EventBridge + SQS via LocalStack).
The control room has committed Playwright E2E tests (`frontend/e2e`). See
Limitations for what still isn't covered.

## AWS deployment
`docs/operations/AWS_DEPLOYMENT.md` — root hygiene, IAM Identity Center
setup, IAM user+AssumeRole fallback, CDK bootstrap/deploy, verification,
cleanup. Not yet deployed to a live account from this build.

## Security summary
Cognito JWT auth for users (or local-dev bypass), Cognito Hosted UI PKCE
sign-in in the control room, a shared service credential for the worker
and SDK endpoints, org-scoped tenant isolation (`TenantIsolationTest`),
separate ECS execution/task IAM roles, no static credentials anywhere.
Details and the remaining hardening item (one shared service credential
rather than per-contract keys): `docs/architecture/SECURITY_ARCHITECTURE.md`.

## Limitations
`docs/product/LIMITATIONS.md` — read this before a demo. The backend, SDK,
demo, container image, control room, DynamoDB adapters, and
EventBridge/SQS adapters have all been built and exercised for real
(against local emulators where AWS is involved). The one substantial thing
left: **nothing has run in a live AWS account** — `cdk deploy` is
unverified.

## Roadmap
`docs/product/ROADMAP.md`.
