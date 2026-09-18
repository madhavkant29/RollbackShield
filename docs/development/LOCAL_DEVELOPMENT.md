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
to override).

## Recommended order for a first run
1. `backend` (`mvn spring-boot:run`) — leave running.
2. `frontend` (`npm run dev`) in a second terminal — create a service,
   create a release, walk it through prepare → ready → activate a
   contract, watch the control room.
3. Or skip the UI and run `ProtectedDemo` for the same flow via stdout.
