# GitHub connector

`connectors/github/adapter/GitHubConnector` — capabilities
`SOURCE_DISCOVERY`, `SOURCE_METADATA`. Uses the REST API over plain HTTP;
no SDK, no provider types above the adapter.

## Authentication

- `GITHUB_APP` (the intended production mode): app id + installation id +
  private-key secret reference. `GitHubAppJwt` mints a 9-minute RS256 JWT
  (PKCS#1 or PKCS#8 PEM accepted) and exchanges it for a short-lived
  installation token, cached until five minutes before expiry.
- `GITHUB_TOKEN`: token resolved from a secret store. Local/dev only; not
  the long-term design.
- `GITHUB_PUBLIC`: unauthenticated, public repositories only. Requires
  `configuration.owner`; read-only; GitHub's anonymous rate limit applies.
  Intended for demos and public-repo evaluation, never for private code.

Connection test: `GET /user` (token mode),
`GET /installation/repositories?per_page=1` (app mode), or
`GET /users/{owner}` (public mode). Failures are returned with GitHub's
own message; there is no code path that marks an integration CONNECTED
without this call succeeding.

## Live verification (performed)

The connector has discovered real repositories through the public mode
against `api.github.com`: connection test
`GitHub API reachable (public, no credentials); owner madhavkant29 found`,
sync discovered 11 repositories (0 errors), including
`madhavkant29/RollbackShield` with its real default branch and push
timestamp. The same run path is what the App/token modes use; a real App
installation still awaits a customer account (see LIMITATIONS).

## Metadata

`inspectSource(repo)` reads repository info, recent commits, tags, and the
recursive git tree, then extracts:

- Dockerfiles,
- migration paths (any `.../db/migration/*.sql`, `.../migration/*.sql`,
  `.../flyway/*.sql`),
- Liquibase changelogs (`.../db/changelog/*.xml|yaml|yml|json|sql`, or any
  path containing `liquibase`/`changelog`) — detected for mapping and
  evidence; the deterministic SQL analyser in this build classifies
  Flyway-style SQL only, so a Liquibase changelog yields a
  MIGRATION_SOURCE binding but migration analysis reports UNKNOWN rather
  than pretending to parse XML,
- deployment files (`.github/workflows/*`, docker-compose, k8s manifests,
  argocd, deployment.yaml).

`fetchMigrationFiles` fetches raw file contents (capped at 200).
`changedMigrationFilesBetween(base, head)` uses the compare API and
returns only **added** migration files, which is what isolates the
migrations a release introduced.

## What the connector is used for

- discovery → repositories in the Integrations page,
- service mapping → proving a deployment file references the service,
- deployment observation enrichment → recording which commit the runtime
  runs,
- preflight → isolating candidate migrations and classifying them.

## No fabricated data

Repository lists and file contents come from GitHub responses; an empty
list means GitHub returned an empty list. Errors surface as connection
state `ERROR` with the provider message.

## Verification status

`GitHubConnectorTest` drives the connector over real HTTP against a local
stub that verifies the RS256 signature of the app JWT with the matching
public key, checks bearer attachment, and serves user/repo/commits/tags/
tree/contents responses. A real GitHub App installation has not been
driven; see `docs/product/LIMITATIONS.md`.
