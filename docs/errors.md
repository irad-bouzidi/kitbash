# Errors

Every failure names the **stage**, the **recipe** and the **file** it came from, and carries a
**hint naming the next action**. §14 sets that rule; this page is the list.

The vocabulary lives in `core`, as a sealed hierarchy of typed variants — not as strings assembled
at a controller. A client that wants to behave differently for a conflict than for a breached cap
needs a stable symbol, and a message is not one.

## The envelope

The same shape everywhere: the HTTP API returns it as an RFC 9457 problem document, the CLI prints
it as JSON to stderr, and the wizard renders it verbatim.

```json
{
  "error": "CAPABILITY_UNSATISFIED",
  "stage": "resolve",
  "recipe": "frontend-react-vite",
  "file": null,
  "message": "frontend-react-vite requires 'rest-api' and nothing in this selection provides it.",
  "hint": "Choose a value for 'backend'.",
  "selectionHash": "sha256:8f14e45fceea167a…"
}
```

`recipe`, `file` and `selectionHash` are omitted when they do not apply — a bad identifier is the
caller's, not a recipe's, and the hash is only known once the selection has parsed. `error`,
`stage`, `message` and `hint` are always there. The hint is not optional and not decorative: it is
enforced by `ErrorDetail`'s constructor, which refuses a blank one, one shorter than a sentence, and
the phrases people reach for when they have not thought about the next action ("try again later",
"check your input", "something went wrong").

A correlation id is **not** in the body. §14 fixes the envelope's fields, so the id travels in the
log line and, for an unmapped failure, as a `reference` property beside them.

## The codes

### `UNKNOWN_RECIPE` · parse · 400

**Cause** — the selection names a recipe id this catalog does not have. Almost always a typo or a
recipe that was removed.

**Fix** — the hint names the nearest ids by prefix: *"Did you mean one of: backend-spring-java?"*
With nothing close enough, it points at `GET /api/v1/metadata`, which lists every recipe this
catalog offers.

### `CAPABILITY_UNSATISFIED` · resolve · 400

**Cause** — a selected recipe `requires` a capability nothing in the selection provides. The
commonest shape is a frontend with no backend: the SPA needs `rest-api` and nothing serves one.

**Fix** — the hint names the **option** to set, not the capability, because the option is the thing
in front of the user. The wizard renders this one inline on that control.

This code also covers the empty selection — *"This selection does not name anything to generate"* —
which is the same failure with nothing selected at all. An empty zip would be worse than an error,
because it looks like the generator worked.

### `CONFLICT` · resolve · 400

**Cause** — two selected recipes declare `conflictsWith` each other, or two recipes compete for one
slot.

**Fix** — the hint names the option that decides between them. Conflicts are rendered inline on that
control rather than in a banner: a conflict belongs to the control that caused it, and a list at the
top of the page makes the user hunt for which one.

### `CYCLE` · resolve · **500**

**Cause** — recipes that require each other in a loop. This is a **catalog** defect, not a
selection: no combination of choices can resolve it.

**Fix** — the hint names the members in order (`a -> b -> a`), because "cycle detected" is not a
diagnosis. Break it by removing one `requires` in one of those manifests.

A 500 on purpose. However the request was phrased, the fault is the server's, and reporting it as
the caller's mistake sends somebody looking in the wrong place.

### `PATCH_TARGET_MISSING` · patch · 400

**Cause** — a recipe patches a file no selected recipe produces. Selecting `feature-auth-jwt`
without a backend gets this: something has to have written `application.yml` before anything can
merge into it.

**Fix** — select the recipe that produces the file. The hint names both.

### `PATCH_COLLISION` · patch · 400

**Cause** — two recipes set the same key in the same file. The generator refuses rather than picking
a winner, because a silent winner is a project that builds and behaves unlike what was asked for.

**Fix** — the hint offers the two real options: put one behind an option so both are never selected
together, or have the owning recipe expose a `# kitbash:` marker for the other to insert at.
`docs/recipe-format.md` covers markers.

### `INVALID_IDENTIFIER` · parse · 400

**Cause** — a variable that is not a legal identifier where it is about to be written: a package
name with a Java keyword in it, a project name that is not a legal directory, a `schemaVersion` this
server does not understand.

