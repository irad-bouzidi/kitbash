plugins {
    id("kitbash.java-conventions")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":render"))
    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The worker runs in a JVM this module launches, so the tests need the same classpath the
// production launcher would build. Passed rather than reconstructed: a test that guessed at the
// classpath would be testing its guess.
tasks.named<Test>("test") {
    systemProperty("kitbash.sandbox.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}
