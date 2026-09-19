# AGENTS.md

Standing instructions for any AI coding agent working in this repository.
Read this first, then `docs/product/LIMITATIONS.md` for current known gaps
before touching anything.

## What this project is

RollbackShield: a deployment reversibility control plane. See
`docs/PRODUCT_OVERVIEW.md` and `docs/architecture/CONNECTIVITY_MODEL.md`
for the current product and how the control plane relates to the local
enforcement SDK.

## Build & test (run in this order)

```
cd sdk-java && mvn install      # backend doesn't need this; demo-app/demo-worker do
cd ../backend && mvn verify     # includes ArchUnit + integration tests
cd ../frontend && npm install && npm run build && npm run lint
cd ../cli && npm install && npm test
cd ../infrastructure && npm install && npx cdk synth --all
```

If any of these fail, that's real signal — fix the actual error, don't
work around it by deleting the failing test or loosening a check.

Local note: this machine has no Java 21 on PATH by default. Use
`$env:JAVA_HOME='C:\Users\madha\.jdks\temurin-21\jdk-21.0.12.1+1'` with the
IntelliJ-bundled Maven
(`C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.1\plugins\maven-plugin\lib\maven3\bin\mvn.cmd`);
Maven on JDK 26 breaks Mockito/ByteBuddy.

## Non-negotiable rules

1. **Module boundaries are enforced by `ModuleBoundaryTest`
   (ArchUnit), not just convention.** `domain/` packages never import
   Spring, AWS SDK, Jackson, or servlet classes. `application/` never
   reaches into another module's `adapter/` package. See
   `docs/development/MODULE_BOUNDARIES.md`.
2. **Every module follows `domain/application/adapter/api`.** Copy an
   existing module's shape (e.g. `release/`) for a new one.
3. **Check `docs/product/ROADMAP.md` before adding a "nice to have".**
   The connectivity layer (integrators/connectors/discovery/mapping/
   observation/evidence/rollback execution) was explicitly commissioned
   and is implemented; everything else listed in ROADMAP.md is
   deliberately deferred. Do not build ROADMAP items opportunistically.
4. **Stable error codes, not message strings.** New failure modes get a
   new `ApiException` subclass or a case in `GlobalExceptionHandler`,
   registered in `docs/API_GUIDE.md`'s error code table.
5. **No `Object`/`Map<String,Object>` domain modeling.** Use
   `record`/`enum`/`sealed interface`. See `docs/development/CODE_STYLE.md`.
6. **Constructor injection only.** Never `@Autowired` field injection.
7. **Tenant scope always comes from `CurrentPrincipal`, never from a
   client-supplied path/body value.** Every new per-release/per-contract
   endpoint must check caller-org-matches-resource-org before returning
   data — copy the pattern in `ReleaseController`/`ReversibilityController`.
   Three endpoints originally shipped without this check and were fixed
   after the fact; don't repeat that. See
   `docs/architecture/SECURITY_ARCHITECTURE.md`.
8. **Keep docs truthful.** If you change a domain concept, state machine,
   API shape, or architecture decision, update the matching doc in the
   same change. If you fix something listed in `LIMITATIONS.md`, remove
   or update that entry — don't let it go stale in either direction
   (claiming a gap that's closed, or hiding one that isn't).
9. **No meaningless tests.** No getter/setter tests, no mocking the thing
   under test. Every test asserts a real invariant. See
   `docs/development/TESTING_STRATEGY.md`.
10. **Commit messages**: `feat(module): ...`, `fix(module): ...`,
    `docs(area): ...`. Never `update`/`fix stuff`/`wip`.

## Where things live

- Domain model: `docs/DOMAIN_MODEL.md`
- Release lifecycle: `docs/RELEASE_LIFECYCLE.md`
- API surface: `docs/API_GUIDE.md`
- Architecture: `docs/architecture/` (start with `SYSTEM_DESIGN.md`, then
  `CONNECTIVITY_MODEL.md`, `SERVICE_DISCOVERY.md`, `SERVICE_MAPPING.md`,
  `REVERSIBILITY_GRAPH.md`)
- Connectors: `docs/integrations/` (start with
  `CONNECTOR_ARCHITECTURE.md`, then the per-provider file)
- CLI: `docs/integrations/CLI.md` (source in `cli/`)
- Decisions and why: `docs/adr/`
- Current known gaps: `docs/product/LIMITATIONS.md` — **check this before
  assuming something is broken or missing; it may already be tracked**
- What's deliberately not built yet: `docs/product/ROADMAP.md`
- Local dev: `docs/development/LOCAL_DEVELOPMENT.md` (no Docker needed)
- AWS deployment + live verification checklist:
  `docs/operations/AWS_DEPLOYMENT.md`

## Never do this

- Never commit `node_modules/`, `.next/`, `target/`, `cdk.out/`, `dist/`
  (all already gitignored — if you see one staged, something's wrong).
- Never put static AWS credentials anywhere (code, `.env`, CI config,
  Docker image). ECS uses IAM roles; local dev needs no AWS credentials
  at all (`local` Spring profile is fully in-memory).
- Never claim something is "verified" or "tested" in a doc unless you
  actually ran it and saw it pass. This codebase's docs have been
  deliberately honest about the difference between "written" and
  "verified" — keep that discipline.
