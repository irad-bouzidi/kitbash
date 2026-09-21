package com.example.demo.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment

/**
 * Fails startup naming every missing variable at once.
 *
 * Spring's own placeholder resolution would also fail, but it reports one variable at a time
 * inside a stack trace, so a developer with three unset variables restarts three times. The output
 * here is a single Description/Action block, produced by [MissingEnvironmentFailureAnalyzer].
 *
 * This list is the same list as `.env.example`; adding a variable means adding it to both.
 */
class RequiredEnvironmentValidator : EnvironmentPostProcessor {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        // Tests supply configuration directly rather than through the process environment.
        if (environment.matchesProfiles("test")) {
            return
        }

        val missing = REQUIRED.keys.filterNot { environment.containsProperty(it) }.sorted()

        if (missing.isNotEmpty()) {
            throw MissingEnvironmentVariablesException(missing)
        }
    }

    companion object {
        private val REQUIRED =
            mapOf(
                "DEMO_DB_URL" to "JDBC URL, e.g. jdbc:postgresql://localhost:5432/demo",
                "DEMO_DB_USERNAME" to "Database user",
                "DEMO_DB_PASSWORD" to "Database password",
                "DEMO_ENVIRONMENT_NAME" to "Environment label for /actuator/info, e.g. local",
            )

        internal fun describe(name: String): String = REQUIRED[name] ?: ""
    }
}
