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
