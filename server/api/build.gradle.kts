plugins {
    id("kitbash.java-conventions")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

// The only module that knows Spring exists.
dependencies {
    implementation(project(":core"))
    implementation(project(":catalog"))
    implementation(project(":render"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // §10's data model. JDBC rather than JPA: the rows are four flat tables with jsonb columns
    // read back as text, and an ORM would add a mapping layer, a dialect and a lazy-loading
    // failure mode for no gain. There is no entity graph here to map.
    // §18 settles the audience: one internal team behind the SSO they already have. That is why
    // this is a resource server and nothing else — no registration, no password handling, no
    // tenancy. `owner_id` is the token's subject.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    // Publishes the OpenAPI document the web client's types are generated from (§9).
    implementation(libs.springdoc.openapi.webmvc)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
    testImplementation(libs.flyway.postgresql)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Recipes are read from /recipes at boot rather than staged onto the classpath (§10):
// they are files in git, and the application either loads the whole tree or refuses to
// start. Determinism across JVM invocations is asserted in `render`, over the real
// pipeline, so nothing here needs a forked JVM any more.

// The OpenAPI document is checked in so the web build needs no running server. Regenerating it
// is deliberate — the diff is the point — so the flag has to reach the forked test JVM.
tasks.named<Test>("test") {
    systemProperty(
        "kitbash.openapi.update",
        providers.systemProperty("kitbash.openapi.update").getOrElse("false"),
    )
    // The §10 schema snapshot is regenerated deliberately too — the diff is how a migration gets
    // reviewed as a shape rather than as a list of statements.
    systemProperty(
        "kitbash.schema.update",
        providers.systemProperty("kitbash.schema.update").getOrElse("false"),
    )

    // docker-java — which Testcontainers uses — defaults to Docker API v1.32, and daemons from
    // Docker 29 onward refuse it outright ("Could not find a valid Docker environment"). It reads
    // `api.version` as a JVM property and ignores DOCKER_API_VERSION, so the env var a developer
    // would reach for has to be forwarded. Unset on CI runners, whose daemons accept the default.
    //
    //   DOCKER_API_VERSION=$(docker version --format '{{.Server.APIVersion}}') ./gradlew :api:test
    providers.environmentVariable("DOCKER_API_VERSION").orNull?.let { systemProperty("api.version", it) }
}
