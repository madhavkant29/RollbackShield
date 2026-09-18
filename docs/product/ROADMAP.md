# Roadmap (future only — none of this is implemented)

**v0.2** — external developer onboarding, API credentials, environment
separation, Maven Central SDK publishing, contract draft/review workflow
before activation (currently create+activate are atomic).

**v0.3** — `ConsumerLease`, historical replay certification.

**v0.4** — `ArtifactReference` / artifact reachability checks.

**v0.5** — CI/CD deployment gates (GitHub Actions, GitLab, Jenkins
integrations).

**v0.6** — Argo CD / ECS / EKS deployment-tool integrations
(`DeploymentIntegration`).

**v0.7** — database-evolution protection (schema migrations as a first-
class reversibility concern, not just row-level compatibility rules).

**v0.8** — additional language SDKs / sidecar mode for non-JVM apps.

**v0.9** — `ExternalCloudConnection` — connect to a customer's own AWS
account rather than running inside RollbackShield's.

**v1** — production SaaS: billing, enterprise auth, usage metering,
advanced RBAC, operational maturity (multi-instance work fencing per
ADR-005, structured logging/metrics per `OBSERVABILITY.md`, worker/SDK
service-credential auth per `SECURITY_ARCHITECTURE.md`).

See `docs/product/LIMITATIONS.md` for gaps in what v0.1 already claims to
do, as distinct from this list of what it deliberately doesn't attempt
yet.
