# Product Gap Audit

Date: 2026-09-19. Scope: the entire repository at this commit. Method:
inspect first, then run the real build/test commands; nothing below is
inferred from code existence alone. Where a claim could only be verified
with an external account, it is marked UNVERIFIED and the exact setup to
verify it is cited.

Environment: Windows, JDK 21 (Temurin 21.0.12.1+1) via
`JAVA_HOME`, IntelliJ-bundled Maven, Node 25.8.2, Docker available (used
by Testcontainers), no AWS account credentials.

## Status legend

`REAL + VERIFIED` — implemented and exercised by a run whose output was
observed. `REAL BUT UNVERIFIED` — implemented and unit/contract-tested,
but the real external system has not been exercised. `PARTIAL` — some of
the capability works and is verified; the rest is missing or unverified.
`DEMO-ONLY` — intentionally a demo, not a product path. `STATIC UI` —
informational page, no functionality. `STUB` — none found in production
code. `PLANNED` — deliberately not built. `BROKEN` — a run today failed.

## Capability classification

| Capability | Status | Evidence |
| --- | --- | --- |
| Spring Boot control plane (`local` profile) | REAL + VERIFIED | `mvn clean verify` green (82 tests); jar started on :18080/:18082, `/actuator/health` = UP, live API smoke run |
| Spring Boot control plane (`aws` profile) | **BROKEN** | Started jar with `--spring.profiles.active=aws`; `APPLICATION FAILED TO START`: no bean for `ServiceMappingRepository` (and the other connectivity repositories) |
| Release lifecycle (DRAFT→…→COMMITTED) | REAL + VERIFIED | `ReleaseLifecycleIntegrationTest`, live smoke (create/prepare/ready/contract), state machine tests |
| Rollback contracts + local policy format | REAL + VERIFIED | Contract round-trip in `DynamoDbAdapterIntegrationTest`, policy endpoint test, live contract activation |
| Java SDK | REAL + VERIFIED | `mvn install` green (14 tests), including `HttpPolicySource` against a real local `HttpServer` |
| Local policy evaluation | REAL + VERIFIED | `RollbackGuardTest`, deterministic evaluator tests |
| Policy refresh/cache | REAL + VERIFIED | SDK cache keyed by `(contractId, policyVersion)`, tested over real HTTP |
| Work fencing (epoch + redemption ledger) | REAL + VERIFIED | DynamoDB Local concurrent-redeem test; LocalStack SQS round-trip; fence-after-rollback in `ReleaseLifecycleIntegrationTest` |
| Audit | REAL + VERIFIED (per release) | In-memory + DynamoDB adapters tested; live audit rows; note: global `/audit` page is STATIC UI |
| Reversibility evaluation / preflight | REAL + VERIFIED | `ReversibilityEvaluatorTest`, `ConnectedReleaseFlowTest`, live smoke showing `AT_RISK/UNKNOWN` + precise blockers |
| Frontend build / lint / typecheck | REAL + VERIFIED | `npx tsc --noEmit` exit 0, `npm run lint` 0 warnings, `npm run build` 11 routes |
| Frontend API calls in a browser | PARTIAL | `lib/api.ts` is real fetch; committed Playwright E2E is **BROKEN/stale** (fails at `getByPlaceholder('Service name, e.g. checkout')` after the Services page rewrite; auth spec skipped without Cognito). Run today: 1 failed, 1 skipped |
| DynamoDB (core entities) | REAL + VERIFIED | `DynamoDbAdapterIntegrationTest` (9 tests) against DynamoDB Local, incl. optimistic locking |
| DynamoDB (connectivity entities) | **BROKEN / MISSING** | No `*DynamoDbItem`/adapter for Integration, DiscoveredResource, ServiceMapping, DeploymentObservation; in-memory beans are `@ConditionalOnProperty(…=in-memory)`, so the `aws` profile has no beans (proven by startup failure above) |
| SQS (work queue + connector) | REAL + VERIFIED | LocalStack enqueue/receive round-trip; queue discovery test against LocalStack |
| EventBridge (events + connector) | REAL + VERIFIED | LocalStack rule→SQS delivery test; bus discovery test |
| Cognito (auth) | REAL BUT UNVERIFIED | Backend `CognitoPrincipalConverter` + frontend PKCE exist; `auth.spec.ts` skipped; no real user pool driven |
| ECS (runtime adapter) | REAL BUT UNVERIFIED | Contract tests pin discovery, candidate/previous revision, rollback call, monitor/health mapping; ECS is LocalStack-Pro-only, no live run |
| ECR (artifact adapter) | REAL BUT UNVERIFIED | Contract tests: digest found/missing/blank; no live run |
| CloudWatch Logs (connector) | REAL + VERIFIED | LocalStack: log group + error evidence retrieval |
| CDK (synth) | REAL + VERIFIED | `npx tsc --noEmit` + `npx cdk synth --all` exit 0, 5 stacks |
| CDK (deploy) | **BROKEN (blocked) + UNVERIFIED** | Never deployed; even if deployed, the `aws` profile cannot start (missing connectivity persistence) |
| GitHub (connector) | REAL BUT UNVERIFIED against real API | Real HTTP + RS256 App JWT signature verified against a local stub; repo/commit/tag/tree/compare/contents paths tested |
| GitHub Actions (composite actions) | REAL BUT UNVERIFIED | Actions call the real API only; never executed on a runner |
| Repo CI workflow | PARTIAL | Real commands, but no `cli` job; never executed (no remote/run observed) |
| Service discovery | PARTIAL | Mechanism real+verified (sync, replace-on-success, per-provider errors) for SQS/Logs/Events/GitHub/PG/K8s; ECS discovery unverified live; no resource-list pagination in UI |
| Service mapping | REAL + VERIFIED; PARTIAL UI | Import inference + evidence verified in `ConnectedReleaseFlowTest`; no UI to edit/confirm MEDIUM bindings (API exists) |
| PostgreSQL | REAL + VERIFIED | Real postgres:16 container: database, tables, column nullability, views |
| Flyway / migration analysis | REAL + VERIFIED | Classifier tests (8) + destructive-vs-safe preflight paths in the connected flow |
| Kubernetes | PARTIAL | Discovery/revision/health verified against fabric8 mock CRUD server; rollback execution **PLANNED** (capability absent by design) |
| CLI | PARTIAL | Live run against a real backend: `login`, `services list`, `integrations connect/list` produced correct output; **exit code is corrupted on Node 25/Windows** (`exit=-1073740791` with `Assertion failed: … UV_HANDLE_CLOSING`) after HTTP commands, which breaks the documented exit-code gate |
| Deployment observation | REAL + VERIFIED (orchestration) | `ConnectedReleaseFlowTest`: observe → release from observation (idempotent) → labels `task-definition:1→2` |
| Rollback execution | PARTIAL | Orchestrator verified with a fake runtime (success, artifact-missing → FAILED, no-runtime path); ECS execution unverified live; execution currently blocks the HTTP request while polling |
| Health verification | PARTIAL | Kubernetes verified against mock API; ECS health logic contract-tested; live unverified |
| `/contracts`, `/audit`, `/settings` pages | STATIC UI | Informational text pointing at the release control room; no fake data, no functionality |
| `demo-app`, `demo-worker`, `ProtectedDemo` | DEMO-ONLY (by design) | Local demonstration of SDK enforcement and epoch fencing; documented as such |
| SDK decision telemetry ingestion | PLANNED | SDK buffers, does not report; listed in LIMITATIONS/ROADMAP |
| Per-contract credentials / mTLS | PLANNED | One shared service credential today |

