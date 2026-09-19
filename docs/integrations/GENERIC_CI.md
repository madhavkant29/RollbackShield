# Generic CI integration

Any CI/CD system can integrate without a native plugin: the product is
REST + CLI, and both are real.

## The contract

```
POST /api/v1/services/{serviceId}/observations   # observe the connected runtime now
POST /api/v1/services/{serviceId}/releases       # create/reuse the release for that observation
GET  /api/v1/releases/{releaseId}/reversibility  # preflight: verdict + blockers + evidence
POST /api/v1/releases/{releaseId}/contracts      # activate the protected window (rules JSON)
POST /api/v1/releases/{releaseId}/rollback       # rollback
POST /api/v1/releases/{releaseId}/commit         # commit
```

Auth: `Authorization: Bearer <Cognito access token>`; under the `local`
profile the dev principal is applied automatically. Failures use stable
codes (`docs/API_GUIDE.md`), never message strings.

## Pipeline shape

```
build -> test -> observe+release -> preflight (fail on CANNOT_ROLLBACK)
      -> deploy -> activate contract -> (window) -> rollback or commit
```

The preflight `verdict` is the gate: `CAN_ROLLBACK` / `UNKNOWN` /
`CANNOT_ROLLBACK`. Do not parse human text; use `verdict` and
`checks[].blockerCode`.

## Jenkins (declarative, example)

```groovy
stage('RollbackShield preflight') {
  steps {
    withEnv(["ROLLBACKSHIELD_TOKEN=${env.COGNITO_TOKEN}"]) {
      sh 'rollbackshield preflight "$SERVICE_ID" --json > preflight.json'
    }
  }
}
```

## Raw curl gate

```bash
release=$(curl -sf -X POST "$BASE/api/v1/services/$SERVICE_ID/observations" -H "$AUTH")
curl -sf -X POST "$BASE/api/v1/services/$SERVICE_ID/releases" -H "$AUTH" > release.json
verdict=$(curl -sf "$BASE/api/v1/releases/$(jq -r .releaseId release.json)/reversibility" \
  -H "$AUTH" | jq -r .verdict)
[ "$verdict" = "CANNOT_ROLLBACK" ] && exit 1 || true
```

LocalStack and mocks may supplement CI tests; they do not replace the
live verification required by `docs/operations/AWS_DEPLOYMENT.md`.
