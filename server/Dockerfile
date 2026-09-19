# Built from the repository root, because the phase-0 generator stages /reference
# onto the api classpath at build time. kitbash-13 replaces that with /recipes and
# the context stays the root for the same reason.
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
COPY reference ./reference

RUN cd server && ./gradlew :api:bootJar --no-daemon && \
    java -Djarmode=tools -jar api/build/libs/*.jar extract --layers --destination /build/extracted && \
    mv /build/extracted/application/*.jar /build/extracted/application/app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S kitbash && adduser -S -G kitbash -H -s /sbin/nologin kitbash

WORKDIR /app
COPY --from=build --chown=kitbash:kitbash /build/extracted/dependencies/ ./
COPY --from=build --chown=kitbash:kitbash /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=kitbash:kitbash /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=kitbash:kitbash /build/extracted/application/ ./

USER kitbash
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
    CMD wget -q -O- http://localhost:8080/actuator/health | grep -q UP || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
