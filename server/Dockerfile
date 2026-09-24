# Built from the repository root, because the application reads the recipe catalog
# from /recipes at boot (§10) and the runtime image therefore has to carry it.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

COPY server/gradlew server/gradlew.bat ./server/
COPY server/gradle ./server/gradle
RUN cd server && ./gradlew --version --no-daemon

COPY server/settings.gradle.kts server/build.gradle.kts ./server/
COPY server/buildSrc ./server/buildSrc
COPY server/core ./server/core
COPY server/catalog ./server/catalog
COPY server/render ./server/render
COPY server/api ./server/api
COPY server/verify ./server/verify
COPY server/cli ./server/cli
COPY recipes ./recipes

RUN cd server && ./gradlew :api:bootJar --no-daemon && \
    java -Djarmode=tools -jar api/build/libs/*.jar extract --layers --destination /build/extracted && \
    mv /build/extracted/application/*.jar /build/extracted/application/app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime

# git, for kitbash-46's push target. The zip already contains a real repository with the commit in
# it, so pushing is `git push` from that tree — no re-render, and the commit hash is preserved by
# construction. Shelling out needs the binary, and a JRE image has none: without this line the
# feature works on a developer's machine and fails in the container, which is the shape of defect
# kitbash-42's smoke test was built to catch.
RUN apk add --no-cache git

RUN addgroup -S kitbash && adduser -S -G kitbash -H -s /sbin/nologin kitbash

WORKDIR /app
COPY --from=build --chown=kitbash:kitbash /build/extracted/dependencies/ ./
COPY --from=build --chown=kitbash:kitbash /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=kitbash:kitbash /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=kitbash:kitbash /build/extracted/application/ ./
# The catalog is data the application refuses to start without, so it ships beside
# the jar rather than being mounted and hoped for.
COPY --chown=kitbash:kitbash recipes ./recipes

USER kitbash
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
    CMD wget -q -O- http://localhost:8080/actuator/health | grep -q UP || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
