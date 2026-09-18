# Policy Distribution

## The one call
`GET /api/v1/contracts/{contractId}/policy` — called only by
`PolicyCache`'s background refresh (`sdk-java`'s `HttpPolicySource`),
never per mutation. Proven end-to-end in Phase F against a real local
`HttpServer`.

## Wire format
Flat, discriminated-by-`type` rule objects (see `API_GUIDE.md`) — chosen
over JSON polymorphism so the SDK's dependency-free `MinimalJson` doesn't
need subtype dispatch (ADR-003).

## Freshness states (`PolicyState`)
`FRESH` → `STALE` → `EXPIRED` → the cache's last-known-good snapshot is
retained regardless; `MISSING` if no fetch has ever succeeded; `INVALID`
if a fetch returned malformed JSON.

## Enforcement modes × failure behavior
`EnforcementConfig` pairs `Mode` (`OBSERVE`/`WARN`/`ENFORCE`) with
`FailureBehavior` (`FAIL_OPEN`/`FAIL_CLOSED`), configurable per call site
— a financial mutation might use `ENFORCE`+`FAIL_CLOSED`
(`EnforcementConfig.enforceFailClosed()`), low-impact metadata
`WARN`+`FAIL_OPEN` (`.warnFailOpen()`). No single global answer (§11).

## Auth gap (known, documented)
`HttpPolicySource` sends no Authorization header — see
`docs/product/LIMITATIONS.md`.
