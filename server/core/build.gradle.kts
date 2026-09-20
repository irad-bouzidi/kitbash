plugins {
    id("kitbash.pure-java-module")
    // The hostile-input corpus is a test fixture rather than a test class because kitbash-39 and
    // kitbash-47 both want it from other modules, and a corpus that has to be copied to be reused
    // is a corpus that drifts.
    `java-test-fixtures`
}

// Selection, Recipe, Capability, FilePlan, PatchOp, hooks; resolver; patch appliers; zip
// writer. Framework-free — no Spring, no Lombok, no container — and `checkModulePurity`
// fails the build if that ever stops being true.
//
// Jackson is the one exception, and it is a deliberate one: §4 requires the patch appliers
// to parse and re-serialise real YAML and JSON rather than append text, and the JDK ships
// neither parser. See docs/adr/0003-jackson-in-core-for-format-aware-patching.md.
dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)

    testImplementation(testFixtures(project(":core")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)

    testFixturesImplementation(platform(libs.junit.bom))
}

// Golden files are regenerated deliberately, never as a side effect of a normal run:
//   ./gradlew :core:test -Dkitbash.golden.update=true
// The flag has to be forwarded because tests run in a forked JVM.
tasks.named<Test>("test") {
    systemProperty(
        "kitbash.golden.update",
        providers.systemProperty("kitbash.golden.update").getOrElse("false"),
    )
}
