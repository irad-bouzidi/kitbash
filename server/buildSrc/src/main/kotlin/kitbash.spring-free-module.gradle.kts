plugins {
    id("kitbash.java-conventions")
}

// §5, §6: some modules must stay free of the framework, and a convention everybody agrees to
// is not enforcement. The runtime classpath is inspected and the build fails on a forbidden
// coordinate, wired into `check` so CI enforces it too.
//
// `core` needs it because the domain has to be drivable from tests without booting a context.
// `cli` needs it because §12 has every verification cell call the generator through it: if the
// CLI can generate a project with no Spring on its classpath, the boundary is real rather than
// aspirational, and a red cell means the generator is broken rather than the deployment.
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
                    appendLine("$moduleName must stay free of the framework (plan §5, §6).")
                    appendLine("Forbidden dependencies found on the runtime classpath:")
                    violations.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("Move the code that needs a framework into `api`.")
                },
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkModulePurity)
}
