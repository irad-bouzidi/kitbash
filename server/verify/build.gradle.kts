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

    implementation(libs.jackson.databind)

    // The matrix prints a report somebody reads in CI output. SLF4J's "no providers were found"
    // banner on stderr is noise in front of it, and the runner logs nothing through SLF4J itself.
    runtimeOnly(libs.slf4j.nop)

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

// The matrix is a task rather than a test: it builds real projects in containers for minutes at
// a time, and burying that in `check` would make every `./gradlew check` need a Docker daemon.
//
//   ./gradlew :verify:runMatrix                          # the merge-request cells
//   ./gradlew :verify:runMatrix -Pkitbash.trigger=nightly
val runMatrix by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Generates each cell and builds it in an ecosystem container (§12)."
    mainClass = "dev.kitbash.verify.MatrixMain"
    classpath = sourceSets.main.get().runtimeClasspath
    // `-Pkitbash.cell=<id>` runs exactly one cell; it is what verification/run-cell.sh passes,
    // so reproducing a red cell runs the same code CI ran.
    val cell = providers.gradleProperty("kitbash.cell").orNull
    if (cell != null) {
        args("--cell", cell)
    } else {
        args("--trigger", providers.gradleProperty("kitbash.trigger").getOrElse("merge-request"))
    }

    // Run from the repository root: the runner finds everything else relative to it, the same
    // way the API and the CLI do.
    workingDir = file("${rootDir}/..")
}
