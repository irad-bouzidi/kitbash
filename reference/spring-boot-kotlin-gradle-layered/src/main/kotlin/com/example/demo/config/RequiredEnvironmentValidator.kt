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
        /**
         * Built rather than declared, so that a recipe contributing a variable can contribute its
         * description too.
         *
         * A feature that reads a new variable adds a line at the marker below, and the startup
         * message then names the variable *and* says what it is for — the difference between "set
         * DEMO_AUTH_ISSUER_URI" and a developer guessing what a URI of what is wanted.
         *
         * Two things this shape has to get right. The marker is not spelled out in this comment,
         * because a patch inserts at the first line that matches and a marker quoted in prose is a
         * marker. And each description is short enough that its `put` never wraps: the formatter
         * lays out the *rendered* line, so a longer prefix than this project's would rewrap a line
         * the template had already wrapped, and the generated build would fail its own format
         * check.
         */
        private val REQUIRED: Map<String, String> =
            buildMap {
                put("DEMO_ENVIRONMENT_NAME", "Environment label for /actuator/info")
                put("DEMO_DB_URL", "JDBC URL of the database")
                put("DEMO_DB_USERNAME", "Database user")
                put("DEMO_DB_PASSWORD", "Database password")
                // kitbash:required-environment
            }

        internal fun describe(name: String): String = REQUIRED[name] ?: ""
    }
}
