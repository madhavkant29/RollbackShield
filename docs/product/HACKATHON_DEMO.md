# Hackathon Demo (3 minutes)

## Opening (20s)
"We rolled the application back successfully. Production still broke."
Run `UnprotectedDemo` live — show v1 throwing `UnknownOrderStatusException`
and the stale queued job firing anyway, right after "rollback" completed.

## The reframe (15s)
"RollbackShield treats reversibility as a runtime property, not an
assumption you make after deploying."

## The protected run (90s)
Run `ProtectedDemo` (or walk the same flow through the Next.js control
room, service → release → contract → control room):
1. v2 tries `PARTIALLY_REFUNDED` — **blocked before it's ever written**,
   decided locally by the SDK, zero network calls on that path.
2. v2 writes a compatible value — allowed.
3. v2 queues async work tagged with the release epoch.
4. Rollback: epoch invalidated.
5. The job is delivered twice (at-least-once, modeled honestly) — both
   times `CANCEL`, zero irreversible side effects performed.
6. v1 reads the order after rollback — succeeds.
7. Show the reversibility report (`REVERSIBLE`) and the real audit trail
   — same sequence, timestamped, nothing staged.

## Close (15s)
"Deployment tools move your code backwards. RollbackShield makes sure
your system can actually go back." Show the control room's status badge
one more time.

## What NOT to over-explain
Skip the architecture diagram during the demo itself — it's in
`docs/architecture/SYSTEM_DESIGN.md` for the Q&A, not the pitch.

## If asked "is this deployed to AWS"
Honest answer: CDK synthesizes real CloudFormation for all five stacks
(verified), but this hasn't been deployed to a live AWS account as of
this build — see `docs/operations/AWS_DEPLOYMENT.md` for the exact
remaining steps. Don't claim more than that.
