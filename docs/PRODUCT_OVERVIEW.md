# Product Overview

**RollbackShield** is a deployment reversibility control plane. It answers
one question mechanically: *can this production system safely return to
the previous release right now?*

## The problem

Deployment tools can move compute backward (v42 → v41) reliably. They
cannot guarantee the *system* can go back, because a "successful" rollback
of compute doesn't undo:
- data the candidate release already wrote in a shape the previous release
  doesn't understand (a new enum value, a field the old code treats as
  required, a value the old code special-cases incorrectly)
- asynchronous work the candidate release queued, which may still execute
  after compute has rolled back
- policy/config the candidate release changed

A team can roll back compute and still have a broken, actively-mutating
production system. RollbackShield exists to make "is this release still
reversible" an answerable, provable question at any point during a
rollout — and to actually block the writes and fence the work that would
make it unanswerable.

## What the connected product proves

The engine above is the enforcement core. The product around it is
connectivity: a customer connects their systems (AWS, GitHub, Kubernetes,
PostgreSQL, Flyway), RollbackShield discovers and maps their services,
observes a real deployment, and answers the reversibility question from
that evidence:

```
connect --> discover --> map --> observe deployment --> preflight
        --> protected rollback window --> roll back or commit
        --> verify --> permanent evidence
```

- **Compute**: the previous runtime revision (ECS task definition,
  Kubernetes ReplicaSet revision) is identified from the provider, not
  from labels.
- **Artifact**: the previous revision's immutable digest is verified to
  still exist; a missing artifact is `CANNOT_ROLLBACK` with
  `ROLLBACK_ARTIFACT_MISSING`.
- **Database**: migrations introduced between the two observed commits are
  isolated by a real GitHub diff and classified; a destructive one is
  `CANNOT_ROLLBACK` with `DESTRUCTIVE_DATABASE_MIGRATION`.
- **Async**: candidate work is epoch-fenced; rollback invalidates the
  epoch before any irreversible effect can fire.
- **Policy**: a versioned rollback contract, enforced locally by the SDK.
- **Health**: desired/running/available state is read from the runtime
  after rollback; `ROLLED_BACK` is never reported for a rollback that did
  not converge.

The verdict is `CAN_ROLLBACK | CANNOT_ROLLBACK | UNKNOWN` with explicit
evidence paths — never a score.

## What v0.1 proved (the engine)

The scenario in `docs/product/HACKATHON_DEMO.md`: a checkout service's
`Order.status` enum gains `PARTIALLY_REFUNDED` in v2, and v2 queues an
async refund webhook job. Without protection, rolling back compute breaks
v1 and the queued job fires anyway. With RollbackShield: the SDK blocks
the incompatible write before it's ever persisted, the queued job is
fenced by release epoch and cannot fire after rollback, and the control
plane can prove all of this with an audit trail.

## What RollbackShield is not

Not a CI/CD tool, not a Kubernetes dashboard, not feature flags, not a
generic observability or policy engine, not a schema registry, not a
database migration tool, not an AI assistant. It owns exactly one thing:
deployment reversibility. See `docs/product/ROADMAP.md` for what's
deliberately out of scope and `docs/product/LIMITATIONS.md` for the
current verification state — including the one open item: a live AWS
account run.

## Who it's for

An engineer or SRE running a risky rollout (a data-model change, a new
enum value, a new async workflow) who wants a real answer to "can I still
go back" instead of an assumption.
