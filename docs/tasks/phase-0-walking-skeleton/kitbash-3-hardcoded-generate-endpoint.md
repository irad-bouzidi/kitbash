# kitbash-3-hardcoded-generate-endpoint

**Phase** 0 — Walking skeleton · **Depends on** `kitbash-2-reference-spring-java-layered` · **Plan** §2, §5, §6, §7

## Goal

`POST /api/v1/generate` streams a zip of the reference project with the caller's names
substituted. No recipes, no resolver. A deliberately throwaway path that contains one
permanent component: the deterministic zip writer.

## Context

The value of this task is not the endpoint — it is discovering the awkward parts of streaming
a generated tree (file modes, timestamps, git skeleton, content type, download headers) while
the rest of the system is still trivial. Phase 1 deletes the hardcoded path and keeps the
plumbing.

The request body already uses the §7 selection envelope, even though almost none of it is
honoured yet, so no client has to change shape later.

## Scope

- `api` module: Spring Boot 3.x, **Spring MVC on virtual threads** (not WebFlux — generation
  is CPU work, and MVC on virtual threads streams zips fine, §5).
- One controller, one endpoint: `POST /api/v1/generate`, producing `application/zip` with a
  `Content-Disposition` filename derived from the project name.
- Request body is the §7 envelope — `schemaVersion`, `projectName`, `options`, `variables` —
  of which this task honours `projectName`, `groupId`, `packageName` and `javaVersion`. Unknown
  option keys are accepted and ignored, not rejected, for this phase only.
- Substitution is a straight text replacement of the reference project's known placeholder
  names. Pebble arrives in `kitbash-9`.
- **In-memory workspace only** — `Map<String, byte[]>`. No `/tmp/{id}` directory, no cleanup
  job (§2). Projects are a few hundred KB.
- `core`: a `ZipWriter` that produces **byte-identical output for identical input**:
  - fixed zip entry timestamps,
  - entries sorted by path,
  - normalized line endings,
  - Unix file modes preserved, `gradlew` at 0755.
- Git skeleton in the output: an initialized `.git` with exactly one initial commit, itself
  reproducible (fixed author, fixed commit timestamp).
- Inline identifier checks good enough to not write nonsense to disk; the full allowlist and
  traversal defence land in `kitbash-20`.

## Out of scope

Resolver, recipes, patches, persistence, auth, caching, preview. No `202` anywhere — generation
is synchronous and streamed, and stays that way (§8).

## Implementation notes

- Write the zip straight to the response `OutputStream`; do not buffer the whole archive, even
  though it would fit. The streaming shape is what later phases need.
- Determinism is easy to get right now and miserable to retrofit — the cache key and the
  reproducibility guarantee both depend on it (§4). Make the byte-equality test part of this
  MR, not a follow-up.
- Keep the substitution logic in one clearly-named class with a comment saying it is
  phase-0 scaffolding, so `kitbash-13` knows exactly what to delete.

## Files and modules touched

`/server/api/**`, `/server/core/**` (zip writer, in-memory workspace), integration tests.

## Done when

- `curl -XPOST /api/v1/generate -d @selection.json -o out.zip` yields a zip that unzips and
  whose `./gradlew build` passes.
- Generating twice with identical input produces byte-identical zips, asserted by a test that
  also runs across two separate JVM invocations.
- `git log` inside the generated project shows exactly one commit.
