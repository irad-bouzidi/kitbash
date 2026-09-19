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
RUN cd /tmp/warmup \
    && ./gradlew build -x test --no-daemon \
    && cd / && rm -rf /tmp/warmup \
    && rm -rf "$GRADLE_USER_HOME/daemon" "$GRADLE_USER_HOME/native"

# Generated builds run as a non-root user, as they would on a developer's machine.
RUN useradd --create-home --shell /bin/bash builder \
    && chown -R builder:builder "$GRADLE_USER_HOME" \
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
