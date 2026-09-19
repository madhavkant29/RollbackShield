# Reversibility graph and evidence

## The question

"Can this system safely return to the previous release right now?" is
answered by a preflight report built from live state:

```
ReversibilityVerdict: CAN_ROLLBACK | CANNOT_ROLLBACK | UNKNOWN
ReversibilityStatus:  REVERSIBLE | AT_RISK | COMMITTED | UNKNOWN
checks[]:             policy, database, async, compute, artifact, health
evidence[]:           subject --relation--> object   (source, detail)
```

`verdict` is derived from blocker severity, not a score:
any `BLOCKING` blocker → CANNOT_ROLLBACK; otherwise any UNKNOWN/REVIEW →
UNKNOWN; all pass → CAN_ROLLBACK. There is no percentage anywhere.

## Evidence paths (examples)

```
payments@task-definition:2 --runs-as--> task-definition:2        (aws-hackathon)
payments@task-definition:2 --source-commit--> abc1234@main       (deployment-observation)
payments@task-definition:2 --rollback-target--> task-definition:1 (aws-hackathon)
sha256:previous --exists-in--> payments                          (aws-hackathon)
V219 --destructive-change--> ALTER TABLE customers DROP COLUMN ... (flyway)
src/.../V219__drop.sql --breaks--> payments@task-definition:1    (flyway)
abc1234 --introduced-migration-set--> V219__remove_legacy_tax_code.sql, ... (github:acme/payments)
payments@task-definition:2 --mapped-runtime--> cluster/payments  (aws-hackathon)
payments@task-definition:2 --mapped-queue--> payments-jobs       (aws-hackathon)
payments@task-definition:2 --protected-by--> contract v17        (rollback-contract)
payments --health--> HEALTHY                                     (aws-hackathon)
payments --rollback-execution--> unsupported                     (kubernetes:cluster)
```

The exact spec example renders as:

```
checkout@task-definition:107 --runs-as--> task-definition:107
checkout@task-definition:107 --source-commit--> 9f3ab21@main
9f3ab21 --introduced-migration-set--> V219__remove_legacy_tax_code.sql
V219 --destructive-change--> db/migration/V219__remove_legacy_tax_code.sql
db/migration/V219__remove_legacy_tax_code.sql --breaks--> checkout@task-definition:106
```

## Blocker paths: WHY NOT, as structured API data

Every failed check yields a `BlockerPath { code, severity, description,
path[] }` in the preflight response
(`GET /releases/{id}/reversibility` -> `blockerPaths`). The path contains
only that dimension's evidence, selected by a deterministic
code-to-relation table (`PreflightReport.buildBlockerPaths`); there are no
heuristics and no scoring. Clients can render or gate on the structure
without parsing prose.

```
DESTRUCTIVE_DATABASE_MIGRATION (BLOCKING)
  path: [ V219 --destructive-change--> <file>, <file> --breaks--> checkout@106 ]
ROLLBACK_ARTIFACT_MISSING (BLOCKING)
  path: [ sha256:abc --missing-from--> aws-hackathon, ... --rollback-target--> ... ]
ROLLBACK_EXECUTION_UNSUPPORTED (UNKNOWN)
  path: [ payments --rollback-execution--> unsupported (kubernetes:cluster) ]
```

No graph database is used: access patterns are "evaluate one release, read
its paths" and are served from the repositories and connectors already
involved in preflight.

## Dimensions and blockers

| Dimension | Check | Blockers |
| --- | --- | --- |
| Policy | Data compatibility, Policy freshness | `NO_ACTIVE_CONTRACT`, `POLICY_EXPIRED` |
| Async | Queued work fencing | `UNFENCED_WORK_PENDING` |
| Database | Database compatibility | `DESTRUCTIVE_DATABASE_MIGRATION` (blocking), `DATABASE_MIGRATIONS_UNREVIEWED`, `MIGRATION_ANALYSIS_UNAVAILABLE` |
| Compute | Compute restore path | `NO_ROLLBACK_TARGET` (blocking), `NO_RUNTIME_MAPPING`, `RUNTIME_NOT_OBSERVABLE` |
| Artifact | Rollback artifact availability | `ROLLBACK_ARTIFACT_MISSING` (blocking), `ARTIFACT_IDENTITY_UNKNOWN`, `ARTIFACT_REPOSITORY_NOT_MAPPED` |
| Health | Runtime health observability | `HEALTH_VERIFICATION_UNAVAILABLE` |

Blocker severities live in `ReversibilityBlocker.Severity`; every failed
check must carry a blocker (enforced by `ReversibilityCheck`'s constructor).

## Rollback orchestration uses the same checks

`ArtifactAvailabilityChecker` is shared: the check the operator sees in
preflight is exactly the check that gates `RollbackOrchestrator`. A
rollback that fails any step marks the release `FAILED` with the step name
and provider message — `ROLLED_BACK` is never displayed for a rollback
that did not converge.

## Where the graph is not

- No global graph store; evidence is generated per report from live reads.
- No inferred edges: every evidence row cites a provider observation or a
  classification with its file path.
- No replay certification (roadmap) and no consumer-lease modeling yet.
