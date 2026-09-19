# kitbash-45-recipe-react-native-expo

**Phase** 5 — Extension · **Depends on** `kitbash-33-openapi-typed-client` · **Plan** §11, §17

## Goal

Open the mobile slot, deferred out of v1, using the capability model already in place.

## Context

§11 lists mobile as *none* for v1 with React Native + Expo first in the deferred column, and §17
puts it in phase 5. The reason to wait was never that mobile is hard to template — it is that
mobile adds an ecosystem to the verification matrix, and the matrix is what makes any of the
catalog trustworthy.

By this point the typed client (`kitbash-33`) exists, so a mobile app can consume the backend's
DTOs the same way the web frontend does, which is the only version of this feature worth shipping.

## Scope

- **Reference project** `/reference/react-native-expo/` — a working list-and-create screen against
  the backend's example entity, with a component test.
- **Recipe** `/recipes/mobile-react-native-expo` — `kind: mobile`, `requires: [rest-api]`, and
  `requires: [openapi-spec]` when the typed client is enabled.
- Coexistence rules: mobile alongside a web frontend, mobile alone, and mobile with a backend —
  each resolves sensibly, and the resolver reports clearly if a combination is unsupported.
- Patches for compose (where applicable), `.env.example`, README fragment and `.gitignore`.
- **A mobile ecosystem verification image** and matrix cells — **sampled, not enumerated**,
  following the frontend-only precedent in §18, because mobile builds are slow and the axis is
  largely independent.
- Documentation of what "builds" means for this ecosystem, since a mobile cell cannot run the app
  the way a server cell can.

## Out of scope

Compose Multiplatform stays deferred (§11). No app-store packaging, signing or distribution.

## Implementation notes

- Be explicit and honest about the verification depth: a bundle build and a component test is a
  reasonable cell; claiming a green badge means "this app runs on a device" would be a lie the
  matrix cannot back.
- Expo's toolchain versions move fast; make sure the weekly bump job (`kitbash-36`) covers this
  recipe's manifest or explicitly excludes it with a reason.

## Files and modules touched

`/reference/react-native-expo/**`, `/recipes/mobile-react-native-expo/**`,
`/verification/images/mobile/**`, `/verification/cells/**`.

## Done when

A generated mobile + backend project builds in CI, the typed client compiles against the backend's
DTOs, and the mobile cells stay inside the nightly budget.
