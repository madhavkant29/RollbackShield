# Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `mvn spring-boot:run` fails to compile | This build was never compiled in the environment that wrote it (no Maven Central access there) — first real compile happens on your machine | Paste the exact error; fix immediately rather than guessing |
| `401` on every request under `aws` profile | JWT missing/expired/wrong issuer | Check `COGNITO_ISSUER_URI` matches the deployed user pool; re-authenticate |
| `404 RELEASE_NOT_FOUND` but you're sure it exists | You're not the owning organization (`local` profile always uses the fixed dev org) or a typo'd UUID | `GET /api/v1/services` to confirm what org context you're in |
| `409 STATE_TRANSITION_FAILED` | Concurrent write raced you | Re-`GET` the release, retry the transition against current state |
| `409 CONTRACT_VERSION_CONFLICT` | Release already has an active contract | One active contract per release by design; supersede instead (not yet built — v0.2) |
| SDK evaluates everything as `BLOCK` unexpectedly | `EnforcementConfig.FailureBehavior.FAIL_CLOSED` + policy `MISSING`/`EXPIRED` | Check `PolicyCache.state()` and `lastRefreshFailed()`; confirm the control plane is reachable from the app's network |
| Frontend shows "Could not reach the control plane" | Backend not running, or `NEXT_PUBLIC_ROLLBACKSHIELD_API_URL` unset/wrong | `curl http://localhost:8080/actuator/health` first |
| `cdk synth` fails locally | Usually a missing `npm install` or Node version mismatch | This exact synth succeeded in the sandbox that wrote it against Node's npm registry — if it fails for you, the error is almost certainly environmental (Node/npm version), paste it |
| Frontend build fails on fonts | `next/font/google` needs network access to fonts.googleapis.com at build time | Should work with normal internet; if building in a restricted CI, swap back to the system-font stack the way this was verified (see `tailwind.config.ts` comment) |
