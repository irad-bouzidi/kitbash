# Multi-stage: the JDK, the Maven repository and the source never reach the runtime image.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

# Copied first so a source-only change does not re-resolve dependencies. `go-offline` is
# deliberately not used: it resolves plugins this build does not run and fails on ones it
# cannot see, which makes a cold build slower rather than faster.
COPY mvnw ./
COPY .mvn ./.mvn
COPY pom.xml ./
RUN ./mvnw -B -q dependency:resolve

COPY src ./src
# Extracted into layers so a dependency change and a code change do not invalidate
# the same image layer: the dependency layers are rebuilt only when the set moves.
# The jar is renamed so the runtime stage does not carry a version in its ENTRYPOINT.
RUN ./mvnw -B -q -DskipTests package && \
    java -Djarmode=tools -jar target/*.jar extract --layers --destination /build/extracted && \
    mv /build/extracted/application/*.jar /build/extracted/application/app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime

# Non-root, with no login shell and no home directory to write into.
RUN addgroup -S demo && adduser -S -G demo -H -s /sbin/nologin demo

WORKDIR /app
COPY --from=build --chown=demo:demo /build/extracted/dependencies/ ./
COPY --from=build --chown=demo:demo /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=demo:demo /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=demo:demo /build/extracted/application/ ./

USER demo
EXPOSE 8080

# Container-aware defaults; the JVM reads the cgroup limit rather than the host's memory.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -q -O- http://localhost:8080/actuator/health/readiness | grep -q UP || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
