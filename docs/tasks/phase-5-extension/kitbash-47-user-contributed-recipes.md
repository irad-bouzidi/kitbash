# kitbash-47-user-contributed-recipes

**Phase** 5 — Extension · **Depends on** `kitbash-43-recipe-authoring-sdk` · **Plan** §1, §10, §13, §17

## Goal

Allow recipes the core team did not write — **only if justified**, and only with the sandbox its
threat model demands.

## Context

The plan is consistent and emphatic across four sections. §1 lists user-uploaded recipes as a
non-goal for v1 with their own threat model. §13 says they *change the threat model completely —
separate process, no network, read-only FS, hard memory/CPU caps, review before a recipe becomes
visible. That is its own project, not a checkbox.* §10 notes that a DB-backed catalog is only
needed for this case. §17 qualifies it with *only if justified*.

So the first deliverable of this task is not code. It is a written argument that the feature is
worth its risk, and a threat model that survives review.

## Scope

**Before any code:**

- A written threat model, reviewed and merged, covering at minimum: template-engine escape,
  resource exhaustion, path traversal into other users' output, dependency injection of malicious
  coordinates into generated projects, exfiltration via generated CI files, and social engineering
  through plausible-looking recipe names.
- An explicit statement of who may contribute and who reviews.

**Then:**

- Execution in a **separate process**: no network, read-only filesystem, hard memory and CPU caps,
  hard timeout, and no access to the host catalog or the database (§13).
- A **review workflow** — a contributed recipe is invisible until approved, and approval is a
  human action recorded against a named reviewer.
- DB-backed catalog entries **for contributed recipes only**; shipped recipes stay in git (§10).
  The digest must distinguish the two so a generation's provenance is unambiguous.
- Provenance and attribution recorded per recipe, with a **revocation path** that pulls a recipe
  and flags the generations that used it.
- Contributed recipes run through the same verification matrix before approval, not after.
- Generated projects disclose which contributed recipes they used, in the README and the lock.

## Out of scope

Anything resembling a public marketplace. Anonymous contribution.

## Implementation notes

- The sandbox needs an adversarial test suite, not a smoke test: a documented set of escape
  attempts that must all fail, kept in the repo and extended whenever a new attack is imagined.
- Resist reusing the in-process hook SPI (§4) for contributed code. Hooks are registered in `core`
  precisely so that recipe directories cannot supply code, and that boundary is the main thing
  standing between this feature and an arbitrary-code-execution endpoint.
- If the justification cannot be written convincingly, the correct outcome of this task is a
  merged document explaining why the feature was declined. That is a successful outcome, not a
  failed one.

## Files and modules touched

`/docs/threat-model-contributed-recipes.md`, a new sandbox runner module, `/server/catalog/**`
(dual-source catalog), review workflow UI, migrations for contributed recipe metadata.

## Done when

- The threat model is merged and reviewed.
- The sandbox resists every documented escape attempt in an automated test suite.
- A contributed recipe can be submitted, verified, approved, generated from, and revoked — with
  revocation flagging the generations that used it.
