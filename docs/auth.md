# Authentication, authorization and limits

§18 settles the audience: **one internal team, behind the SSO they already have.** Everything here
follows from that. There is no registration, no password handling and no tenancy — a caller arrives
with a token from the identity provider the company already runs, and `owner_id` is that token's
subject.

## What is open

Nothing, except `/actuator/health`.

That one has to be open: a health check that needed SSO could not report that SSO is down, and an
orchestrator would kill a service whose only fault was that its identity provider hiccupped.
Everything else — including the OpenAPI document and anything added tomorrow — is closed until
somebody opens it deliberately, because `anyRequest().authenticated()` is the last rule.

A refusal is the §14 envelope, not an empty 401:

```json
{
  "title": "Not signed in",
  "status": 401,
  "detail": "This request carried no valid token.",
  "error": "UNAUTHENTICATED",
  "hint": "Sign in and retry. Every endpoint except /actuator/health needs a token from the identity provider."
}
```

## Who may do what (§13)

| Action | Needs |
| --- | --- |
| Read the catalog, validate, preview, **generate** | any authenticated user |
| Read a preset | any authenticated user |
| Write a preset | the `kitbash-author` role |
| Publish a preset | the `kitbash-publisher` role |
| Verify a selection (`kitbash-37`) | any authenticated user, throttled by *concurrency* rather than by count |

Generation needs no role on purpose: it is the product, and gating it would mean onboarding
everybody twice. Presets are shared objects, so writing one is a role and publishing one to
everybody is a second role.

Role names and the claim they arrive in are **configuration**, not code:

```
KITBASH_OIDC_ROLES_CLAIM=roles           # groups, realm_access.roles, whatever the IdP calls it
KITBASH_ROLE_PRESET_AUTHOR=kitbash-author
KITBASH_ROLE_PUBLISHER=kitbash-publisher
```

The mapping to an identity provider's groups is the part most likely to change, and renaming a
group should not be a deploy.

## Rate limits

Modest, per §13 and §18 — one internal team, not a public service. They exist to stop a loop in
somebody's script from saturating the box, not to meter usage.

| Bucket | Default | Environment variable |
| --- | --- | --- |
| `/generate` | 60/min, burst 20 | `KITBASH_LIMIT_GENERATE_PER_MINUTE`, `KITBASH_LIMIT_GENERATE_BURST` |
| `/share` | 30/min, burst 10 | `KITBASH_LIMIT_SHARE_PER_MINUTE`, `KITBASH_LIMIT_SHARE_BURST` |

A token bucket rather than a fixed window: a window lets a caller spend a whole minute's budget in
its last second and the next minute's in its first, which is twice the intended rate at the moment
load is highest.

Keyed by **user, then IP** — in that order. Keying by IP first would put everyone behind one office
NAT into a single bucket, which is a limit on the company rather than on a caller.

**A cache hit consumes no budget**, and where that is enforced matters more than it looks.
Regenerating the house stack repeatedly is the usage this product most wants to encourage, and
serving bytes that already exist costs nothing worth metering — so the limiter is called from
inside the request handler, *below* the cache lookup, rather than from a servlet filter that would
have charged before the handler ever got the chance to find them. `CacheHitsAreFreeTest` sets the
budget to one request a minute and asks for the same project five times.

The limiter's state is **in memory**. One internal team means one instance; if this is ever run as
more than one, the limits become per instance. That is a real behaviour change, and it is written
down here rather than assumed away with a Redis that does not exist.

## The nightly's input carries no names

`GET /api/v1/history/cells` serves the twenty most-generated selections to the verification runner
(§40). It is authenticated like everything else, and it carries **no project or package names**: §10
draws that line, and the API replaces every variable with a neutral set before the list leaves it —
by building a new envelope rather than filtering fields, so a variable added tomorrow does not
arrive by default. `HistoryCellsTest` asserts that nothing identifying survives, against the whole
serialised envelope rather than field by field.

The failure this prevents is quiet: a name that leaks breaks nothing. The cell builds, the matrix is
green, and a customer's package name sits in a CI log on somebody else's retention schedule until
the day somebody looks.

## Verification is limited by concurrency, not by count

`POST /api/v1/verify` is the one endpoint whose cost is measured in minutes rather than
milliseconds, so it is the one with a different kind of limit. §18 opens it to every authenticated
user and §12 says why that is affordable: **not because it is cheap, but because the work is
deduplicated.**

