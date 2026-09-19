plugins {
    id("kitbash.pure-java-module")
}

// Selection, Recipe, Capability, FilePlan, PatchOp, hooks; resolver; patch
// appliers; zip writer. Depends on the JDK and nothing else — enforced by
// `checkModulePurity`, not by convention.
dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
