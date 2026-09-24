# 0004 — User-contributed recipes are declined, and the catalog stays in git

**Status** Accepted · **Date** 2026-09-24 · **Plan** §1, §10, §13, §17, §18 · **Task** `kitbash-47`

## Context

§17 lists user-contributed recipes in phase 5 with a qualifier the other four tasks do not carry:
*only if justified*. §13 says they *change the threat model completely — separate process, no
network, read-only FS, hard memory/CPU caps, review before a recipe becomes visible. That is its
own project, not a checkbox.* §1 lists them as a v1 non-goal. §10 notes that the only reason to put
the catalog in a database is to hold them.

`kitbash-47` therefore asks for an argument before any code, and
[`threat-model-contributed-recipes.md`](../threat-model-contributed-recipes.md) is it. This record
is the decision that came out.

The analysis turned on two facts.

**A recipe is data, not code.** `recipe.yaml`, a `files/` tree, `.peb` templates and a closed set
of eight patch operations. Hooks are registered in `core` and cannot be supplied by a recipe
directory, so accepting a contributed recipe is not accepting an executable.

**The contributors would be people who already have commit access.** §18 settles the audience as
one internal team behind SSO; §47 puts anonymous contribution and anything resembling a marketplace
out of scope. So the alternative to a bespoke review workflow is not *no review* — it is a merge
request.

## Decision

**Decline the feature for this audience.** No sandbox runner module, no dual-source catalog, no
review workflow, no migration describing recipes. `SchemaTest.theCatalogIsNotInTheDatabase` stays,
and its comment now points at the threat model.

Two findings drove it.

**The sandbox addresses the cheaper half of the threat model.** Of the six threats §47 enumerates,
the three that §13's isolation controls — template escape, resource exhaustion, path traversal —
are High, Medium-High and Medium. The two Critical ones are malicious dependency coordinates and
exfiltration through generated CI files, and isolation does not touch either, for a structural
reason: §13's controls protect the *generator host*, while those two attack the *generated project*
and its user. Their payload is the generator's legitimate output. A coordinate written into a build
file is the format's central feature working correctly; no process boundary constrains a file that
is meant to be written, zipped and handed over.

This also disposes of the two controls that look like they cover it. §13's `osv-scanner` pass finds
*known-vulnerable* packages, not *malicious* ones — a typosquat published last week is in no
advisory database. And §47's *verification before approval* builds generated projects; it does not
execute their CI pipelines, so a job that exfiltrates a token is inert in verification and fires in
the user's pipeline afterwards.

**So the only control that addresses the critical half is human review — which git already gives us,
better.** A merge request supplies the diff, signed commits, `git blame`, `CODEOWNERS`, revert and
a permanent record of who approved what and why. A bespoke workflow has to build every one of those
and arrives weaker, having discarded the history. Its entire benefit is that a contributor skips
opening a merge request.

## Consequences

**The catalog stays in git**, and a catalog digest keeps corresponding to a commit somebody can
check out. That property is why §10 kept it there, and it survives.

**`kitbash-47` is complete without code.** Its implementation notes say so: *if the justification
cannot be written convincingly, the correct outcome of this task is a merged document explaining
why the feature was declined. That is a successful outcome, not a failed one.*

**A contributor with a recipe to add opens a merge request.** [`kitbash-43`](../tasks/phase-5-extension/kitbash-43-recipe-authoring-sdk.md)
already shipped the part with real value — the manifest schema and a local test harness, so
authoring does not require understanding the engine. What was declined is a delivery mechanism, not
the ability to contribute.

**The decision is conditional and the conditions are written down.** It rests on contributors
having commit access. [§7 of the threat model](../threat-model-contributed-recipes.md#7-when-to-revisit)
names four triggers that break that, each stated so somebody can tell whether it has happened — the
strongest being contributors who cannot be given repository access, since that removes the
alternative entirely.

**A revisit does not start from nothing.** Sections 3.1–3.3 of the threat model stand as the design
brief for the sandbox. Sections 3.4–3.6 are flagged as the ones needing answers no sandbox will
provide, with starting points recorded: a coordinate allowlist, denying contributed recipes the
`ci` capability outright, and mandatory namespacing carried into the digest, the lock, the logs and
the wizard.

**What we give up.** If the audience grows faster than this document is reread, the decline could
outlive its reasoning. That is the cost of deciding now, and the triggers in §7 are the mitigation
— they are written as observable conditions rather than as a feeling precisely so that somebody can
notice.
