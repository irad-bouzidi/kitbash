# demo

## The stack

- **Build** — Gradle 8.14 with the Kotlin DSL, dependencies in a version catalog
- **Backend** — Spring Boot 3.5.5 on Java 21, layered architecture
- **Database** — Postgres, with Flyway migrations and JPA
- **Containers** — a compose file wiring the stack together, with healthchecks and non-root images
- **CI** — GitLab CI: build, test, format check, and a container image on the default branch
<!-- kitbash:stack -->

## Start it

```bash
docker compose up --build
```

That brings everything up, waits for the database to be healthy, and starts the service on
**http://localhost:8080** — documented at `/swagger-ui.html`, health at `/actuator/health`.

<!-- kitbash:start -->

## Work on it

```bash
cp .env.example .env          # then export it, or let your IDE load it
```

```bash
./gradlew build               # compile, format check, unit and integration tests
```

The integration tests start their own Postgres through Testcontainers, so they need a working
Docker daemon and nothing else. There is no in-memory database anywhere in the suite: the
schema, the unique index and the JPA mappings are exactly the things worth testing against the
real engine.

### If the integration tests cannot find Docker

On a very new Docker daemon, Testcontainers' client library defaults to an API version the
daemon no longer serves, and the suite fails with *"Could not find a valid Docker
environment"*. Pin the version for your machine, once:

```bash
docker version --format '{{.Server.APIVersion}}'   # e.g. 1.44
echo "api.version=1.44" >> ~/.docker-java.properties
```

Deliberately not pinned in the build: hardcoding a version breaks the opposite case, a
developer on an older daemon that does not serve it yet.

```bash
docker compose up -d db       # just the database, to run the app from your IDE
```

<!-- kitbash:work -->

## How it is laid out

```
src/main/java/com/example/demo/
  controller/   HTTP: request and response records, the REST controller, the error handler
  service/      business rules and transaction boundaries
  repository/   Spring Data interfaces
  domain/       entities
  config/       properties, startup validation, cross-cutting filters
```

<!-- kitbash:layout -->

## Rules worth knowing before editing

- **Formatting is a build failure**, not a review comment — `./gradlew spotlessApply`.
- **Configuration is validated at startup.** Every variable in `.env.example` is required;
  missing ones are reported together, by name, before the context loads.
- **Logs are JSON**, one object per line, with the correlation id as a field. Every request
  gets an `X-Correlation-Id` (the caller's, if supplied) and it is echoed in the response.
- **Errors are RFC 9457 problem documents**, never stack traces.
- **Flyway owns the schema.** `ddl-auto: validate` — Hibernate checks the entity against the
  migration and refuses to start if they disagree. To change a table, add a `V<n>__*.sql`.
<!-- kitbash:rules -->
