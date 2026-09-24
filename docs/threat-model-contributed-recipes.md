# Threat model — recipes the core team did not write

**Status** Reviewed · **Task** [`kitbash-47`](tasks/phase-5-extension/kitbash-47-user-contributed-recipes.md) · **Plan** §1, §10, §13, §17, §18

§17 qualifies user-contributed recipes with *only if justified*. §13 says they *change the threat
model completely* and are *their own project, not a checkbox*. §1 lists them as a v1 non-goal. So
`kitbash-47` asks for a written argument before any code, and this is it.

The conclusion is at the bottom, but it is short enough to put here too: **the feature is built,
and three of the six threats get controls that are not the sandbox**, because the sandbox cannot
reach them. [ADR 0004](adr/0004-contributed-recipes-accepted.md) records the decision and what it
costs. [§6](#6-the-decision) states the controls as requirements — they are not advice, and a
review that lets one through has let the feature's critical half through with it.

---

## 1. What a recipe actually is

The size of a threat model depends on what is being accepted, and here that is unusually small.
A recipe is **data**, not code:

| It may contain | Bounded by |
| --- | --- |
| `recipe.yaml` — metadata, capabilities, options, `when` expressions | a JSON schema, validated at boot |
| a `files/` tree copied byte for byte | the §13 path rules |
| `.peb` templates | Pebble with five registered filters and no others |
| patch operations | a **closed set of eight** ([`recipe-format.md`](recipe-format.md)) |

It may **not** contain executable code. `hook: true` declares that a hook exists; the
implementation is registered in a map in `dev.kitbash.core.hook`, reviewed with the source and
shipped with the application. There is no `ServiceLoader` and no classpath scanning
([`hooks.md`](hooks.md)), and a contract test caps the registry at three entries.

That boundary is the single most valuable security property this system has, and §47's
implementation notes say so: *resist reusing the in-process hook SPI for contributed code … that
boundary is the main thing standing between this feature and an arbitrary-code-execution endpoint.*

Everything below assumes the boundary holds. A design that lets contributed content supply a hook
is not a variant of this feature; it is a different, much worse one.

## 2. Who contributes, and who reviews

§47 requires this to be stated rather than implied, and stating it is most of the analysis.

§18 settles the audience: **one internal team, behind the SSO they already have.** There is no
registration, no tenancy and no anonymous access — [`auth.md`](auth.md) describes a caller who
arrives with a token from the identity provider the company already runs. §47 puts *anonymous
contribution* and *anything resembling a public marketplace* out of scope.

So the contributor population is: **people who already have commit access to this repository.**

That sentence is the hinge. Every control below has to be compared not against *nothing*, but
against the review those same people's changes already get when they open a merge request.

## 3. The threats

§47 names six. Each is assessed as: what it is, what already stands in its way, what contributed
content changes, and — the column that decides this — **whether §13's prescribed sandbox helps.**

### 3.1 Template-engine escape

**What it is.** A `.peb` template that reaches out of the rendering context into the JVM.

**What stands in the way.** Pebble is configured with unsafe extensions removed: no method access,
no reflection, no `include` from user-controlled paths, and a variable map holding validated
primitives only — never a live service object ([`recipe-format.md`](recipe-format.md#what-a-template-cannot-do)).

**What changes.** Two things, and the second is the one usually missed.

First, the blacklist becomes load-bearing. `recipe-format.md` already says it plainly — *a blacklist
is a list of the attacks somebody already thought of* — and against a catalog written by the team
that is an acceptable remark, because nobody in the repository is attacking. Against contributed
content it is the perimeter.

Second, `include` changes meaning. Templates resolve against the catalog's own set, which today is
a set the team wrote. Add contributed recipes to that set and a contributed template can include
another recipe's template — reading content it was not shipped with, in a rendering context it
controls.

**Does the sandbox help?** **Yes.** This is the threat §13's separate process, read-only filesystem
and dropped network are designed for. It is also the threat that is most amenable to an adversarial
test suite, since an escape either produces a value it should not or it does not.

**Severity** High · **Mitigable by isolation** Yes

### 3.2 Resource exhaustion

**What it is.** A recipe that renders forever, or renders something enormous.

**What stands in the way.** §13's caps, enforced at the plan stage so failures happen before bytes
stream — `Caps` in `dev.kitbash.core.plan` holds the four numbers: 5,000 files, 50 MB uncompressed,
5 MB per file, 10 s wall clock.

**What changes.** The caps bound the *output*. They do not bound the *work*: a template that loops
or recurses through includes burns CPU inside the 10-second window, and that window is currently
spent on an API thread. Ten seconds of spin per request, multiplied by the request rate the §13
limiter allows, is a denial of service against a service the whole team shares.

**Does the sandbox help?** **Yes.** Hard memory and CPU caps in a separate process are the right
control, and they are also a genuine improvement over what exists — the caps today assume a recipe
that is merely large, not one that is hostile.

**Severity** Medium-High · **Mitigable by isolation** Yes

### 3.3 Path traversal into another user's output

**What it is.** A rendered path that escapes the project root — into the host, or into a
concurrently generating project.

**What stands in the way.** §13's zip-slip rules: every rendered path is normalised and must
resolve inside the project root, with `..`, absolute paths and Windows reserved names rejected.
Paths are themselves templated, so this is already enforced against a computed value.

**What changes.** Less than it appears. The variables interpolated into a path are user input and
were already allowlisted; what becomes attacker-controlled is the *static* part of the path, which
the existing normalisation covers. The honest statement is that the control exists and is correct,
but that it has been guarding against a typo and would now be guarding against an adversary — which
is a reason for adversarial tests, not for a new mechanism.

**Does the sandbox help?** **Yes**, as defence in depth: a read-only filesystem makes a successful
traversal land nowhere.

**Severity** Medium · **Mitigable by isolation** Yes

### 3.4 Malicious dependency coordinates

**What it is.** `addDependency` with `coordinate: com.evil:backdoor:1.0`, or — far more likely to
survive review — `com.fasterxml.jackson:jackson-databind` beside the real
`com.fasterxml.jackson.core:jackson-databind`.

**What stands in the way.** §13 asks for pinned versions and a vulnerability scan of generated
dependency trees, and [`dependency-freshness.md`](dependency-freshness.md) implements it: every run
generates a full-stack project and scans it with `osv-scanner`.

That control is real and it does not help here. **A vulnerability scanner finds *known-vulnerable*
packages, not *malicious* ones.** A typosquat published last week is in no advisory database. The
scan is a control against distributing yesterday's CVEs, which is exactly what §13 asks it to be,
and it was never a control against deliberate poisoning.

**What changes.** Everything. Adding dependencies is not an abuse of the recipe format — it is the
format's central feature, the one `addDependency` exists to serve. The payload is indistinguishable
from the product.

**Does the sandbox help?** **No.** Not partially, not as defence in depth: **not at all.** The
coordinate is a string in a manifest. It is written into a build file, the build file leaves in a
zip, and the zip is built on a developer's laptop and shipped to production. Every control §13
prescribes — separate process, no network, read-only filesystem, memory and CPU caps — governs
what happens *inside the generator*, and nothing malicious happens inside the generator. The
generator does exactly what it was asked to do, correctly, deterministically, and within every cap.

The only control that addresses this is a human reading the coordinate and knowing which one is
real.

**Severity** Critical · **Mitigable by isolation** **No**

### 3.5 Exfiltration through generated CI files

**What it is.** The `ci` capability lets a recipe append a job to the generated pipeline. A job
runs in the *user's* CI, with the user's secrets in scope. `mergeYaml` targets CI files directly,
and `addComposeService` adds a service to a `compose.yaml` somebody will `up`.

**What stands in the way.** Stage ordering is not extensible — [`recipe-format.md`](recipe-format.md#conventions-a-capability-carries)
notes that *a recipe that could add a stage could reorder the pipeline* — so a contributed job
appends at `# kitbash:jobs` rather than inserting anywhere. That limits *where*, not *what*.

**What changes.** §47's scope says contributed recipes *run through the same verification matrix
before approval, not after*, and it is worth being precise about what that buys, because it reads
like more than it is.

The matrix does look at generated CI files: the `images/ci` cell is, in
[`verification/README.md`](../verification/README.md)'s words, *the only image that inspects rather
than builds*, and it runs `actionlint`, `check-jsonschema` and `osv-scanner`. Those answer *is this
a valid workflow?* and *does it use a package with a published advisory?* None of them answers *does
this job send a secret somewhere.* `actionlint` is a linter, not a taint analysis, and a `curl` to
an attacker's host is valid YAML running a valid step.

Nothing in the matrix **executes** a generated pipeline, which is correct — executing untrusted CI
is the thing verification is sandboxed to avoid — but it means a job that exfiltrates
`$CI_JOB_TOKEN` is inert during verification and fires in the user's pipeline afterwards.
Verification-before-approval is a good rule for catching recipes that do not work. It is not a
control against recipes that work exactly as intended.

**Does the sandbox help?** **No**, for the same structural reason as §3.4: the hostile artifact is
the *output*, and the output is supposed to leave.

**Severity** Critical · **Mitigable by isolation** **No**

### 3.6 Plausible names

**What it is.** `backend-spring-java-v2`. `frontend-react-vite-fast`. `db-postgres-flyway-ha`. The
wizard renders the catalog and a contributed recipe appears in the same list, in the same control,
with the same styling as one the team wrote.

**What stands in the way.** Nothing today, because today every recipe id was chosen by the team.

**What changes.** The catalog digest is `sha256` over `(recipeId, version, contentHash)` for every
recipe, and §10's privacy rule means logs and metrics carry **recipe ids and hashes only**. Both
properties are load-bearing elsewhere and both assume recipe ids are trustworthy names. A
confusable id is therefore not only a confusable *choice* in the wizard — it is a confusable line
in the audit trail that would be used to investigate it.

**Does the sandbox help?** **No.** Namespacing contributed ids and separating them visibly in the
wizard would help, and neither is isolation.

**Severity** High · **Mitigable by isolation** **No**

## 4. The finding

| Threat | Severity | Addressed by the sandbox §13 prescribes |
| --- | --- | --- |
| Template-engine escape | High | Yes |
| Resource exhaustion | Medium-High | Yes |
| Path traversal | Medium | Yes |
| **Malicious dependency coordinates** | **Critical** | **No** |
| **Exfiltration through generated CI** | **Critical** | **No** |
| **Plausible names** | **High** | **No** |

The two critical threats are the two the sandbox cannot touch, and they fail to be touched for the
same structural reason. §13's controls protect **the generator host**. Threats 3.4, 3.5 and 3.6
attack **the generated project and the person who runs it**, and their payload is the generator's
legitimate output. No amount of process isolation constrains a file that is supposed to be written,
zipped and handed over.

So the expensive, difficult, interesting part of this feature — the sandbox, the adversarial test
suite, the separate-process runner — addresses the *cheaper half* of its own threat model.

§47 already supplies one control for the other half: *a contributed recipe is invisible until
approved, and approval is a human action recorded against a named reviewer.* That human is
necessary and is built. But a review is only as good as what it is asked to look at, and asking
somebody to eyeball a free-text Maven coordinate for a typosquat, or a CI job for exfiltration, is
asking them to be a scanner. Reviews that depend on nobody ever being tired are not controls.

So the design owes each of 3.4, 3.6 and especially 3.5 something **structural** — a rule the
system enforces, so the reviewer is checking a judgement rather than performing a search.
[§6](#6-the-decision) is those three rules. They are the part of this feature that the sandbox, had
it been built alone, would have quietly left out.

## 5. The cost this accepts

The contributors are, by [§2](#2-who-contributes-and-who-reviews), people with commit access to
this repository. So the alternative to building a review workflow is not *no review*. It is `git`,
and git is better at most of it:

| | Contributed-recipe review | A merge request |
| --- | --- | --- |
| What the reviewer reads | the same YAML and templates | the same YAML and templates |
| Diff against the previous version | built here | `git diff` |
| Attribution | a `reviewed_by` column | signed commits, `git blame` |
| Required reviewers by area | not built | `CODEOWNERS` |
| Revocation | built here, plus flagging affected generations | `git revert`, plus the same flagging |
| Verification before it is visible | built here | the matrix already gates every MR |
| History of who approved what, and why | a row | the MR thread, permanently |

This table is not an argument against the decision — the decision is made, and it is the owner's to
make. It is the **list of things this implementation has to be judged against**, and two rows are
worth carrying into review:

- **`CODEOWNERS` has no equivalent here**, and is not built. Any approver may approve any recipe. If
  the catalog grows past what one reviewer can assess, that is the first gap to close, and
  [§7](#7-what-would-reverse-this) says so.
- **The catalog digest stops corresponding to a git commit.** §10 kept recipes in git so they were
  *reviewable, diffable, versioned with the code that renders them*, and
  `SchemaTest.theCatalogIsNotInTheDatabase` was the tripwire guarding it. The tripwire has been
  changed rather than deleted: it now asserts that the contributed tables are **exactly** the ones
  this feature introduced, so the next table describing a recipe still has to argue for itself. The
  digest becomes `git:<d>+contributed:<d>`, so a generation's provenance stays unambiguous — which
  half came from a commit, and which half did not.

## 6. The decision

**Build it, with three controls the sandbox does not provide.**

[§4](#4-the-finding) is the reason this section is not simply "build it". The sandbox §13 prescribes
handles 3.1–3.3 and would handle them well. It handles **none** of 3.4–3.6, and those include both
Critical threats. A build that ships the sandbox and calls the threat model satisfied would have
shipped the easy half.

So each of the three carries a control that is not isolation, and each is a hard requirement of
this feature rather than a hardening task for later.

### 6.1 Contributed recipes may only name allowlisted dependency coordinates

Answers [3.4](#34-malicious-dependency-coordinates). `addDependency` in a contributed recipe is
checked against an allowlist of `group:name` pairs; anything else is refused at submission, by
name, with the §14 envelope.

The allowlist is **`group:name` without a version**, deliberately. Pinning versions would make the
list a second dependency-freshness problem and it would rot; the threat is a coordinate nobody
recognises, not a stale one, and a version that moves is already §12's job.

This does not eliminate the trust decision — it relocates it to a small, reviewable file that
changes rarely, which is a much better place for it than a free-text field in a manifest. A
contributed recipe needing a coordinate not on the list is a merge request against the list, which
is exactly the review [§5](#5-the-cost-this-accepts) says git does better.

### 6.2 Contributed recipes may not touch CI

Answers [3.5](#35-exfiltration-through-generated-ci-files). A contributed recipe may not declare the
`ci` capability, and its patches may not target a CI file. Refused at submission.

This costs contributed recipes a genuinely useful feature, and the trade is made deliberately here
rather than discovered later: a generated CI job runs in the user's pipeline with the user's
secrets, verification lints it rather than executing it, and no control short of not emitting it
closes that. A contributed recipe that needs a CI job is a merge request against the shipped
catalog.

### 6.3 Contributed recipe ids are namespaced, everywhere

Answers [3.6](#36-plausible-names). A contributed recipe's id is `@namespace/name`, and the `@`
prefix is carried into the catalog digest, the generation lock, the logs and the wizard — all four
of the places §10 and §14 make somebody look.

Four rather than one because they are four different questions. The wizard answers *what am I
choosing*; the lock answers *what did I ship*; the logs answer *what happened*; the digest answers
*was this the same catalog*. A namespace visible in three of them and absent from the fourth is a
namespace that fails exactly when somebody is investigating.

### 6.4 And the sandbox, for the half it does cover

Separate process, no network, read-only filesystem, hard memory and CPU caps, hard timeout, no
access to the host catalog or the database — as §13 prescribes, for 3.1–3.3.

**What the host has to permit.** The namespace comes from
`unshare --map-root-user --net`, which needs unprivileged user namespaces. Ubuntu 24.04 blocks
them by default through AppArmor, so a host there answers
`unshare: write failed /proc/self/uid_map: Operation not permitted` and this feature is off until
somebody sets `kernel.apparmor_restrict_unprivileged_userns=0`. That is a real operational cost and
it belongs in the decision rather than in a runbook: the feature does not work everywhere, and on a
host where it does not, it says so at boot and refuses.

One addition the analysis produced: **the sandbox fails closed.** If the confinement it asks the
operating system for is unavailable, a contributed recipe is refused rather than rendered
unconfined. A sandbox that silently degrades to "a normal render" on a host that does not support
namespaces is worse than no sandbox, because the threat model says it is there.

Shipped recipes are unaffected and keep rendering in process. They are not the untrusted input, and
routing them through a subprocess would buy nothing and cost every generation.

## 7. What would reverse this

The decision rests on the three controls in [§6](#6-the-decision) holding. Each has a failure mode
worth watching for, and none of them is subtle:

1. **The allowlist becomes a rubber stamp.** If coordinates are added to it on request without
   anybody checking what they are, 6.1 has become a formality and 3.4 is back, with the extra harm
   of a control everyone believes in. The signal is an allowlist that grows on the same merge
   request as the recipe that needs it.
2. **The CI restriction acquires an exception.** The first *"this recipe really does need a job"*
   is the one to refuse, because the second is much harder to.
3. **A namespace is dropped somewhere.** Most likely in a log line or a metric label, where §10's
   privacy rule already restricts what may be written and the temptation is to shorten.
4. **The sandbox's fail-closed becomes fail-open.** Under operational pressure — a host where
   namespaces are unavailable and generations are failing — the tempting fix is a flag. That flag
   is the feature's threat model being switched off from a config file.

If the contributor population ever stops being people with commit access — a partner team, a
contractor, a second company — then [§5](#5-the-cost-this-accepts)'s comparison no longer has a
right-hand column, and this analysis needs redoing rather than extending. That is a bigger change
than it sounds: every control here assumes a reviewer who can be held responsible.

## 8. What this task changed

- this document, and [ADR 0004](adr/0004-contributed-recipes-accepted.md)
- `RecipeId` — `@namespace/name`, with provenance carried in the identity rather than beside it
  (§6.3)
- `server/sandbox` — the confined renderer and `SandboxEscapeTest`, the adversarial suite that is
  the sandbox's own "done when" (§6.4)
- `ContributedRecipeRules` — the coordinate allowlist and the CI restriction, enforced at
  submission (§6.1, §6.2)
- `V3__contributed_recipes.sql`, `ContributedRecipeRepository`, `ContributedRecipeService` and
  `/api/v1/contributed-recipes` — submit, review, approve, revoke, and the flagging of every
  generation that used a withdrawn recipe
- `DualSourceCatalog` — the composition and the `git:<d>+contributed:<d>` digest
- `SchemaTest.theCatalogIsNotInTheDatabase`, which now names the two new tables exactly, so a third
  still has to argue for itself

### How a contributed recipe reaches a generated project

`ContributedRenderStage` routes stage 4 by provenance: a shipped recipe's templates render in
process, a contributed recipe's render in the sandbox, one subprocess per recipe. What comes back
is a finished string, which `Renderer` then treats exactly like its own output — the `.peb` strip,
the rendered-path check, the binary guard and the plan-order collection are one code path for
both, because two would drift on precisely the checks that matter most for untrusted input.

The split buys more than "the sandbox ran it". `Renderer` builds one template registry per pass and
that registry is what an `include` can reach; entries rendered elsewhere are **left out of it**, so
a contributed template is not merely routed away from the in-process engine but is not loadable by
it. The converse is stronger than [§3.1](#31-template-engine-escape) asked for: each contributed
recipe is sent to the sandbox with only its own templates, so it cannot include another contributed
recipe's either.

**Contributed recipes may not carry patches.** A `mergeYaml` body or an `appendLines` entry is a
template too, and rendering one in process would leave a hole exactly where somebody would look for
it. A second sandbox protocol for patch content is not worth building until a contributed recipe
needs one — but "not built yet" fails loudly here rather than falling through to the engine this
design exists to keep them away from.

**Disclosure is written by the generator, not by the recipe.** A project built with contributed
recipes says so above its README's `kitbash:stack` marker, listing them by namespaced id. Since a
contributed recipe cannot carry patches it could not add its own line, and it should not be able
to: disclosure an author can word, shorten or forget is not disclosure. The lock needs no
equivalent — [§6.3](#63-contributed-recipe-ids-are-namespaced-everywhere) put the namespace inside
the id, so every contributed recipe is already visible there.

**Approval takes effect without a restart.** `LiveCatalog` recomposes the catalog from the approved
rows and is refreshed at exactly two moments — after approval and after revocation — because those
are the only two at which visibility changes. `GenerationPipeline` reads it through a supplier and
holds the result for the whole of a generation, so a swap between two requests is invisible and a
swap during one cannot happen.
