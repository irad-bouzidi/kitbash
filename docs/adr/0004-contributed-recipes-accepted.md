# 0004 — User-contributed recipes, with the three controls the sandbox cannot provide

**Status** Accepted · **Date** 2026-09-24 · **Plan** §1, §10, §13, §17, §18 · **Task** `kitbash-47`

## Context

§17 lists user-contributed recipes in phase 5 with a qualifier the other four tasks do not carry:
*only if justified*. §13 says they *change the threat model completely — separate process, no
network, read-only FS, hard memory/CPU caps, review before a recipe becomes visible. That is its
own project, not a checkbox.* §1 lists them as a v1 non-goal. §10 notes that the only reason to put
the catalog in a database is to hold them.

So `kitbash-47` required an argument before any code, and
[`threat-model-contributed-recipes.md`](../threat-model-contributed-recipes.md) is it. Its central
finding is not that the sandbox is hard. It is that **the sandbox addresses the cheaper half of its
own threat model.**

Of the six threats §47 enumerates, §13's isolation controls three — template escape, resource
exhaustion, path traversal — rated High, Medium-High and Medium. It controls **none** of the other
three, which include both Critical ones, and it fails to for a structural reason: §13's controls
protect the *generator host*, while malicious dependency coordinates, exfiltrating CI jobs and
confusable recipe names attack the *generated project and the person who runs it*. Their payload is
the generator's legitimate output. No process boundary constrains a file that is meant to be
written, zipped and handed over.

Two controls that look like they cover that gap do not. §13's `osv-scanner` pass finds
*known-vulnerable* packages, not *malicious* ones — a typosquat published last week is in no
advisory database. And §47's *verification before approval* builds generated projects and lints
their CI with `actionlint`; it does not execute their pipelines, so an exfiltrating job is inert in
verification and fires in the user's afterwards.

§47 supplies a human reviewer, and that reviewer is necessary. But a review is only as good as what
it is asked to look at, and asking somebody to spot a typosquatted coordinate by eye is asking them
to be a scanner.

## Decision

**Build the feature, and give each of the three unsandboxable threats a structural control** — a
rule the system enforces at submission, so the reviewer is checking a judgement rather than
performing a search. All three are hard requirements of the feature, not later hardening.

**Allowlisted coordinates.** `addDependency` in a contributed recipe may only name a `group:name`
on an allowlist; anything else is refused at submission by name. Without versions, deliberately —
pinning them would make the list a second dependency-freshness problem, and the threat is a
coordinate nobody recognises rather than a stale one. This relocates the trust decision to a small
reviewable file that changes rarely, which is a better home for it than a free-text manifest field.

**No CI.** A contributed recipe may not declare the `ci` capability, and its patches may not target
a CI file. This costs contributed recipes something genuinely useful, and the trade is made here
rather than discovered later: nothing short of not emitting the job closes that threat.

**Namespaces everywhere.** A contributed recipe's id is `@namespace/name`, carried into the catalog
digest, the generation lock, the logs and the wizard. Four places because they answer four
questions — *what am I choosing*, *what did I ship*, *what happened*, *was this the same catalog* —
and a namespace absent from the fourth fails exactly when somebody is investigating.

**The sandbox, fail-closed.** Separate process, no network, read-only filesystem, hard memory and
CPU caps, hard timeout, no host catalog and no database, as §13 prescribes. If the confinement it
asks the operating system for is unavailable, a contributed recipe is **refused rather than
rendered unconfined** — a sandbox that silently degrades to a normal render is worse than none,
because the threat model says it is there. Shipped recipes keep rendering in process: they are not
the untrusted input.

## Consequences

**The catalog is no longer only in git.** §10 kept it there so recipes were *reviewable, diffable,
versioned with the code that renders them*, and `SchemaTest.theCatalogIsNotInTheDatabase` was the
tripwire. The tripwire is changed rather than deleted: it now asserts the contributed tables are
**exactly** the ones this feature introduced, so the next table describing a recipe still has to
argue for itself. The digest becomes `git:<d>+contributed:<d>`, so a generation's provenance stays
unambiguous about which half came from a commit.

**`CODEOWNERS` has no equivalent, and is not built.** Any approver may approve any recipe. The
threat model names this as the first gap to close if the catalog grows past what one reviewer can
assess, and it is the clearest way this is weaker than the merge request it sits beside.

**Contributed recipes are second-class on purpose**, and should stay that way. They cannot touch
CI, cannot name an arbitrary dependency, and cannot be mistaken for a shipped recipe in any of the
four places that matter. A future change that makes them equal is a change that removes a control.

**The four ways this erodes are written down.** [§7 of the threat
model](../threat-model-contributed-recipes.md#7-what-would-reverse-this) names them: an allowlist
that becomes a rubber stamp, a first exception to the CI rule, a namespace dropped from a log line,
and a flag that turns the sandbox's fail-closed into fail-open. The last is the one to watch —
under operational pressure it looks like a fix.

**What we give up.** A merge request supplies a diff, signed commits, blame, `CODEOWNERS`, revert
and a permanent record of who approved what and why, all free. This feature rebuilds some of that
and not the rest, for the benefit of letting a contributor skip opening one. That trade was the
owner's to make and was made knowingly; the threat model's
[§5](../threat-model-contributed-recipes.md#5-the-cost-this-accepts) is the list this
implementation should be judged against.
