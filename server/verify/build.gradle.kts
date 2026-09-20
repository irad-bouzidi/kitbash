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

// Adopting the generated tree into a reference project is an extraction-time step, never a
// normal one, so it takes a flag and the flag has to be forwarded to the forked test JVM.
tasks.named<Test>("test") {
    systemProperty(
        "kitbash.reference.adopt",
        providers.systemProperty("kitbash.reference.adopt").getOrElse("false"),
    )
}
