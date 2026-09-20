package dev.kitbash.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kitbash.catalog.CatalogLoader;
import dev.kitbash.catalog.metadata.MetadataAssembler;
import java.io.PrintStream;

/**
 * {@code kitbash catalog --json} — the same metadata document {@code GET /api/v1/metadata} serves.
 *
 * <p>The same, not similar: both call {@link MetadataAssembler}, and a test asserts the two
 * payloads are byte-identical. Two assemblers would drift, and the drift would first be visible as
 * a CLI that describes a catalog the wizard does not offer.
 */
final class CatalogCommand {

    private static final ObjectMapper JSON = new ObjectMapper();

    int run(Arguments arguments, PrintStream out, PrintStream err) {
        try {
            CatalogLoader.LoadedCatalog catalog = CatalogLocator.load(arguments);
            out.println(JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(MetadataAssembler.assemble(catalog.catalog())));
            return 0;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ErrorOutput.print(e.getMessage(), err);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return ErrorOutput.print("Could not write the catalog document: " + e.getMessage(), err);
        }
    }
}
