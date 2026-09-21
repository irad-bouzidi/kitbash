# The JVM ecosystem image: a JDK, a warm Gradle cache, nothing else.
#
# Generated builds run in here and never on the API host (§12). The image is built
# from the repository root so the warm-up can use the reference project — whose
# dependency set is, by construction, the dependency set of everything phase 0
# generates.
FROM eclipse-temurin:21-jdk-noble

# unzip to unpack the generated archive, git because a generated project arrives as
# a repository and the build may read it. Nothing else: an ecosystem image that
# accumulates tools stops resembling a user's machine, which is what it exists to be.
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV GRADLE_USER_HOME=/opt/gradle-home
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.welcome=never"

# Maven's equivalent, added by §28 when Maven became a selectable build tool. Outside the build
# user's home for the same reason Gradle's is: the warm-up runs as root and the cell runs as
# `builder`, and a cache in root's home is a cache the cell cannot read — the mistake the Node
# image's corepack cache made (see verification/README.md).
#
# Both variables are needed and they mean different things. MAVEN_USER_HOME is what the wrapper
# script reads to decide where to unpack the Maven distribution; maven.repo.local is what Maven
# itself reads to decide where the artefacts go. Setting only one warms only half of it.
ENV MAVEN_USER_HOME=/opt/maven-home
ENV MAVEN_OPTS="-Dmaven.repo.local=/opt/maven-home/repository"

# Warm the cache by actually building the reference project, then throwing the
# project away and keeping GRADLE_USER_HOME. Resolving dependencies would warm
# less: this also populates the Gradle distribution, the wrapper, the Kotlin DSL
# script cache and the toolchain.
#
# A warm cache is a speed optimisation, not a correctness one. Builds still resolve
# against the network, so a dependency that disappears upstream still breaks the
# cell — which is the point. An offline cache would silently go stale and then hide
# real breakage (§12).
COPY reference/spring-boot-java-gradle-layered /tmp/warmup
#
# `compileTestJava` rather than `build -x test`: the latter never resolves the test
# classpath, so every cell re-downloaded JUnit, AssertJ and Testcontainers before it
# could compile a test. Compiling the tests warms exactly those coordinates and still
# needs no Docker daemon at image-build time, which running them would.
RUN cd /tmp/warmup \
    && ./gradlew build -x test compileTestJava --no-daemon \
    && cd / && rm -rf /tmp/warmup \
    && rm -rf "$GRADLE_USER_HOME/daemon" "$GRADLE_USER_HOME/native"

# The same warm-up, against the Maven reference project. Without it the Maven cell re-downloaded
# Maven itself plus Spring Boot, Flyway and Testcontainers on every run — 72 seconds against the
# 22 the Gradle cell takes, for work the image can do once.
#
# `test-compile` rather than `package`, for exactly the reason `compileTestJava` is above: it
# resolves the test classpath, which is where most of those coordinates live, and needs no Docker
# daemon at image-build time as running the tests would.
COPY reference/spring-boot-java-maven-layered /tmp/maven-warmup
RUN cd /tmp/maven-warmup \
    && ./mvnw -B -q test-compile \
    && cd / && rm -rf /tmp/maven-warmup

# Generated builds run as a non-root user, as they would on a developer's machine.
RUN useradd --create-home --shell /bin/bash builder \
    && chown -R builder:builder "$GRADLE_USER_HOME" "$MAVEN_USER_HOME" \
    && mkdir -p /workspace \
    && chown builder:builder /workspace
USER builder

WORKDIR /workspace

# The project is mounted read-only at /input and copied here before building, so
# the container's uid never has to match the host's and a generated build cannot
# write back to the runner's filesystem.
COPY --chown=builder:builder verification/images/jvm/entrypoint.sh /usr/local/bin/entrypoint.sh
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["./gradlew", "build", "--no-daemon"]
