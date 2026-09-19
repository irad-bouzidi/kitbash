# `/verification` — proving the output builds

Generated projects are verified by building them, in a container, on a machine that is not the
API host (§12, §13). This directory holds the matrix runner and one image per ecosystem.

Empty until [`kitbash-5-ci-generated-build-gate`](../docs/tasks/phase-0-walking-skeleton/kitbash-5-ci-generated-build-gate.md)
adds the first cell and the JVM image. [`kitbash-18`](../docs/tasks/phase-1-recipe-engine/kitbash-18-verification-runner.md)
generalises it into a runner; [`kitbash-35`](../docs/tasks/phase-3-catalog-breadth/kitbash-35-full-matrix-sharding.md)
scales it to the full matrix.

Planned shape:

```
/verification
  /images/jvm/Dockerfile      # JDK 21 + warm Gradle cache, nothing else
  /images/node/Dockerfile     # arrives with the frontend recipe
```

**The rule that holds from the first job:** never run a generated build on the API host.
