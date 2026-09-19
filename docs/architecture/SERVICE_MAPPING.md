# Service mapping

## Model

`ServiceMapping` is the set of `ResourceBinding` edges between one
RollbackShield service and observed resources. Each binding carries:

- `role`: REPOSITORY, RUNTIME, ARTIFACT_REPOSITORY, QUEUE, EVENT_BUS,
  DATABASE, MIGRATION_SOURCE,
- `integrationId` + `externalId`: the exact provider resource,
- `confidence`: HIGH / MEDIUM / REQUIRES_CONFIRMATION,
- `evidence`: a sentence citing why the edge exists,
- `boundAt`.

## How bindings are created

- **Import** (automatic, evidence-based): runtime always; artifact
  repository from the runtime's image reference; repository/migration
  source from repository inspection (deployment file reference → HIGH,
  name match only → MEDIUM).
- **Operator confirmation** (explicit): `POST
  /api/v1/services/{serviceId}/mapping/bindings` accepts a binding only if
  a sync actually discovered the resource; evidence defaults to "confirmed
  by operator".

There is no arbitrary "add edge" API, and no code path fabricates an edge
to make a preflight pass.

## Confirmation semantics

Ambiguity is explicit, not hidden:

- `HIGH` — evidence proves the edge (image URI resolves to the repository,
  a deployment file references the service, an operator confirmed it).
- `MEDIUM` — a heuristic matched (e.g. repository name equals service
  name). The edge is stored and shown with its evidence, but it is **not
  used for decisions**: deployment observation does not record a source
  commit from a MEDIUM repository binding, so migration analysis reports
  UNKNOWN instead of trusting an unconfirmed hint.
- Confirmation is an operator re-adding the same binding
  (`POST .../mapping/bindings`), which replaces it at `HIGH` confidence
  with `evidence: "confirmed by operator"` and emits
  `SERVICE_MAPPING_CONFIRMED` in the audit trail. The Services page shows
  a "Confirm mapping" control on anything below HIGH.
- `REQUIRES_CONFIRMATION` is available for providers that can only suggest
  an edge; nothing in this build writes it without evidence.

## How mappings are used

- Deployment observation: RUNTIME binding identifies the runtime to
  observe; REPOSITORY binding enriches the observation with the commit the
  runtime runs.
- Preflight: RUNTIME → compute/health, ARTIFACT_REPOSITORY → artifact
  existence, MIGRATION_SOURCE → candidate migration range, DATABASE →
  schema evidence.
- Rollback orchestration: RUNTIME → execution port; ARTIFACT_REPOSITORY →
  artifact verification before touching the runtime.

## Honest gaps

`RESOURCE_NOT_FOUND` on add-binding means "sync first"; `MEDIUM`
confidence is surfaced in the UI as requiring review. A mapping with no
RUNTIME binding makes preflight return `NO_RUNTIME_MAPPING` (UNKNOWN),
which is the correct answer for a control-plane-only service.
