# AGENTS.md

Standing instructions for any AI coding agent working in this repository.
Read this first, then `docs/product/LIMITATIONS.md` for current known gaps
before touching anything.

## What this project is

RollbackShield: a deployment reversibility control plane. See
`docs/PRODUCT_OVERVIEW.md`. Full context for a first-time agent:
`HANDOFF.md` at repo root (delete that file once its checklist is done —
it's a one-time briefing, not standing instructions; this file is).

## Build & test (run in this order)

```
cd sdk-java && mvn install      # backend doesn't need this; demo-app/demo-worker do
cd ../backend && mvn verify     # includes ArchUnit + integration tests
cd ../frontend && npm install && npm run build
cd ../infrastructure && npm install && npx cdk synth --all
```

If any of these fail, that's real signal — fix the actual error, don't
work around it by deleting the failing test or loosening a check.

## Non-negotiable rules

1. **Module boundaries are enforced by `ModuleBoundaryTest`
   (ArchUnit), not just convention.** `domain/` packages never import
   Spring, AWS SDK, Jackson, or servlet classes. `application/` never
   reaches into another module's `adapter/` package. See
   `docs/development/MODULE_BOUNDARIES.md`.
2. **Every module follows `domain/application/adapter/api`.** Copy an
   existing module's shape (e.g. `release/`) for a new one.
3. **Hackathon freeze rule still applies.** Don't build anything listed
   in `docs/product/ROADMAP.md` (ConsumerLease, replay certification,
   artifact reachability, multi-SDK, billing, K8s, etc.) until everything
   in `docs/product/LIMITATIONS.md` is closed. If tempted to add a
   "nice to have," check ROADMAP.md first — if it's there, it's
   deliberately deferred.
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
- Architecture: `docs/architecture/` (start with `SYSTEM_DESIGN.md`)
- Decisions and why: `docs/adr/`
- Current known gaps: `docs/product/LIMITATIONS.md` — **check this before
  assuming something is broken or missing; it may already be tracked**
- What's deliberately not built yet: `docs/product/ROADMAP.md`
- Local dev: `docs/development/LOCAL_DEVELOPMENT.md` (no Docker needed)
- AWS deployment: `docs/operations/AWS_DEPLOYMENT.md`

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
