# Dependency freshness

A generator's output is only as current as its recipes. §12 puts it plainly:

> Scaffolding rots by default; this is the only thing that stops it, and it is what makes a
> six-month-old preset still produce a modern project.

So there is a job. It runs every Monday, bumps the versions the recipes pin, builds the whole
catalog with them, and opens a pull request **only if all ninety-eight combinations still build**.

`.github/workflows/dependency-freshness.yml`. It can also be run by hand from the Actions tab,
which is how it was first proved.

## What it bumps, and how it knows

Every version is **declared**, in the manifest of the recipe that writes it:

```yaml
tracks:
  - version: "3.5.5"
    artifact: org.springframework.boot:spring-boot-starter-parent
    label: Spring Boot
```

Declared rather than discovered, because a job that guesses which literals in a repository are
versions eventually rewrites a port number — in a pull request that looks exactly like the twenty
good ones before it.

`version` is the exact string the recipe writes, and the bump replaces **every occurrence of it
inside that recipe's directory** at once. That is what makes a Spring Boot bump land as one
coherent change: `frameworkVersion`, the Gradle version catalog entry and the Maven parent all move
together, instead of three edits somebody has to remember to keep in step. It is bounded three
ways — only inside the declaring recipe, only for a declared literal, and only where the literal is
a whole version rather than part of a longer number, so bumping `1.4.1` cannot corrupt `11.4.10`.

A recipe whose `tracks` entry names a literal it never writes fails a test, because a declaration
nothing matches is a bump that would silently do nothing.

## What it does not bump

**Major versions.** The job proposes the newest release *within the current major* and reports the
rest:

> **A major version is available, and this job does not take them**
> - Spring Boot in `backend-spring-java`: `3.5.16` → `4.1.1`

A major upgrade moves packages and changes defaults. A job that proposed one every Monday would be
red every Monday, which is the same as being switched off — and the whole value here is a job people
leave on. Majors are a decision; the job offers them and takes none.

**Pre-releases.** `-RC1`, `-M2`, `-alpha`, `-SNAPSHOT` are somebody still deciding. `.Final` is the
exception the JVM ecosystem insists on, and it means the opposite.

**Anything a `holdBelow` names.** A version with a known incompatibility gets a ceiling and a
reason, in the manifest:

```yaml
  - version: "1.5.0"
    artifact: com.pinterest.ktlint:ktlint-cli
    holdBelow: "1.6.0"
    because: >-
      ktlint 1.6 and later need a newer Kotlin compiler embeddable than the Spotless we use
      ships, and every Kotlin file fails with NoClassDefFoundError on ZipUtilKt.
```

The job then reports it under "held back by a declared ceiling" rather than proposing it again
every Monday — and the reason is written down, so the next person can tell whether it still
applies. A ceiling is not a freeze: a project held below `1.6.0` still gets `1.5.9`.

Kotlin carries one today, held below `2.4.0`: 2.4's compiler-embeddable drops a class Spotless's
ktlint step needs, and Spotless and ktlint are both already at their latest — so there is nothing
to bump *to*, which is what makes it a ceiling rather than a pair that should move together.

That one was found the hard way. The first ceiling this repository had was on ktlint, held below 1.6 on the theory that it needed a
newer Spotless than we had. That was wrong twice over. Spotless 7.2 *requires* ktlint 1.6, so the
ceiling pinned one half of a pair while the job moved the other half — and the real culprit was
neither of them: the Kotlin plugin had moved to 2.4, and its compiler-embeddable no longer carries
the class ktlint reaches for.

Three things follow, and they are why this section exists at all.

A ceiling is for a dependency incompatible with something that is **not** moving. Two dependencies
that must move together need none, because the job bumps everything onto one branch.

A `because` is a claim about the world. The first one was wrong, and it sat in the repository for
two days telling the next reader something untrue.

**Bisect in a verification container, not on your machine.** The host said Spotless 7.2.1 with
ktlint 1.8.0 was fine; the container said it was not, and the container was right — a warm Gradle
cache had an older Kotlin compiler in it. The whole point of §12's containers is that they look
like a user's machine and yours does not.

**npm dependencies**, for now. The frontend's `package.json` and its lockfile are not in `tracks`,
so `pnpm` versions move when a person moves them. Adding them means driving `pnpm update` and
committing a regenerated lockfile, which is a different shape of change from a literal replacement
and deserves its own pass.

## When it fails

A bump that breaks the catalog opens an **issue**, not a red pull request, naming the branch and
the failing cells. The alternative — skipping the offending bump silently — is how a catalog ends
up permanently pinned to something nobody chose.

Bumps are independent: one artifact that has moved host, or one library whose lookup fails, is
reported and skipped while every other recipe is still updated.

**A red bump is usually not the bump's fault.** So far every one has been a fragility the pinned
versions were hiding. The clearest was springdoc 2.8.9 → 2.9.1: 2.9 started honouring Kotlin
nullability in the OpenAPI document it serves, so a Kotlin `val id: Long?` on a response became
`"type": ["integer", "null"]`, openapi-generator turned that into `id: number | null`, and
twenty-four Kotlin cells stopped typechecking at the one call site that passes an id back. Nothing
about springdoc was wrong. The response type had been lying about itself since §29, and the
document had been covering for it.

Read a red matrix that way first. The question to ask of a failing cell is not "which version do we
hold back" but "what was already true here that nothing was checking" — a ceiling added in answer
to the first question pins the catalog to a defect.

## The vulnerability scan

§13 attaches a second duty to the same job:

> so this does not become an efficient distributor of known-vulnerable dependencies.

Every run generates a full-stack project and scans its dependency trees with `osv-scanner` — the
Maven side from `pom.xml`, the browser side from `pnpm-lock.yaml` — and attaches the findings to
whatever it opens. Findings do not fail the job: a known issue in a transitive test dependency is
something a reviewer weighs, and failing on it would block the very bump that fixes it.

## What it needs from the repository

**Settings → Actions → General → "Allow GitHub Actions to create and approve pull requests."**
Without it the job does everything — bumps, builds ninety-eight cells, scans — and then dies on
`gh pr create` with `GitHub Actions is not permitted to create or approve pull requests`. It is off
by default on a new repository, and because the first five runs never got past a red matrix, this
was the last thing the job discovered about itself.

The setting also permits Actions to *approve* pull requests, which is the half of it worth
thinking about: a workflow that can approve can satisfy a review requirement without a human. This
repository's job only creates, and never merges.

If the setting is off, the run opens an issue saying so and naming the branch, rather than leaving
a green bump on a branch nobody was told about.

## What it never does

**Merge.** A human reviews and merges. The job's job is to make that review take a minute, which is
why the diff is version literals and nothing else.
