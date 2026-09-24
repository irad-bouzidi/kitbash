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

- Move `dumpOpenApiDocument`, `OpenApiDocumentTest` (Java and Kotlin) and the Gradle and Maven
  build patches out of `frontend-react-vite` and into the backends that declare
  `provides: [openapi-spec]`.
- Gate them on the capability being **consumed** rather than on `frontend-react-vite`'s
  `typedClient` option, so a project with a mobile client and no web frontend still gets a
  document.
- Give `mobile-react-native-expo` its typed-client option with `requires: [openapi-spec]`, as §45
  asked for, replacing the hand-written client.
- A matrix cell for mobile-with-typed-client, and the `kitbash-33` contract extended to it: change
  a backend DTO field, regenerate, and the **mobile** typecheck fails at the call site.

## Out of scope

Changing what the typed client generates, or the generator itself. This moves where the document
comes from; `kitbash-33`'s choices about the client stand.

## Implementation notes

- Four combinations to keep green, not two: Java and Kotlin, each on Gradle and Maven. The build
  patches differ per build tool and the document test differs per language, which is why this
  reads as a small move and is not one.
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
