# kitbash-32-feature-observability

**Phase** 3 — Catalog breadth · **Depends on** `kitbash-31-feature-auth-jwt` · **Plan** §11, §15

## Goal

Actuator and Micrometer as a feature recipe, with OpenTelemetry as an option and the full
observability stack deliberately left out.

## Context

§11 puts Actuator + Micrometer in v1 and *optional OpenTelemetry* beside it, while the full LGTM
compose stack is deferred. The reasoning is the same as for Keycloak: every additional container
is a healthcheck, a startup race and a verification dimension. A generated project that emits
metrics is useful; a generated project that ships a monitoring platform is a different product.

§15 already requires health endpoints, structured JSON logging and correlation ids in *every*
generated project, so this recipe adds the metrics layer rather than inventing the basics.

## Scope

- **Recipe** `/recipes/feature-observability` — `requires: [http-server]`,
  `provides: [observability]`.
- Actuator endpoints exposed deliberately (health, info, metrics, prometheus) with the rest
  closed.
- Micrometer registry configured; a meaningful application metric emitted by the vertical slice,
  not just JVM defaults.
- Structured JSON logging and request correlation ids **propagated into log output**, asserted by
  a test in the generated project.
- **Optional OpenTelemetry exporter** behind an option; when off, no OTel dependency, no compose
  service, no config.
- Interaction with auth: Actuator endpoints must not become an unauthenticated data leak when
  `feature-auth-jwt` is selected — a test covers both recipes together.
- Matrix cells for observability on/off × auth on/off.

## Out of scope

The full LGTM compose stack, dashboards, alerting rules (§11).

## Implementation notes

- The "no container unless OTel is on" rule is the test worth writing first: generate with the
  option off and assert `compose.yaml` is byte-identical to the non-observability output.
- Keep the emitted metric names generic enough to be useful and specific enough to prove the
  wiring — a counter on the example entity's creation endpoint is the obvious choice.

## Files and modules touched

`/recipes/feature-observability/**`, `/reference/**`, `/verification/cells/**`.

## Done when

A generated project exposes health and metrics endpoints and emits a correlation id per request,
asserted by a test **inside the generated project**; and with auth on, Actuator is not open.
