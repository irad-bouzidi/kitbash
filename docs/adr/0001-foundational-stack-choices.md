# 0001 — Foundational stack choices

**Status** Accepted · **Date** 2026-09-19 · **Plan** §2, §5, §18

## Context

The plan settled a set of choices that are cheap now and expensive to reverse once six modules
and a catalog exist. They are recorded here so a contributor finds the reasoning without
reading a 40-page plan, and so a future reversal is an argument against a stated cost rather
than a rediscovery.

## Decisions

### Java 21 for the server

The maintaining team is Java-only, which outranks the marginal modelling advantage of Kotlin.
Records, sealed interfaces and pattern-matching `switch` recover most of it: `PatchOp` is a
sealed hierarchy and every applier dispatches over it exhaustively.

*Consequences.* JUnit 5 + AssertJ rather than Kotest. **No Lombok in `core`** — its generated
members fight the sealed hierarchy and the switches built on it. The Java + Spring Boot backend
recipe moves from phase 3 to phase 0, so the reference project the team reads most often is in
the language they maintain.

### Gradle 8.x with the Kotlin DSL, and a version catalog

Same tool and dialect the generated projects default to, so the team debugs one build system.
Kotlin DSL is for build scripts only — it is not an opening for Kotlin server code.

*Consequences.* Every coordinate lives in `server/gradle/libs.versions.toml`; a version literal
in a build script is a review comment. Shared build logic lives in `buildSrc` convention
plugins from the first commit, because six modules each carrying a copy-pasted `java { }` block
rot within a month.

### `core` depends on the JDK and nothing else

The generator must be drivable from tests and the CLI without booting a Spring context (§6).
That is only true if it is enforced: `checkModulePurity` inspects `core`'s runtime classpath
and fails the build on a `org.springframework`, `org.projectlombok` or `jakarta.persistence`
coordinate. It is wired into `check`, so CI enforces it on every merge request.

*Consequences.* Code in `core` that needs a framework does not get an exemption; it moves to
`catalog`, `render` or `api`.

### Pebble for templating

Jinja2-like conditionals and loops, JVM-native, and sandboxable — which matters the moment
recipes are contributed rather than written in-house (§13). Logic-less engines fight you when
the output is source code.

### In-memory workspace

A generated project is a few hundred kilobytes. Generation builds a `Map<String, byte[]>` and
streams it to the response; there is no `/tmp/{id}` directory and therefore no cleanup job, no
disk-full failure mode and no cross-request leakage.

*Consequences.* A cap on file count and total size is required, and belongs in the plan stage
(§13). Very large outputs are out of scope by construction, which is the intended trade.

### Java 21 toolchain pinned through Gradle toolchains

The build resolves its own JDK rather than trusting `JAVA_HOME`. A developer on 17 gets a clear
provisioning error instead of a confusing compilation failure.

### LF line endings enforced at the repository level

Not in the original plan, added here: the product's central guarantee is a byte-identical zip
for identical input (§4). That cannot survive a checkout whose line endings depend on the
developer's `core.autocrlf`. `.gitattributes` pins `eol=lf`, `.editorconfig` agrees, and
Spotless is configured with `LineEnding.UNIX` so the build fails rather than drifts.
