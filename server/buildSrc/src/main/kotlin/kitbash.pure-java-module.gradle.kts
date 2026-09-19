plugins {
    id("kitbash.java-conventions")
}

// §5: `core` is stdlib-only — no Spring, no Lombok. A convention everybody agrees
// to is not enforcement, so the runtime classpath is inspected and the build fails
// on a forbidden coordinate. Wired into `check`, which means CI enforces it too.
val forbiddenCoordinates = listOf(
    "org.springframework",
    "org.projectlombok",
    "jakarta.persistence",
)

val checkModulePurity by tasks.registering {
    group = "verification"
    description = "Fails if a framework dependency leaks onto this module's runtime classpath."

    val runtimeClasspath = configurations.named("runtimeClasspath")
    val artifacts = runtimeClasspath.map { configuration ->
        configuration.incoming.resolutionResult.allComponents
            .map { it.id.displayName }
            .filterNot { it.startsWith("project :") }
            .sorted()
    }
    val moduleName = path

    inputs.property("artifacts", artifacts)

    doLast {
        val violations = artifacts.get().filter { coordinate ->
            forbiddenCoordinates.any { forbidden -> coordinate.contains(forbidden) }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("$moduleName must depend on the JDK and nothing else (plan §5, §6).")
                    appendLine("Forbidden dependencies found on the runtime classpath:")
                    violations.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("Move the code that needs a framework into `catalog`, `render` or `api`.")
                },
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkModulePurity)
}
