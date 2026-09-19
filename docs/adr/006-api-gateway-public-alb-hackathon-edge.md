# ADR-006: API Gateway public-HTTP-proxy ALB edge for the hackathon frontend

Status: Accepted (hackathon deployment). Superseded-by-design by the
private-origin target described at the end.

## Context

The Next.js control room is hosted on Amplify (`*.amplifyapp.com`, no
custom domain). The API runs as a Spring Boot service behind an
internet-facing ALB. For browser traffic we needed HTTPS end to end, but:

- no custom domain / ACM certificate is available, and an ALB cannot serve
  trusted TLS on its `*.elb.amazonaws.com` hostname;
- Amplify reverse-proxy rewrites require HTTPS targets and did not carry API
  write methods/bodies reliably;
- CloudFront creation is blocked on this account pending AWS Support
  verification.

## Decision

Put an **API Gateway HTTP API** in front of the existing public ALB with a
public `HTTP_PROXY` integration (`http://<alb>/api/{proxy}`), a single
`ANY /api/{proxy+}` route and the default `$stage`/`execute-api` HTTPS
endpoint. The browser calls API Gateway directly and CORS is configured on
the API for exactly the Amplify origin (`+ http://localhost:3000`), with
`Authorization`/`Content-Type` allowed and caching disabled. The backend
also emits `Access-Control-Allow-Origin` for the same origin.

## Consequences

Positive: HTTPS at the public edge without a domain; no new long-running
infrastructure; preflight answered by API Gateway; all methods, query
strings, bodies and the `Authorization` header preserved (verified live).

Negative / accepted risks: frontend↔API is cross-origin; the ALB stays
public and directly reachable; API Gateway→ALB is plain HTTP; the 30s
integration timeout can 504 a synchronous rollback that is still running
(clients must poll release state); API Gateway is a front door, not a
network security boundary. Authentication remains Cognito/JWT at the
application layer.

## Alternatives rejected for the hackathon

- CloudFront (blocked by account verification), custom domain + ACM (not
  purchased), VPC Link + private ALB (extra complexity/cost for a
  controlled demo account), keeping the Amplify `/api` proxy (write path
  broken).

## Post-hackathon direction

API Gateway → **VPC Link** → private/internal ALB → ECS, with an
asynchronous rollback operation/status resource, so the public origin is
the only reachable edge and long operations never ride a synchronous
request. See `docs/architecture/FRONTEND_API_EDGE.md`.