| Control | Default | Environment variable |
| --- | --- | --- |
| Workers (runs in containers at once) | 4 | `KITBASH_VERIFY_WORKERS` |
| Per caller, in flight | 1 | `KITBASH_VERIFY_PER_USER` |
| Queue depth before refusing | 32 | `KITBASH_VERIFY_QUEUE_DEPTH` |
| Hard timeout on a run | 15 minutes | `KITBASH_VERIFY_TIMEOUT_MINUTES` |

A run is keyed by `(selection_hash, catalog_digest)` and the key is a partial unique index in the
database, so two people asking the same question at the same moment produce **one** container and
two answers. The second is instant.

The nightly matrix does **not** write rows here. §12 keeps it independent of the API and its
persistence — it has no database connection, and that is what makes a red cell mean the generator
is broken rather than the deployment. Its results reach a user as the wizard's badges
(`GET /api/v1/verification`, `kitbash-38`) rather than as a warm dedupe, so the first person to
verify an enumerated combination still pays for a container even though the nightly built it hours
earlier.

Two refusals share the 429 and mean different things, so they carry different bodies:

- `VERIFY_ALREADY_RUNNING` names the run you already have, because the useful answer to "one at a
  time" is *which one*.
- `VERIFY_QUEUE_FULL` carries `queueDepth`. Refusing is deliberate: accepting the request and
  queueing it for an hour is the same outcome delivered dishonestly, and the caller sits on a
  spinner instead of deciding.

A run that fails is **not** covered by the dedupe index. That asymmetry is on purpose — a failure
is a result somebody may want to reproduce once a recipe is fixed — and there is a test for it so
nobody tidies it away.

`GET /api/v1/verification` and `GET /api/v1/verification/cells/{cell}/log` are the read-only half
and need **no database**: it serves what the
nightly published, so a deployment without persistence still tells a user which combinations are
known to be red. Its entity tag covers the catalog digest *and* the run that produced the results —
the catalog alone would let a client keep yesterday's badges through tonight's nightly, and the run
alone would let it keep them across a catalog change.

A cell's log is served only for a cell the **currently published run names**. That is the whole
check: an id the results do not mention has no log worth serving, which happens to mean an id out
of a URL can never become a path.

The queue depth and the number of runs in containers are published as
`kitbash_verify_queue_depth` and `kitbash_verify_running`. The 429 body tells one caller whether to
wait; the gauges tell whoever runs this whether four workers is the right number, which is a
different question.

Logs are served through the API rather than as a link into the bucket, and they expire with
everything else after thirty days (§10). The row outlives its log: after that, a run still has its
verdict and no longer has its detail.

## Running it locally

`docker compose up` starts a stub issuer beside everything else and signs you in without a prompt,
as `dev-user` holding both roles.

It stubs the **issuer**, not the security: the API runs its real filter chain against real signed
tokens, so a mistake in the authorization rules shows up on a laptop rather than in production.

Running the API outside compose:

```bash
docker compose up oidc
SPRING_PROFILES_ACTIVE=dev-auth \
KITBASH_OIDC_JWKS=http://localhost:9500/kitbash/jwks \
  ./gradlew :api:bootRun
```

The service **refuses to start** without an issuer, with a sentence saying so. That is deliberate:
starting without one would mean being unable to verify a token, and therefore unable to refuse
anything.

### The one thing the dev profile relaxes

It verifies against the issuer's **key set** rather than its issuer claim. The browser reaches the
stub on the host's published port and the API reaches it by its service name, so a token minted for
the browser carries an `iss` the API would not recognise. The signature check — the part that
matters — is unchanged. Deployments set `KITBASH_OIDC_ISSUER` and get issuer validation as well.

## The download stopped being a form

§9 wanted the generated project downloaded by a real form submission, for the browser's own
progress and resume. A form navigation cannot carry an `Authorization` header, and the
alternatives are worse: a token in a query string ends up in history, referrers and access logs,
and a cookie session beside the bearer tokens is two authentication schemes to keep in agreement.

So the web app fetches with the token and hands the blob to the download manager. The cost is
small in practice — a generated project is a few hundred kilobytes, and §9's concern was
multi-megabyte downloads that appear to hang. The form endpoint stays on the server for clients
that are not browsers.

The sign-in redirect also preserves the selection in the URL. §9 makes the URL the configuration,
so a link is how somebody shares a stack; without that, a shared link opened by a colleague who
was not signed in would arrive empty.
