plugins {
    id("kitbash.java-conventions")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.pebble)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The determinism suite forks two JVMs and compares their output; same-JVM equality would not
// catch a hash-order or a time-of-day dependence, which are exactly the bugs it guards against.
tasks.named<Test>("test") {
    systemProperty("kitbash.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}
