plugins {
    id("kitbash.java-conventions")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

// The only module that knows Spring exists.
dependencies {
    implementation(project(":core"))
    implementation(project(":catalog"))
    implementation(project(":render"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Recipes are read from /recipes at boot rather than staged onto the classpath (§10):
// they are files in git, and the application either loads the whole tree or refuses to
// start. Determinism across JVM invocations is asserted in `render`, over the real
// pipeline, so nothing here needs a forked JVM any more.
