# RollbackShield CLI

`cli/` — TypeScript, no runtime dependencies, calls the real control-plane
API. Install: `cd cli && npm install && npm run build`, then run
`node dist/index.js <command>` (or link it as `rollbackshield`).

## Connection

```
rollbackshield login --api-url https://control-plane.example --token <cognito-access-token>
rollbackshield logout
```

Config is stored at `~/.rollbackshield/config.json` (mode 0600). Env
overrides: `ROLLBACKSHIELD_API_URL`, `ROLLBACKSHIELD_TOKEN`,
`ROLLBACKSHIELD_CONFIG`. Under the `local` Spring profile the backend
accepts the fixed dev principal, so a token is optional for local use.

`--json` is accepted by every command for automation; human output is
tables/one line per record.

## Commands

```
integrations list
integrations connect --type AWS --name aws-hackathon --endpoint us-east-1 \
                     --credential-kind AWS_CONTROL_PLANE_ROLE
integrations connect --type GITHUB --name github --endpoint https://api.github.com \
                     --credential-kind GITHUB_TOKEN --secret-ref GITHUB_TOKEN
integrations test <integrationId>
integrations sync <integrationId>
integrations services <integrationId>
services list
services import <integrationId> <resourceExternalId> [--name <n>]
services show <serviceId>
status <serviceId>
observe <serviceId>
release create <serviceId>
release list <serviceId>
release inspect <serviceId|releaseId>
preflight <serviceId|releaseId>
rollback <serviceId|releaseId> [--reason <text>]
commit <serviceId|releaseId>
audit <releaseId>
```

Commands that take `<serviceId|releaseId>` resolve a service id to its
latest non-terminal release; a release id is used directly.

## Exit codes

- `0` success (for `preflight`: verdict is not `CANNOT_ROLLBACK`;
  for `commit`/`rollback`: the release reached the expected state),
- `1` API/operation failure (or `CANNOT_ROLLBACK` from `preflight`),
- `2` usage error (missing/invalid arguments).

The process sets its exit code and exits naturally (HTTP keep-alive is
disabled per request) so stdout is never truncated and no runtime
assertion can corrupt the code on Windows/Node 25 — pinned by a
spawned-process test (`cli/src/test/cli-process.test.ts`).

This makes the CLI directly usable as a pipeline gate without parsing
output:

```
rollbackshield preflight "$SERVICE_ID" --json > preflight.json || exit 1
```

Verified by `cli/src/test/client.test.ts` against a real local HTTP
server: bearer attachment, request bodies, stable error-code parsing,
preflight verdict.
