plugins {
    id("kitbash.java-conventions")
}

// The matrix runner and the on-demand build-validation job. It drives the generator the
// same way the CLI does — no Spring, no database — so a red cell means the generator is
// broken rather than the deployment (§12).
// See the note on `quietLogging` below: a logging binding must not travel to this module's
// consumers, so it lives in a configuration only the two runnable tasks put on their classpath.
val quietLogging: Configuration by configurations.creating

dependencies {
    implementation(project(":core"))
    implementation(project(":catalog"))
    implementation(project(":render"))

    implementation(libs.jackson.databind)

    // The matrix prints a report somebody reads in CI output. SLF4J's "no providers were found"
    // banner on stderr is noise in front of it, and the runner logs nothing through SLF4J itself.
    //
    // Its own configuration rather than `runtimeOnly`, because kitbash-37 made this module a
    // dependency of `:api`, and a runtime dependency is inherited: the NOP binding reached Spring
    // Boot's classpath and every context failed to start with "LoggerFactory is not a Logback
    // LoggerContext". A binding is a choice the program at the top of the classpath makes, and the
    // program at the top here is the matrix rather than the API.
    quietLogging(libs.slf4j.nop)

    // The hostile-input corpus, shared rather than copied: §13's rules and the values that
    // probe them belong together wherever they are checked.
    testImplementation(testFixtures(project(":core")))

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
//   ./gradlew :verify:runMatrix -Pkitbash.enumerate=true            # the full matrix (§35)
//   ./gradlew :verify:runMatrix -Pkitbash.enumerate=true -Pkitbash.shard=2/6
val runMatrix by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Generates each cell and builds it in an ecosystem container (§12)."
    mainClass = "dev.kitbash.verify.MatrixMain"
    classpath = sourceSets.main.get().runtimeClasspath + quietLogging
    // `-Pkitbash.cell=<id>` runs exactly one cell; it is what verification/run-cell.sh passes,
    // so reproducing a red cell runs the same code CI ran.
    val cell = providers.gradleProperty("kitbash.cell").orNull
    if (cell != null) {
        args("--cell", cell)
    } else {
        args("--trigger", providers.gradleProperty("kitbash.trigger").getOrElse("merge-request"))
        // `-Pkitbash.enumerate=true` runs the full matrix derived from the catalog (§35) instead
        // of the checked-in cells; `-Pkitbash.shard=k/n` runs one deterministic slice of it.
        if (providers.gradleProperty("kitbash.enumerate").orNull == "true") {
            args("--enumerate")
        }
    }
    providers.gradleProperty("kitbash.shard").orNull?.let { args("--shard", it) }

    // Run from the repository root: the runner finds everything else relative to it, the same
    // way the API and the CLI do.
    workingDir = file("${rootDir}/..")
}

// The weekly freshness job's first half (§36): rewrite the versions the recipes pin, and write a
// summary the pull request body is made of. Judging the result is the workflow's job.
//
//   ./gradlew :verify:bumpVersions
val bumpVersions by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Bumps the versions recipes pin to the latest releases (§36)."
    mainClass = "dev.kitbash.verify.bump.BumpMain"
    classpath = sourceSets.main.get().runtimeClasspath + quietLogging
    // 3 means "nothing to do", which is the answer on most Mondays.
    isIgnoreExitValue = true
}
