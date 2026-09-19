# `/recipes` — the catalog

One directory per recipe, each a manifest plus the files and patch operations it contributes.
Recipes are the *only* place in this repository allowed to know a technology's name (§19).

Empty until [`kitbash-7-recipe-manifest-and-loader`](../docs/tasks/phase-1-recipe-engine/kitbash-7-recipe-manifest-and-loader.md)
defines the manifest, and [`kitbash-13`](../docs/tasks/phase-1-recipe-engine/kitbash-13-recipes-extract-phase0-stack.md)
extracts the first recipes from `/reference`.

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
