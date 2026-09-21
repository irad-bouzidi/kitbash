package com.example.demo.config

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis

/**
 * Turns a missing-variable failure into Boot's Description/Action block instead of a stack trace.
 * A developer whose first run fails should read a sentence, not forty frames of Spring internals.
 */
class MissingEnvironmentFailureAnalyzer : AbstractFailureAnalyzer<MissingEnvironmentVariablesException>() {
    override fun analyze(
        rootFailure: Throwable,
        cause: MissingEnvironmentVariablesException,
    ): FailureAnalysis {
        val listed =
            cause.missing.joinToString(System.lineSeparator()) { name ->
                "    %-24s %s".format(name, RequiredEnvironmentValidator.describe(name))
            }
        val description =
            "The application needs ${cause.missing.size} environment variable(s) that are not set:" +
                System.lineSeparator() + System.lineSeparator() + listed

        val action =
            """
            Set them and start again. Either:

                cp .env.example .env     and export it (or let your IDE load it), or
                docker compose up        which sets all of them for you.

            The full list is in .env.example.
            """.trimIndent()

        return FailureAnalysis(description, action, cause)
    }
}
