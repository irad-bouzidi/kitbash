plugins {
    java
    id("com.diffplug.spotless")
}

group = "dev.kitbash"
version = "0.1.0-SNAPSHOT"

java {
    // Pinned through a toolchain so the build does not depend on the developer's
    // JAVA_HOME; Gradle provisions or rejects, it never silently uses 17.
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

spotless {
    // LF everywhere regardless of the developer's core.autocrlf: the zip writer's
    // byte-equality guarantee cannot survive a platform-dependent checkout.
    lineEndings = com.diffplug.spotless.LineEnding.UNIX

    java {
        target("src/**/*.java")
        palantirJavaFormat("2.57.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
