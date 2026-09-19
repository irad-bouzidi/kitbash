# demo

A Spring Boot service: Java 21, Gradle Kotlin DSL, Postgres with Flyway, layered architecture.

## Start it

```bash
docker compose up --build
```

That brings up Postgres, waits for it to be healthy, and starts the service on
**http://localhost:8080**. The API is at `/api/widgets`, documented at
[`/swagger-ui.html`](http://localhost:8080/swagger-ui.html), health at `/actuator/health`.

## Work on it

```bash
cp .env.example .env          # then export it, or let your IDE load it
docker compose up -d db       # just the database
./gradlew bootRun
```

```bash
./gradlew build               # compile, format check, unit and integration tests
```

The integration tests start their own Postgres through Testcontainers, so they need a working
Docker daemon and nothing else. There is no in-memory database anywhere in the suite: the
schema, the unique index and the JPA mappings are exactly the things worth testing against the
real engine.

### If the integration tests cannot find Docker

On a very new Docker daemon (29+), Testcontainers' client library defaults to an API version
the daemon no longer serves, and the suite fails with *"Could not find a valid Docker
environment"*. Pin the version for your machine, once:

```bash
echo "api.version=1.44" >> ~/.docker-java.properties
```

This is not pinned in the build on purpose: hardcoding a version breaks the opposite case, a
developer on an older daemon that does not serve it yet.

## How it is laid out

```
src/main/java/com/example/demo/
  controller/   HTTP: request and response records, the REST controller, the error handler
  service/      business rules and transaction boundaries
  repository/   Spring Data interfaces
  domain/       entities
  config/       properties, startup validation, cross-cutting filters
```

Rules worth knowing before editing:

- **Flyway owns the schema.** `ddl-auto: validate` — Hibernate checks the entity against the
  migration and refuses to start if they disagree. To change a table, add a `V<n>__*.sql`.
- **Configuration is validated at startup.** Every variable in `.env.example` is required;
  missing ones are reported together, by name, before the context loads.
- **Logs are JSON**, one object per line, with the correlation id as a field. Every request
  gets an `X-Correlation-Id` (the caller's, if supplied) and it is echoed in the response and
  attached to error bodies.
- **Errors are RFC 9457 problem documents**, never stack traces.
- **Formatting is a build failure**, not a review comment — `./gradlew spotlessApply`.

## Why this project exists

It is a *reference project* for the kitbash generator: recipe content is extracted from here,
and a CI test asserts that generating this stack reproduces this directory byte for byte. It is
not a template — there are no placeholders in it, and it builds and runs on its own.

The names that become generator variables are recorded in
[`reference-variables.json`](reference-variables.json). If you rename the package, the group or
the example entity, update that file in the same commit.
