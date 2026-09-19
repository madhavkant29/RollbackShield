# Frontend / API Edge (deployed hackathon architecture)

Status: IMPLEMENTED and verified live (2026-09-19). This documents the
actual deployed edge, including its deliberate hackathon tradeoffs.

## Deployed path

```
Browser
  → https://main.d372yre6lvoau6.amplifyapp.com        (AWS Amplify Hosting, Next.js)
  → https://t4dv6crzic.execute-api.ap-south-1.amazonaws.com  (API Gateway HTTP API)
  → http://<alb-dns>:80                                (public internet-facing ALB)
  → Spring Boot on ECS Fargate (rollbackshield cluster)
  → DynamoDB / SQS / EventBridge / ECR / ECS
```

Authentication: Cognito Hosted UI **Managed login v2** on the prefix domain
`rollbackshield-hackathon-473671.auth.ap-south-1.amazoncognito.com`.
The SPA uses PKCE and sends the **ID token** as the Bearer credential (the
control plane reads `custom:organization_id`, which Cognito emits only in
the ID token). The browser calls API Gateway **directly** (cross-origin);
CORS on API Gateway answers preflight and the backend adds
`Access-Control-Allow-Origin` for exactly the Amplify origin.

## Why this shape

- No custom domain was purchased for the hackathon.
- The default ALB hostname cannot present trusted browser TLS (no ACM
  certificate is possible for `*.elb.amazonaws.com`).
- Amplify reverse-proxy rewrites require an **HTTPS** target, and in
  practice do not carry API write methods/bodies reliably, so the
  `/api/*` proxy was removed from the production path.
- CloudFront creation is currently **blocked by account verification**
  (`AccessDenied: Your account must be verified before you can add new
  CloudFront resources`).
- API Gateway HTTP API provides a managed HTTPS endpoint with no domain,
  and a public HTTP proxy integration avoids VPC Link complexity and cost
  for this hackathon deployment.

## Tradeoffs and risks (explicit)

- Frontend and API are **cross-origin**; CORS is therefore required and is
  restricted to the Amplify origin (plus `http://localhost:3000` for local
  development) — never `*`.
- The ALB remains **internet-facing** and technically directly reachable on
  port 80. API Gateway is an HTTPS front door, not the sole network
  security boundary. Application security still rests on Cognito/JWT
  (verified: unauthenticated requests return 401 at Spring Security).
- The API Gateway → ALB hop uses **plain HTTP** inside AWS's network.
- API Gateway HTTP API integration timeout is ~30 seconds. The
  **synchronous** rollback request can exceed it and return 504 while the
  orchestrator continues server-side (already observed at the ALB's 60s
  limit). Clients must poll `GET /releases/{id}` for final state; the UI
  does not yet auto-reconcile a 504.
- This is intentionally a hackathon deployment architecture, **not** the
  desired long-term production edge.

## Verified end to end

- API Gateway preflight: `OPTIONS /api/v1/services` with the Amplify origin
  returns 200 with `access-control-allow-origin`, methods
  `GET,POST,PUT,PATCH,DELETE,OPTIONS`, header `authorization`.
- Backend adds `access-control-allow-origin` for the Amplify origin.
- Production Playwright smoke against the public URL (real Cognito sign-in,
  no token injection): authenticated GET and POST through Amplify → API
  Gateway → ALB → Spring succeed; Integrations shows CONNECTED/HEALTHY and
  the connection **Test** POST succeeds; Services (`payments-verify`),
  Releases, Release Control Room, Reversibility and Audit render real
  backend data; zero `localhost:8080` requests; zero browser-visible HTTP
  requests.

## Post-hackathon target (not implemented)

```
Frontend (Amplify or any host)
  → API Gateway HTTPS
  → VPC Link
  → private/internal ALB
  → ECS Fargate
```

plus an asynchronous rollback command (`202` + operation id + status
resource) so long-running rollbacks never depend on a synchronous HTTP
response. This removes direct public access to the ALB and keeps HTTPS at
the public edge with least-privilege network boundaries.
