# GitHub Actions integration

Three composite actions in `.github/actions/`, plus an example workflow in
`docs/integrations/examples/rollbackshield-deploy.yml`. They only call
the control-plane API — there are no local compatibility decisions, and
no fallback that pretends to succeed.

## `rollbackshield-preflight`

Observes the connected runtime, creates or reuses the release for that
observation, and evaluates reversibility:

- outputs `release-id` and `verdict`,
- prints every blocker with its stable code,
- fails on `CANNOT_ROLLBACK`,
- fails on `UNKNOWN` too unless `fail-on-unknown: "false"`.

Inputs: `api-url`, `token`, `service-id`, `fail-on-unknown`.

## `rollbackshield-status`

Queries the final state of a release at any point: lifecycle state, epoch,
and the live reversibility verdict with every blocker. Outputs `status`
and `verdict`; exits non-zero on `CANNOT_ROLLBACK`. Use it after a deploy,
before opening the window, or as a periodic verification step.

Inputs: `api-url`, `token`, `release-id`.

## `rollbackshield-protect`

Activates the rollback contract after a successful deploy, opening the
protected window. Rules come from a JSON file in the repository
(`rules-file`), so the deterministic compatibility rules are reviewed like
code.

Inputs: `api-url`, `token`, `release-id`, `rules-file`,
`rollback-window-seconds` (default 7200).

## Required secrets/variables

- `ROLLBACKSHIELD_API_URL` (secret), `ROLLBACKSHIELD_TOKEN` (secret),
- `ROLLBACKSHIELD_SERVICE_ID` (variable), e.g. from
  `rollbackshield services list`.

## Example layout

```
- uses: your-org/rollbackshield/.github/actions/rollbackshield-preflight@main
  id: preflight
  with: { api-url: ..., token: ..., service-id: ... }
- run: ./scripts/deploy.sh
- uses: your-org/rollbackshield/.github/actions/rollbackshield-protect@main
  with: { api-url: ..., token: ..., release-id: ${{ steps.preflight.outputs.release-id }},
          rules-file: .rollbackshield/rules.json }
```

## Verification status

The actions are real API calls but have not been executed on a GitHub
runner; treat them as reviewed, not run, until the example workflow is
exercised. The endpoints they call are covered by backend tests.