STUB: none found. Grep for `TODO|FIXME|placeholder|not implemented|stub`
in `*.java/ts/tsx` returns only test doubles, comments about
deliberately-absent capabilities, and input placeholders.

## Current real capabilities (summary)

Connect → sync → discover → import → map (with evidence) → observe a real
deployment → create a release from that observation → preflight with
policy/database/async/compute/artifact/health checks and evidence paths →
activate a contract → execute a rollback through the runtime connector
(or fail it explicitly) → verify health → audit. All of it is verified by
tests at the connector/orchestration level and by a live local-backend
smoke run; the only unverified piece is real-account behavior (ECS/ECR,
Cognito, STS AssumeRole against a customer role).

## Current fake / demo-only / static capabilities

- Demo modules and `ProtectedDemo` (intentional).
- `/contracts`, `/audit`, `/settings` informational pages.
- Local `LocalDevDataSeeder` fixed organization (local profile only).
- Test doubles (ECS/ECR SDK collaborators, fake connectors, GitHub stub)
  — test-only, never on a production path.
- No `CONNECTED` state is ever fabricated: connection state is only set
  from a real provider test (verified live: AWS without credentials →
  `ERROR` with STS's message).

## Missing product connectivity

1. **Connectivity persistence for the `aws` profile** — DynamoDB adapters
   for Integration / DiscoveredResource / ServiceMapping /
   DeploymentObservation (+ item classes, GSI access patterns, tests).
   Without this the product cannot be deployed at all.
2. **Live provider verification** — the §4.5 checklist in
   `docs/operations/AWS_DEPLOYMENT.md` (ECS/ECR/STS live; GitHub App on a
   real installation; Cognito Hosted UI).
3. **Async rollback execution + status** — the rollback request currently
   holds an HTTP request thread while polling (default up to 600 s); ALB's
   idle timeout (60 s) would cut it off in AWS. Needs `202 Accepted` +
   execution-status endpoint (or an event/stream), keeping idempotency.
4. **Browser-level product verification** — a current Playwright flow for
   the connected product (integrations → import → observe → release →
   preflight) and CI wiring; the committed E2E is stale.
5. **Mapping confirmation UI** — confirm/remove MEDIUM bindings from the
   Services page (API already supports it).
6. **CI coverage for the CLI** and for the CLI exit-code defect.

## Technical debt

- CLI exits with `process.exit()` while undici keep-alive sockets are
  open → libuv assertion on Windows/Node 25; must use `process.exitCode`
  (and/or close the agent) so the documented gate semantics hold.
- Unbounded in-process caches: `AwsClients`, `KubernetesClients`, GitHub
  app-token cache (no eviction/size bounds).
- `SecretsManagerCredentialResolver` fetches the secret on every resolve
  (no short TTL cache) → Secrets Manager API quota under repeated syncs.
- `EcsRuntimeAdapter` previous-revision fallback is a heuristic (highest
  ACTIVE revision below the candidate); document/verify against a real
  service history.
- Discovery has no UI pagination; resource tables grow unbounded.
- `docs/DOMAIN_MODEL.md`, `docs/architecture/DATA_MODEL.md` and
  `C4_MODEL.md` do not describe the connectivity entities (one passing
  mention total) — doc drift against AGENTS rule 8.
- CloudWatch evidence and GitHub metadata fetch synchronously inside
  user-triggered calls (acceptable), but no timeouts beyond HTTP/client
  defaults are documented for every path.
- No CDK unit/assertion tests; no `cdk deploy` dry run of the new IAM
  statements (synth only).
- Repo CI has no `cli` job; `frontend` job runs lint+build but not
  `tsc --noEmit` explicitly (build covers it) and not E2E.

## Highest-risk gaps (ranked)

1. **`aws` profile cannot start** — blocks any deployment and the entire
   live verification. Fix first.
2. **ECS/ECR never exercised against real AWS** — the core value
   proposition (real rollback execution) is contract-tested only.
3. **Rollback holds the request thread** — in AWS this will surface as a
   504/timeout while the rollback may actually be converging; operators
   could misread state. Correctness-adjacent.
4. **CLI exit codes corrupted on Windows/Node 25** — the documented
   pipeline gate (`preflight` exit status) cannot be trusted on that
   platform.
5. **Stale Playwright E2E** — no automated proof that the productized UI
   still performs the flow in a browser; regressions in `api.ts` wiring
   would only be caught manually.

## Exact implementation order (Phase 1 plan)

Each step ends with the stated verification; do not proceed while a
foundational check is red.

1. **Connectivity persistence (DynamoDB).**
   Add `IntegrationDynamoDbItem`, `DiscoveredResourceDynamoDbItem`,
   `ServiceMappingDynamoDbItem`, `DeploymentObservationDynamoDbItem` and
   `DynamoDb*Repository` implementations matching the in-memory ports and
   the existing single-table/GSI conventions. Add an `aws`-profile
   context test (bounded properties, no network) proving the context
   starts, and DynamoDB Local tests for each new adapter.
   Verify: `cd backend && mvn clean verify`; then
   `java -jar target/… --spring.profiles.active=aws` with dummy
   `COGNITO_ISSUER_URI` reaches "Started RollbackShieldApplication"
   (security auto-config permitted with a stub issuer).
2. **CLI exit semantics.**
   Replace `process.exit(code)` with `process.exitCode = code` (+
   optional undici agent close), add a spawned-process test asserting the
   exit code and intact stdout on success and on `CANNOT_ROLLBACK`.
   Verify: `cd cli && npm test`; manual `node dist/index.js services list;
   echo $LASTEXITCODE` returns 0 with no assertion output.
3. **Async rollback execution.**
   `POST /releases/{id}/rollback` returns `202` with an execution id;
   add `GET /releases/{id}/rollback-execution`; keep the existing
   synchronous semantics for the no-runtime path or make both async; the
   orchestrator runs on a bounded executor. Update SDK/UI/CLI and docs
   (`docs/architecture/PERFORMANCE_AND_LATENCY.md`, `API_GUIDE.md`).
   Verify: tests for 202 + status transitions + idempotent re-request;
   frontend control room shows "in progress" then the final state.
4. **Browser E2E for the connected flow.**
   Rewrite `frontend/e2e` against the current UI (integrations → sync →
   import → observe → release → preflight → rollback with the local
   profile and a fake-free path: use FLYWAY+GitHub stub? — use the local
   backend's manual path for services without connectors, and assert the
   honest UNKNOWN verdict). Wire into CI with backend+frontend.
   Verify: `npm run test:e2e` green locally; CI workflow updated.
5. **Live AWS run (the §4.5 checklist).**
   Execute and record; then update `docs/product/LIMITATIONS.md`
   (remove "No live AWS run") and the demo script.
6. **Doc drift and hardening (can parallelize with 3–5):**
   update `DOMAIN_MODEL.md`/`DATA_MODEL.md`/`C4_MODEL.md`; add secret
   caching with TTL; bound client caches; add mapping confirmation UI;
   add CLI job to CI; document ECS previous-revision heuristic.

## Verification log (exact commands and results)

```
sdk-java:       mvn -B install
                → BUILD SUCCESS, Tests run: 14, Failures: 0, Errors: 0

backend:        mvn -B clean verify
                → BUILD SUCCESS, Tests run: 82, Failures: 0, Errors: 0
                (ArchUnit 5, ConnectedReleaseFlow 2, LocalStack AWS 4,
                 ECR 4, ECS 10, Flyway 8, GitHub 7, Kubernetes 5,
                 PostgreSQL 1, LocalStack core 2, Cors 2, DynamoDB 9,
                 ReleaseLifecycle 2, ServiceCredential 4, Tenant 2,
                 IntegrationApplication 5+4, Reversibility 4, WorkFence 2)

demo-app:       mvn -B -f demo-app/pom.xml clean package       → exit 0
demo-worker:    mvn -B -f demo-worker/pom.xml clean package    → exit 0

frontend:       npx tsc --noEmit                               → exit 0
                npm run lint                                   → no warnings/errors
                npm run build                                  → compiled, 11 routes
                npx playwright test --reporter=line            → 1 failed, 1 skipped
                (failure: stale selector on /services before any API call;
                 backend was not reachable on :8080 in that run because the
                 host's httpd owns :8080 — irrelevant to the selector failure)

cli:            npm test                                       → 4/4 pass
                node dist/index.js login --api-url http://localhost:18082
                node dist/index.js services list               → correct output, crash assertion + garbage exit code on exit
                node dist/index.js integrations connect …      → correct JSON output

infrastructure: npx tsc --noEmit                               → exit 0
                npx cdk synth --all -q                         → exit 0, 5 stacks

aws profile:    java -jar backend.jar --spring.profiles.active=aws --server.port=18081
                → APPLICATION FAILED TO START:
                  no bean of type ServiceMappingRepository

local profile:  java -jar backend.jar --server.port=18080
                → /actuator/health UP; live smoke:
                  FLYWAY integration test = CONNECTED (directory readable),
                  AWS integration test = ERROR (no credentials) — honest,
                  preflight = AT_RISK/UNKNOWN with
                  MIGRATION_ANALYSIS_UNAVAILABLE + NO_RUNTIME_MAPPING
```
