# Threat model — recipes the core team did not write

**Status** Reviewed · **Task** [`kitbash-47`](tasks/phase-5-extension/kitbash-47-user-contributed-recipes.md) · **Plan** §1, §10, §13, §17, §18

§17 qualifies user-contributed recipes with *only if justified*. §13 says they *change the threat
model completely* and are *their own project, not a checkbox*. §1 lists them as a v1 non-goal. So
`kitbash-47` asks for a written argument before any code, and this is it.

The conclusion is at the bottom, but it is short enough to put here too: **the feature is declined
for the audience the plan describes**, and [ADR 0004](adr/0004-contributed-recipes-declined.md)
records that. The reasoning is worth more than the verdict, because the verdict changes when the
audience does — [When to revisit](#7-when-to-revisit) names the conditions with numbers attached.

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
suite, the separate-process runner — addresses the *cheaper half* of its own threat model. The
critical half has exactly one control: **a human being reads the recipe before it becomes
visible.**

That is not a discovery that kills the feature. §47 already requires that human: *a contributed
recipe is invisible until approved, and approval is a human action recorded against a named
reviewer.* What it does is relocate the question. The feature is not really "can we sandbox
untrusted rendering?" — we can. It is:

> **Is a bespoke recipe-review workflow better than the code review these contributors can already
> get?**

## 5. The comparison that decides it

The contributors are, by §2 above, people with commit access to this repository. So the alternative
to building a review workflow is not *no review*. It is `git`.

| | Contributed-recipe review | A merge request |
| --- | --- | --- |
| What the reviewer reads | the same YAML and templates | the same YAML and templates |
| Diff against the previous version | must be built | `git diff` |
| Attribution | a `reviewed_by` column | signed commits, `git blame` |
| Required reviewers by area | must be built | `CODEOWNERS` |
| Revocation | must be built, plus flagging affected generations | `git revert`, plus the same flagging |
| Verification before it is visible | must be built | the matrix already gates every MR |
| History of who approved what, and why | must be built | the MR thread, permanently |
| Cost to build | a sandbox runner module, a dual-source catalog, migrations, a review UI, an adversarial test suite | **zero** |

Every row that matters is already better on the right, and the left-hand column has to be built,
tested, documented and operated. The bespoke workflow is not merely more expensive — for this
contributor population it is **strictly weaker**, because it discards attribution and history that
git supplies for free.

Against that, the feature's entire benefit is: *a contributor skips opening a merge request.*

There is a second cost worth naming. §10 keeps the catalog out of the database deliberately —
recipes are *reviewable, diffable, versioned with the code that renders them* — and
`SchemaTest.theCatalogIsNotInTheDatabase` asserts that no table ever describes a recipe, a
technology, an architecture or a catalog. That test is a tripwire, placed so this conversation
would have to happen before a migration quietly made the catalog mutable state. Building this
feature means deleting it, and with it the property that a catalog digest corresponds to a git
commit somebody can check out.

## 6. The decision

**Declined**, for the audience §18 describes.

§47 anticipates this outcome and calls it a success: *if the justification cannot be written
convincingly, the correct outcome of this task is a merged document explaining why the feature was
declined.* The justification cannot be written convincingly, and the reason is not that the
engineering is hard. It is that the feature's only irreplaceable control — human review — is a
control we already have in a better form, and its sandbox, however well built, would leave both
critical threats exactly where it found them.

The recipe authoring SDK from [`kitbash-43`](tasks/phase-5-extension/kitbash-43-recipe-authoring-sdk.md)
already delivers the part that had real value: writing a recipe is a supported activity with a
local harness, so a contributor does not need to understand the engine to produce one. What
`kitbash-47` would add on top is a delivery mechanism, and `git` is already a good one.

### What this decision is *not*

It is not a claim that the sandbox would fail. Sections 3.1–3.3 say the opposite: for the threats
isolation addresses, the §13 design is correct and would work.

It is not a claim that contributed recipes are a bad idea in general. For a different audience the
arithmetic inverts, which is what the next section is for.

## 7. When to revisit

The decision rests on one fact — **contributors already have commit access** — and that fact has an
owner who would know before this document did. Any of the following breaks it, and each is written
so somebody can tell whether it has happened:

1. **Contributors appear who cannot be given repository access.** A partner team, a contractor, a
   second company. This is the strongest trigger: it removes the alternative in §5 entirely, and
   the comparison has to be redone against *no review* rather than against a merge request.
2. **The reviewing team becomes the bottleneck.** Concretely: recipe merge requests routinely wait
   more than a week, *and* the queue is not explained by something easier to fix. Note that a
   bespoke workflow does not fix this by itself — the same people still review — so the trigger is
   only real if it comes with more reviewers who are not committers.
3. **§18's audience changes.** Multi-tenancy, or an externally hosted instance. §18 excludes both
   today, and both would make "one internal team" false, along with most of §2.
4. **The catalog outgrows one team's knowledge.** If recipes are wanted for stacks nobody on the
   reviewing team can assess, the review in §5's right-hand column stops being better than the left
   — it stops being meaningful at all, and the answer is probably domain reviewers via `CODEOWNERS`
   before it is a new subsystem.

If a revisit happens, sections 3.1–3.3 stand as the design brief for the sandbox, and **3.4–3.6 are
the sections that need new answers**, because they are the ones no sandbox will provide. Starting
points, recorded so the next attempt does not begin from nothing:

- **3.4** — an allowlist of dependency coordinates, or a required registry provenance attestation.
  Note that both push the trust decision onto a reviewer again, just earlier and in a narrower
  place, which is a real improvement over a free-text coordinate.
- **3.5** — deny contributed recipes the `ci` capability and the CI-file patch targets outright.
  This is a capability restriction rather than an isolation one, and it costs contributed recipes a
  genuinely useful feature; that trade should be made deliberately rather than discovered.
- **3.6** — mandatory namespacing (`@team/recipe-id`) carried into the digest, the lock, the logs
  and the wizard, so a contributed recipe cannot be mistaken for a shipped one in any of the four
  places §10 and §14 make somebody look.

## 8. What this task changed

Documentation, and one test comment. No production code, no migration, no new module — which is
the point.

- this document
- [ADR 0004](adr/0004-contributed-recipes-declined.md), recording the decision where decisions live
- `SchemaTest.theCatalogIsNotInTheDatabase`, whose comment now points here: the tripwire fired as
  designed, the conversation happened, and the answer was no
