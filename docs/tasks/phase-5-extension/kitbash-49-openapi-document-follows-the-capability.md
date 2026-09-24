# kitbash-49-openapi-document-follows-the-capability

**Phase** 5 — Extension · **Depends on** `kitbash-33-openapi-typed-client`, `kitbash-45-recipe-react-native-expo` · **Plan** §4, §8, §15

## Goal

Move production of the OpenAPI document to the recipe that **provides** `openapi-spec`, so any
number of recipes can consume it — which is what `kitbash-45`'s typed-client criterion needs and
did not get.

## Context

`kitbash-45`'s "done when" reads *a generated mobile + backend project builds in CI, **the typed
client compiles against the backend's DTOs**, and the mobile cells stay inside the nightly budget.*
The first and third hold. The second does not, and the reason is recorded in
`recipes/mobile-react-native-expo/recipe.yaml` rather than left to be discovered:

> §45 asks for `requires: [openapi-spec]` when a typed client is enabled, and the catalog cannot
> honour that yet. The backend *provides* `openapi-spec` — it can describe itself — but the code
> that actually produces the document lives in `frontend-react-vite`.

So the mobile app ships a hand-written `src/api/{{ entityTable }}.ts`. It compiles, and it is not
generated from the backend's DTOs, so the property `kitbash-33` bought — change a DTO field and the
call site stops compiling — does not extend to mobile.

The recipe declined to ship a `typedClient` option rather than ship one that produces nothing, and
that was right: a selection that validates and yields no client is a promise the recipe cannot
keep. But the underlying fault is a misplaced capability, and it will bite again the moment a third
consumer wants the document.

**This gap had no task until an audit found it.** The recipe said "it is its own task"; nobody
wrote one.

## Scope

> **Corrected before starting.** The first version of this section said to move document
> production *into the backends* and gate it on the capability being **consumed**. That cannot be
> expressed: `provides` in `recipe.schema.json` is a flat `capabilityList` with no `when`, so a
> recipe provides a capability unconditionally or not at all, and there is no way for a backend to
> say "I produce a document when somebody wants one". Gating on `typedClient || mobileTypedClient`
> instead would hardcode two consumers' option ids into both backends, which is the coupling the
> capability system exists to remove.
>
> So the shape below is a **dedicated recipe** rather than a move. It reaches the same place —
> any number of consumers can generate from one document — through the mechanism the catalog
> already has.

- A new recipe, `openapi-document`: `provides: [openapi-document]`, `requires: [openapi-spec]`.
  It carries what `frontend-react-vite` carries today — `OpenApiDocumentTest` in both languages
  (gated on `capability('java-sources')` / `capability('kotlin-sources')`, as now) and the
  `dumpOpenApiDocument` build patches for Gradle and Maven.
- Consumers declare `requires: [openapi-document]`. The resolver implies the producer, which is
  exactly what `requires` is for — a selection constraint, not an ordering one — so a mobile app
  with no web frontend pulls the document in by itself.
- `frontend-react-vite` keeps its `typedClient` option and its fourteen `when: "typedClient…"`
  patch gates go with the files that move. What it stops carrying is the production.
- Give `mobile-react-native-expo` its typed-client option with `requires: [openapi-spec]`, as §45
  asked for, replacing the hand-written client.
- A matrix cell for mobile-with-typed-client, and the `kitbash-33` contract extended to it: change
  a backend DTO field, regenerate, and the **mobile** typecheck fails at the call site.

## Out of scope

Changing what the typed client generates, or the generator itself. This moves where the document
comes from; `kitbash-33`'s choices about the client stand.

## What was checked before starting

Four findings from reading the resolver and the existing patches. The first makes the plan work;
the other three are why this is not the small move it reads as.

**`demands` implies, it does not merely constrain.** The resolver's rule 3 — *a recipe's own
boolean option that declares `demands` adds that capability as a requirement when it is on, which
implied expansion then satisfies* — means `demands: openapi-document` on `typedClient` pulls the
producer in by itself, since it will be the capability's only provider. No consumer has to name it.
That is the mechanism the whole design rests on, and it already exists.

**The Maven `client` profile mixes producer and consumer in one `insertAtMarker`.** Today a single
block emits both the surefire `kitbash.openapi.write` property (producer) and the
`openapi-generator-maven-plugin` execution (consumer). Two recipes cannot both insert at
`<!-- kitbash:profiles -->` without emitting two `<profile>` elements with the same id. So the
producer must own the profile and place a marker **inside** its `<plugins>` for consumers to insert
at — which is the documented pattern (*"another recipe will target it later"*), but it means the
generated `pom.xml` grows a marker that has to be designed rather than moved.

**Gradle's `openApiGenerate` is the plugin's singleton task.** The frontend configures it directly
with `openApiGenerate { … }`. A second consumer cannot — there is one such task per project. Both
consumers have to register their own `GenerateTask`, which means the *frontend's* generated build
changes too, not just the mobile one, and `generateApiClient` has to depend on all of them.

**The document path has to move.** It is `frontend/openapi.json` today, which is the wrong home the
moment a project has a mobile client and no frontend. A neutral path — project root — is the
smallest change, and it touches `inputSpec` on both build tools and the `.gitignore` entry.

## Implementation notes

- Four combinations to keep green, not two: Java and Kotlin, each on Gradle and Maven. The build
  patches differ per build tool and the document test differs per language, which is why this
  reads as a small move and is not one.
- **The reference projects do not carry `OpenApiDocumentTest`** — checked. So this touches recipe
  content only and the `kitbash-19` equality test has nothing to say about it, which removes the
  largest risk a recipe refactor usually carries.
- `demands: openapi-spec` on `typedClient` becomes `demands: openapi-document`. Worth doing in the
  same change: leaving it pointing at the old capability would let a selection validate and then
  produce a client with nothing to generate from, which is the failure the `demands` keyword was
  added to prevent.
- Watch for the document being produced twice when both a web and a mobile client are selected.
  Two consumers of one capability is the case this exists to support, so the producing recipe has
  to emit once regardless of how many ask.
- `frontend-react-vite` keeps its `typedClient` option. What changes is that the option consumes a
  capability instead of carrying the implementation.

## Files and modules touched

`/recipes/backend-spring-java/**`, `/recipes/backend-spring-kotlin/**`,
`/recipes/frontend-react-vite/**`, `/recipes/mobile-react-native-expo/**`,
`/verification/cells/**`, `/reference/**`.

## Done when

`kitbash-45`'s second criterion is true: in a generated mobile + backend project with no web
frontend, the mobile typed client is generated from the backend's OpenAPI document, and changing a
backend DTO field makes the mobile `tsc --noEmit` fail at the call site — asserted by a matrix cell,
as `kitbash-33` asserts it for the web client.
