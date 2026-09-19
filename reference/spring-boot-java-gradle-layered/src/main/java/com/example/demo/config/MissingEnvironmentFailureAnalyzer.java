package com.example.demo.config;

import java.util.stream.Collectors;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns a missing-variable failure into Boot's Description/Action block instead of a stack trace.
 * A developer whose first run fails should read a sentence, not forty frames of Spring internals.
 */
public class MissingEnvironmentFailureAnalyzer extends AbstractFailureAnalyzer<MissingEnvironmentVariablesException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MissingEnvironmentVariablesException cause) {
        String description = "The application needs %d environment variable(s) that are not set:%n%n%s"
                .formatted(
                        cause.missing().size(),
                        cause.missing().stream()
                                .map(name ->
                                        "    %-24s %s".formatted(name, RequiredEnvironmentValidator.describe(name)))
                                .collect(Collectors.joining(System.lineSeparator())));

        String action =
                """
                Set them and start again. Either:

                    cp .env.example .env     and export it (or let your IDE load it), or
                    docker compose up        which sets all of them for you.

                The full list is in .env.example.""";

        return new FailureAnalysis(description, action, cause);
    }
}
