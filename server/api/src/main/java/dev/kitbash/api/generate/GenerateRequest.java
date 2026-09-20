package dev.kitbash.api.generate;

import dev.kitbash.core.selection.SelectionEnvelope;
import java.util.Map;

/**
 * The §7 selection envelope, exactly as clients send it.
 *
 * <p>Deliberately a thin carrier: it holds the wire shape and converts, and every decision about
 * what the values mean belongs to {@code core}'s parse stage (§6). A DTO that starts interpreting
 * its own fields is a second copy of the domain, and the two drift.
 */
public record GenerateRequest(
        Integer schemaVersion, String projectName, Map<String, Object> options, Map<String, String> variables) {

    public SelectionEnvelope toEnvelope() {
        return new SelectionEnvelope(
                schemaVersion == null ? SelectionEnvelope.CURRENT_SCHEMA_VERSION : schemaVersion,
                projectName,
                options,
                variables);
    }
}
