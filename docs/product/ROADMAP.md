# Roadmap (future only — none of this is implemented)

The connectivity layer (integrations, connectors, discovery, service
mapping, deployment observation, evidence preflight, rollback execution)
is now implemented; the items below are what remains deliberately
deferred.

**Next — live verification and hardening**
- Live AWS run of ECS discovery/rollback and ECR digest verification in a
  real account (`docs/operations/AWS_DEPLOYMENT.md`).
- SDK decision telemetry ingestion (`MutationBlocked`,
  `RollbackRiskDetected`) so blocked mutations appear in the audit trail
  without a control-plane round trip.
- Per-integration/per-contract credentials instead of one shared service
  credential; mTLS for worker/SDK calls.

**v0.2** — additional connectors, in priority order: GitLab (source +
CI), Jenkins native pipeline step, AWS CodePipeline, Argo CD / Flux
deployment observation, Liquibase migration analysis, Kafka/MSK and
Kinesis consumer/queue discovery. The connector framework already
supports them as new `CapabilityProvider`s; none are stubbed in this
build.

**v0.3** — `ConsumerLease`, historical replay certification; column-usage
static analysis so destructive migrations can be matched to fields the
previous release actually reads (today the migration range is proven, the
per-field usage is not).

**v0.4** — additional runtime executors: Kubernetes rollback execution,
Docker Engine/Compose observation, GHCR/Docker Hub/generic OCI artifact
verification.

**v0.5** — multi-language SDKs / sidecar mode for non-JVM apps.

**v1** — production SaaS: billing, enterprise auth, usage metering,
advanced RBAC, richer observability (dashboards, structured audit export).

See `docs/product/LIMITATIONS.md` for gaps in what the current build
claims to do, as distinct from this list of what it deliberately doesn't
attempt yet.

## Deployed frontend/API edge (hackathon)

The browser reaches the API directly through API Gateway HTTPS (	4dv6crzic.execute-api.ap-south-1.amazonaws.com) with a public HTTP proxy to the internet-facing ALB; CORS is restricted to the Amplify origin. This is a deliberate hackathon tradeoff (public ALB remains reachable, HTTP hop inside AWS, ~30s API Gateway integration timeout so synchronous rollback can 504 while continuing -- poll release state). See `docs/architecture/FRONTEND_API_EDGE.md` and `docs/adr/006-api-gateway-public-alb-hackathon-edge.md`; post-hackathon target is VPC Link + private ALB plus async rollback operations.
