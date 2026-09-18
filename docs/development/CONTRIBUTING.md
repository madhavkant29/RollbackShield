# Contributing

## Before opening a PR
1. `cd sdk-java && mvn test`
2. `cd backend && mvn test` (includes `ModuleBoundaryTest` — a boundary
   violation fails the build, not just a review comment)
3. `cd frontend && npm run build`
4. `cd infrastructure && npm run build && npx cdk synth --all`

## Scope discipline
This is a hackathon v0.1. Before adding anything, check
`docs/product/ROADMAP.md` — if it's listed there, it's explicitly
deferred; don't build it early "while you're in there." If it's not on
the P0 list in the original product brief and not a bug fix, it probably
doesn't belong in this PR.

## Module boundaries
Read `docs/development/MODULE_BOUNDARIES.md` first. `ModuleBoundaryTest`
will fail your build if domain code reaches for Spring/AWS/Jackson, or if
an application service reaches into another module's adapter package
directly.

## Docs
If you change a domain concept, a state machine, an API shape, or an
architecture decision, update the matching doc in the same PR — see
`docs/product/LIMITATIONS.md` for the standing reminder that docs must
match implementation, not aspiration.
