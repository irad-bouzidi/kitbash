package com.example.demo.config;

import java.util.List;

/** Thrown before the context loads when a documented environment variable is not set. */
public class MissingEnvironmentVariablesException extends RuntimeException {

    private final transient List<String> missing;

    public MissingEnvironmentVariablesException(List<String> missing) {
        super("Missing required environment variables: " + String.join(", ", missing));
        this.missing = List.copyOf(missing);
    }

    public List<String> missing() {
        return missing;
    }
}
