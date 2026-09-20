plugins {
    id("kitbash.java-conventions")
}

// The matrix runner and the on-demand build-validation job. It drives the generator the
// same way the CLI does — no Spring, no database — so a red cell means the generator is
// broken rather than the deployment (§12).
dependencies {
    implementation(project(":core"))
    implementation(project(":catalog"))
    implementation(project(":render"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.jackson.databind)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") {
    // Adopting the generated tree into a reference project is an extraction-time step, never a
    // normal one, so it takes a flag and the flag has to be forwarded to the forked test JVM.
    systemProperty(
        "kitbash.reference.adopt",
        providers.systemProperty("kitbash.reference.adopt").getOrElse("false"),
    )

    // The equality test reads /recipes and /reference, neither of which Gradle would otherwise
    // know about — so editing a manifest left the task UP-TO-DATE and the test passing against
    // the previous catalog. A stale green is worse than a red.
    inputs.dir("${rootDir}/../recipes").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("${rootDir}/../reference").withPathSensitivity(PathSensitivity.RELATIVE)
}
