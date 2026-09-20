# `/recipes` — the catalog

One directory per recipe, each a manifest plus the files and patch operations it contributes.
Recipes are the *only* place in this repository allowed to know a technology's name (§19).

The manifest format is specified in [`docs/recipe-format.md`](../docs/recipe-format.md) and
enforced by [`_schema/recipe.schema.json`](_schema/recipe.schema.json), which the loader validates
against at boot and which editors can be pointed at directly:

```yaml
# yaml-language-server: $schema=../_schema/recipe.schema.json
```

Six recipes today, extracted from [`/reference/spring-boot-java-gradle-layered`](../reference/spring-boot-java-gradle-layered):

| Recipe | Kind | Provides |
| --- | --- | --- |
| `base` | base | `project-root` |
| `build-gradle-kts` | base | `build-tool` |
| `backend-spring-java` | backend | `http-server`, `rest-api`, `openapi-spec`, `jvm-project` |
| `db-postgres-flyway` | feature | `database` |
| `infra-docker` | infra | `containers`, `docker` |
| `ci-gitlab` | ci | `ci` |

A CI test renders them with the selection in that project's `reference-variables.json` and asserts
the result equals the directory byte for byte, so the two cannot drift.

Planned shape (§16):

```
/recipes
  /base /backend-spring-java /backend-spring-kotlin
  /frontend-react-vite /feature-auth-jwt /feature-observability
  /db-postgres-flyway /infra-docker /ci-gitlab /ci-github
```

**Direction of travel:** recipe content is extracted from a real compiling project in
`/reference`, never written from scratch (§4). If you are editing template soup here to fix a
generated project, fix the reference project and re-extract instead.
