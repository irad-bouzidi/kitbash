package dev.kitbash.api.generate;

import dev.kitbash.core.selection.Selection;
import dev.kitbash.core.selection.SelectionEnvelope;
import java.util.Map;

/**
 * The §7 selection envelope, exactly as clients will send it once the resolver exists.
 *
 * <p>Phase 0 honours {@code projectName} and the {@code groupId}, {@code packageName} and {@code
 * javaVersion} variables; everything else is carried and ignored. Modelling it in full now means no
 * client has to change shape when phase 1 starts honouring the rest.
 *
 * <p>Unknown option keys are accepted rather than rejected **for this phase only** — there is no
 * catalog yet to check them against, so rejecting would mean inventing a list to reject from.
 */
public record GenerateRequest(
        Integer schemaVersion, String projectName, Map<String, Object> options, Map<String, String> variables) {

    public Selection toSelection() {
        return new SelectionEnvelope(
                        schemaVersion == null ? SelectionEnvelope.CURRENT_SCHEMA_VERSION : schemaVersion,
                        projectName,
                        options,
                        variables)
                .parse();
    }
}
