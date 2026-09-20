package dev.kitbash.api.catalog;

import dev.kitbash.catalog.metadata.MetadataAssembler;
import dev.kitbash.catalog.metadata.MetadataDocument;
import dev.kitbash.core.recipe.Catalog;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/metadata} — the whole catalog, and the hinge of the design (§8).
 *
 * <p>The document is assembled once at construction because the catalog is immutable for the life
 * of the process (§10). That is not an optimisation: it is what makes the ETag honest. The entity
 * tag <i>is</i> the catalog digest, so a client holding that tag holds the whole catalog, and
 * "which catalog rendered this?" is answerable from a response header rather than from a log.
 */
@RestController
@RequestMapping("/api/v1")
public class MetadataController {

    private final MetadataDocument document;
    private final String etag;

    public MetadataController(Catalog catalog) {
        this.document = MetadataAssembler.assemble(catalog);
        this.etag = "\"" + catalog.digest() + "\"";
    }

    @GetMapping("/metadata")
    public ResponseEntity<MetadataDocument> metadata(
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(304).eTag(etag).build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                // Immutable per digest, so a client may hold it for as long as it likes — and the
                // digest changing is what invalidates it, not a clock.
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .header("X-Kitbash-Catalog-Digest", document.catalogDigest())
                .body(document);
    }
}
