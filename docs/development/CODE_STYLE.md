# Code Style

## Java
- Constructor injection only — never `@Autowired` field injection.
- Prefer `record`/`enum`/`sealed interface` over mutable classes with
  `Map<String,Object>` state.
- No `Object`, no raw `Map<String,Object>` domain modeling.
- No `@SuppressWarnings` to silence a real design problem.
- Structured exceptions (`ApiException` subclasses) over generic
  `RuntimeException` for anything an API caller needs to distinguish.
- One reason to change per class — a `*ApplicationService` orchestrates,
  a `*Controller` translates HTTP, a `domain` class holds an invariant.

## TypeScript/React
- Client components (`'use client'`) only where interactivity is needed;
  everything else stays a server component by default.
- No `any` — the API client (`lib/api.ts`) types every request/response.
- Tailwind utility classes only, using the design tokens in
  `tailwind.config.ts` — no ad hoc hex colors in components.

## Commits
`feat(release): ...`, `fix(contract): ...`, `docs(aws): ...` — never
`update`/`fix stuff`/`wip`.

## What this project deliberately avoids
God services, `*ServiceImpl`/`*ServiceFactory` ceremony around a single
implementation, magic strings for anything with a fixed vocabulary
(error codes, audit actions, rule types all use enums/constants), copy-
pasted validation logic.
