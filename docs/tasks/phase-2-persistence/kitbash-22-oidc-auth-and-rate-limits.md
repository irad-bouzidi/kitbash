# kitbash-22-oidc-auth-and-rate-limits

**Phase** 2 — Persistence · **Depends on** `kitbash-21-postgres-schema-flyway` · **Plan** §13, §18

## Goal

One internal team behind the existing SSO, with the authorization split and the limits §13
specifies — and modest limits, because this is not a public service.

## Context

§18 settles the audience: an internal team behind existing SSO, auth landing in phase 2, rate
limits staying modest. That shapes the whole task — no user registration, no password handling,
no tenancy. `owner_id` is just the token subject.

One limiter detail from §13 is easy to miss and materially affects behaviour: **cache hits do
not consume budget**. Regenerating the house stack repeatedly is exactly the usage the product
wants to encourage, and it costs nothing to serve.

## Scope

- **OIDC** against the existing identity provider; `owner_id` derived from the token subject.
- **Authorization** (§13):
  - catalog read and `generate` — any authenticated user,
  - preset write and `public` visibility — requires a role,
  - `verify` — every authenticated user (throttled by concurrency, not count; `kitbash-37`).
- **Rate limits** on `/generate` and `/share`, keyed by user then IP.
- **Cache hits do not consume budget** — requires the limiter to run after the cache lookup,
  which is a wiring decision worth making explicitly.
- `429` responses carry the §14 envelope plus a retry hint.
- A local dev profile with a stub issuer, so the whole stack runs via `docker compose up`
  without the corporate IdP.
- Unauthenticated access to `/health` retained; everything else closed.

## Out of scope

Multi-tenancy, user management, per-team quotas. §1 is explicit that multi-tenant SaaS is a
non-goal for v1.

## Implementation notes

- Keep the role check in one place and name the roles in configuration, not in code, so the
  mapping to the IdP's groups can change without a deploy.
- Rate limiting state can be in-memory for a single instance; if it must be shared, say so
  explicitly in the MR rather than assuming a future Redis.
- Test the limiter with a burst test that also asserts cache hits pass through free — that is
  the requirement most likely to regress silently.

## Files and modules touched

`/server/api/**` (security config, limiter, error mapping), `/compose.yaml` (stub issuer),
`/web/src` auth wiring.

## Done when

- An unauthenticated `/generate` is rejected with the structured envelope.
- A burst test shows the limiter engaging, and a repeated identical generation served from
  cache consumes no budget.
- `docker compose up` gives a working login against the stub issuer.
