# Local Development

No Docker/docker-compose needed — the `local` Spring profile is entirely
in-memory (persistence, events, work queue) and auth-bypassed, by design,
specifically so local dev needs zero external services.

## Backend
```
cd sdk-java && mvn install     # backend doesn't depend on this, but demo-app does
cd ../backend && mvn spring-boot:run
```
Verify: `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`.

## SDK
```
cd sdk-java && mvn test
```

## Demo (needs the backend already running)
```
cd demo-app && mvn compile exec:java -Dexec.mainClass=com.rollbackshield.demo.run.ProtectedDemo
cd demo-app && mvn compile exec:java -Dexec.mainClass=com.rollbackshield.demo.run.UnprotectedDemo
```
`ProtectedDemo` talks to `http://localhost:8080` by default
(`ROLLBACKSHIELD_CONTROL_PLANE_URL` to override, e.g. when 8080 is taken).

## Demo worker
```
cd demo-worker && mvn compile exec:java -Dexec.mainClass=com.rollbackshield.worker.WorkerMain
```

## Frontend
```
cd frontend && npm install && npm run dev
```
Opens on `http://localhost:3000`, talks to the backend at
`http://localhost:8080` by default (`NEXT_PUBLIC_ROLLBACKSHIELD_API_URL`
to override). No Cognito config is needed locally; the backend's `local`
profile accepts the fixed dev principal.

## Service credential (worker + SDK)
`GET /work/poll`, `POST /work/{jobId}/redeem`, and the SDK's policy fetch
require `X-RollbackShield-Service-Credential`. Under `local` it defaults to
`local-dev-service-credential` (the SDK, `ProtectedDemo`, and
`demo-worker` all default to the same value); override with
`ROLLBACKSHIELD_SERVICE_CREDENTIAL`.

## Frontend E2E tests
With the backend running and a production build in place:
```
cd frontend && npm run build && npm run test:e2e
```
This drives the real control room (service → release → contract →
rollback) in headless Chromium. The auth test only runs when the build was
made with `NEXT_PUBLIC_COGNITO_DOMAIN`/`NEXT_PUBLIC_COGNITO_CLIENT_ID`
set; otherwise it skips. Not part of CI (it needs a live backend).

## Recommended order for a first run
1. `backend` (`mvn spring-boot:run`) — leave running.
2. `frontend` (`npm run dev`) in a second terminal — create a service,
   create a release, walk it through prepare → ready → activate a
   contract, watch the control room.
3. Or skip the UI and run `ProtectedDemo` for the same flow via stdout.

## Connected flow locally (no AWS account needed)

The connectivity layer is runnable locally without cloud credentials:

1. **FLYWAY**: create a temp directory with `V42__...sql` files and
   connect an integration with `credential.kind = NONE`, `endpoint` =
   directory, `configuration.directory` = directory. The connection test
   reads the directory for real; nothing is fabricated.
2. **POSTGRESQL**: `docker run postgres:16`, connect with
   `credential.kind = POSTGRES_PASSWORD` and `secretReference` naming an
   environment variable that holds the password (never pass the password
   in the request).
3. **KUBERNETES**: put a kubeconfig in an env var, use
   `credential.kind = KUBERNETES_KUBECONFIG`.
4. **AWS**: needs real credentials in the backend process environment
   (or `~/.aws/credentials`); without them the connection test fails
   honestly and the integration shows ERROR. LocalStack-based tests cover
   STS/SQS/Logs/Events; ECS/ECR need a real account
   (`docs/operations/AWS_DEPLOYMENT.md`).
5. Import a discovered runtime, **Observe deployment**, **Create release
   from observation**, then open the control room: the preflight shows
   PASS/FAIL per dimension with evidence, and reports blockers (e.g.
   `NO_RUNTIME_MAPPING`, `MIGRATION_ANALYSIS_UNAVAILABLE`) instead of
   assuming safety.

CLI against the same local backend:
`cd cli && npm install && npm run build && node dist/index.js login --api-url http://localhost:8080`
(no token needed under the `local` profile), then
`node dist/index.js services list`.
