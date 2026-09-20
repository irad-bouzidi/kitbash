plugins {
    // Spring appearing on this module's classpath is a build failure, not a review comment.
    id("kitbash.spring-free-module")
    application
}

// Offline generation: no server, no database, no Spring context. §12 has every verification
// cell call the generator through here rather than over HTTP, which keeps verification
// independent of the API, its auth and its persistence — a red cell then means the generator
// is broken rather than the deployment.
//
// It is also the cheapest check that the §6 module boundary holds: if this can generate a
// project while depending only on core, catalog and render, the domain really is Spring-free.
dependencies {
    implementation(project(":core"))
    implementation(project(":catalog"))
    implementation(project(":render"))
    implementation(libs.jackson.databind)
    // A library on the classpath logs through SLF4J, and with no binding it prints three
    // warnings to stderr. §14 has CI parsing stderr as the error envelope, so a no-op binding
    // is the difference between a parseable failure and one that needs a filter.
    runtimeOnly(libs.slf4j.nop)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "dev.kitbash.cli.Kitbash"
    applicationName = "kitbash"
}