**Fix** — the envelope carries `field` and `rule` as properties, so the wizard can highlight the
control and show the rule rather than a sentence about it. The hint carries a working example.

This is the one variant whose hint comes from the call site rather than from the factory, because
the useful next action differs at each. All fourteen sites are held to the same bar by
`ErrorDetail`'s constructor.

### `PATH_ESCAPE` · plan · **500**

**Cause** — a templated file path resolved outside the project root, or to something that cannot be
written on every platform the zip lands on. §13 refuses it.

**Fix** — a recipe defect: fix the templated path in the named recipe. A 500 because a user cannot
cause this by choosing options — if they can, that is the bug.

### `LIMIT_EXCEEDED` · plan · 400

**Cause** — the selection would produce more files or bytes than §13's caps allow.

**Fix** — the hint carries the observed value **next to the cap**, so it is obvious whether this is
a selection that is slightly too big or a recipe that has gone wrong. Deselect something that
contributes files, or raise the cap deliberately in `core`.

### `RENDER_FAILED` · render · **500**

**Cause** — a template did not render: an unknown variable, an unknown filter, a syntax error. Also
what a malformed patch target becomes — a YAML file some template emitted that does not parse.

**Fix** — a recipe defect. The envelope names the template and the line; the hint names the recipe
and the manifest declaration that would fix it.

## Not generation errors

Three kinds of failure are deliberately **not** `GenerationError` variants, because forcing them
into one would put a lie in the envelope.

### `NOT_FOUND` · 404

A preset, share link or generation that is not there. These carry no §6 stage — the request never
reached one — so they get their own type, a 404, and a hint pointing at the list endpoint.

Until `kitbash-39` these answered **400 with no code and no hint**, through an exception meant for a
malformed selection. Both halves were wrong: the caller had sent a perfectly good id for a row that
does not exist.

The body does not distinguish "not yours" from "not there". Telling them apart is a way to learn
whether somebody else's id is real.

### `UNEXPECTED` · 500

The one generic message in the system, and the only one §39 permits. It means a throw site that has
no error type yet — a defect in this codebase, not a mistake by the caller — so the hint says
exactly that and asks for a `reference` to be quoted. Every hit is logged at error with its cause
under that reference.

If you are reading this because you got one: nothing about your selection needs changing.

### The push codes · 422 and 502

`POST /api/v1/push` (§46) fails for reasons that belong to GitLab rather than to generation: a
token, a group, a name, a network. None of them has a §6 stage — the pipeline had already finished
successfully by the time any of them could happen — so none is a `GenerationError`.

They keep the envelope's shape: an `error`, a message and a hint naming the next action.
[`gitlab-push.md`](gitlab-push.md) lists them with what each one means in GitLab's terms; the short
version is `TOKEN_REJECTED`, `GROUP_NOT_FOUND`, `GROUP_FORBIDDEN`, `PROJECT_EXISTS`,
`PROJECT_REFUSED` and `GITLAB_UNREACHABLE`, all 422, all leaving nothing behind.

`PUSH_PARTIAL` is the exception, and the reason this section exists. It is a **502**, because the
project was created and the code did not reach it — neither a success nor a clean failure. Its
envelope carries two extra properties, `projectUrl` and `path`, so the answer to *where did my
empty repository come from?* is in the error rather than in a support conversation. Nothing is
rolled back: deleting a project the user can already see is worse than telling them it is there.

## Where the vocabulary is enforced

| Rule | Enforced by |
| --- | --- |
| Every code has a variant | `GenerationErrorTest.everyCodeHasAVariant`, plus a sealed switch with no default — a new variant fails to **compile** |
| Every error has a message, a stage and a hint | `ErrorDetail`'s constructor |
| A hint names an action rather than restating the problem | `ErrorDetail`'s constructor, and `GenerationErrorTest.anEmptyHintIsRefused` |
| No stack traces reach a user | `GenerationErrorTest.noStackTracesInTheEnvelope` |
| Unmapped failures are logged as defects | `UnmappedFailureTest` |
| The UI renders every code verbatim | `web/src/errors/ProblemDetail.test.tsx` |

Rejections are counted as `kitbash_rejections{code,stage}`. The breakdown is the point rather than
the rate: a code that suddenly dominates is usually a catalog problem wearing a user's clothes.
