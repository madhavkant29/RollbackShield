# RollbackShield

A deployment reversibility control plane: it answers **"can this system
safely return to the previous release right now?"** — and then carries out
the rollback or commit on the connected infrastructure.

Deployment systems can move compute backwards. RollbackShield exists to
know whether the *system* can go backwards: compute, artifact, application
state, database migrations, async work, events, policy, and previous-version
health. It is not a CI/CD platform, a Kubernetes dashboard, a schema
registry, or a policy engine — it owns deployment reversibility and
integrates with the systems that own everything else.

## The product flow

```
connect systems -> discover services -> map dependencies -> observe a real
deployment -> preflight reversibility -> open a protected rollback window
-> fence async work -> roll back or commit -> verify -> permanent evidence
```

Releases are created from observed deployments, not typed in. The manual
release API remains for local development and unsupported systems.

## Repository layout

| Path | What it is |
| --- | --- |
| `backend/` | Spring Boot control plane: integrations, connectors, discovery, service mapping, releases, contracts, reversibility, rollback, work fencing, audit |
| `sdk-java/` | Local enforcement SDK: cached, deterministic policy evaluation in the application process |
| `frontend/` | Next.js control room: overview, integrations, services, releases (control room), reversibility, audit |
| `cli/` | `rollbackshield` automation CLI (real control-plane API calls) |
| `infrastructure/` | AWS CDK (network, identity, data, control plane, observability) |
| `demo-app/`, `demo-worker/` | Local demo of SDK enforcement and SQS epoch fencing |
| `docs/` | Product, architecture, integrations, operations, ADRs |

## Build and test (run in this order)

```
cd sdk-java && mvn install          # demo-app/demo-worker depend on this
cd ../backend && mvn verify          # ArchUnit + LocalStack + PostgreSQL + K8s mock tests
cd ../frontend && npm install && npm run build && npm run lint
cd ../cli && npm install && npm test
cd ../infrastructure && npm install && npx cdk synth --all
```

Testcontainers-backed tests (DynamoDB Local, LocalStack, PostgreSQL) skip
automatically when Docker is unavailable; everything else runs anywhere.

## Run locally (no Docker, no AWS)

```
cd backend && mvn spring-boot:run          # 'local' profile: in-memory, fixed dev principal
cd frontend && npm run dev                 # http://localhost:3000
```

Then in the UI: **Integrations → Connect system** (AWS with the
control-plane role needs real AWS credentials in the backend process
environment; otherwise use the FLYWAY/POSTGRESQL connectors locally) →
**Sync** → **Resources** → **Import as service** → **Services → Observe
deployment → Create release** → **Releases → control room**.

See `docs/product/HACKATHON_DEMO.md` for the full script, and
`docs/development/LOCAL_DEVELOPMENT.md` for details.

## What works and what doesn't

Read `docs/product/LIMITATIONS.md` before trusting anything. In short: the
engine, connectors, discovery/mapping, preflight, rollback orchestration
and fencing are implemented and covered by tests (backend suite green
including LocalStack/PostgreSQL/Kubernetes-mock/GitHub-stub and DynamoDB
Local).

**Live AWS verification (2026-09-19):** all six CDK stacks were deployed to
a real account (`ap-south-1`); the deployed control plane authenticated real
Cognito users, connected to AWS (`sts:GetCallerIdentity`), discovered 17
real resources, imported a fresh ECS service, observed candidate
`task-definition:2` against previous `:1` with real ECR digests, created and
preflighted the release, protected it, enqueued real SQS work, **rolled ECS
back to `:1`** (independently verified: running 1/desired 1, deployment
COMPLETED), verified health, redeemed stale work as `CANCEL`, and wrote the
full audit trail. Evidence: `docs/LIVE_AWS_VERIFICATION.md`,
`docs/FINAL_PRODUCT_AUDIT.md`. One operational caveat: the synchronous
rollback request can exceed the ALB 60s idle timeout and return 504 while
the rollback completes server-side — poll release state until async
execution ships.

Still unverified and tracked in `LIMITATIONS.md`: real GitHub App
installation, a real Kubernetes cluster, Cognito Hosted UI sign-in from a
browser, and SDK telemetry ingestion.

## Documentation

- Product: `docs/product/PRODUCT_OVERVIEW.md`, `HACKATHON_DEMO.md`,
  `LIMITATIONS.md`, `ROADMAP.md`
- Architecture: `docs/architecture/` (start with `SYSTEM_DESIGN.md`,
  then `CONNECTIVITY_MODEL.md`, `SERVICE_DISCOVERY.md`,
  `SERVICE_MAPPING.md`, `REVERSIBILITY_GRAPH.md`)
- Integrations: `docs/integrations/` (connector architecture, one file per
  connector, CLI, GitHub Actions)
- API: `docs/API_GUIDE.md`; domain: `docs/DOMAIN_MODEL.md`; lifecycle:
  `docs/RELEASE_LIFECYCLE.md`
- Operations: `docs/operations/AWS_DEPLOYMENT.md`,
  `IAM_AND_ACCESS.md`, `RUNBOOK.md`

## License

See `LICENSE`.
