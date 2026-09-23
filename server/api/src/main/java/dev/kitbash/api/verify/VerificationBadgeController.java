package dev.kitbash.api.verify;

import dev.kitbash.core.recipe.Catalog;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/verification} — what the matrix knows, for the wizard to badge with (§9, §12).
 *
 * <p>A document of its own rather than a field on the metadata document, for reasons
 * {@link VerificationBadges} sets out: verification is a property of combinations rather than of
 * choices, and it changes within a catalog digest while the metadata document does not.
 *
 * <p>Open to any authenticated caller and cached by entity tag. A client that already holds the tag
 * gets a 304 — badges are read on every wizard load and change once a night.
 */
@RestController
@RequestMapping("/api/v1")
public class VerificationBadgeController {

    private final VerificationBadges badges;
    private final MatrixLogs logs;
    private final Catalog catalog;

    public VerificationBadgeController(VerificationBadges badges, MatrixLogs logs, Catalog catalog) {
        this.badges = badges;
        this.logs = logs;
        this.catalog = catalog;
    }

    @GetMapping("/verification")
    public ResponseEntity<VerificationDocument> verification(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        VerificationBadges.Badges current = badges.forCatalog(catalog.digest());
        String eTag = current.eTag();

        if (eTag.equals(ifNoneMatch)) {
            return ResponseEntity.status(304).eTag(eTag).build();
        }

        return ResponseEntity.ok()
                .eTag(eTag)
                // Revalidate rather than trust: a nightly can land at any hour, and the tag makes
                // revalidation cheap enough that caching it for a fixed period buys nothing.
                .cacheControl(CacheControl.noCache())
                .body(VerificationDocument.of(current));
    }

    /**
     * The log of a cell the published run names (§38).
     *
     * <p>§38 wants the failure reason <em>and a link to the log</em> on the badge, because "pnpm
     * typecheck failed" tells a user which step and not which line. The log is the matrix's own, so
     * it is read from where the runner wrote it.
     *
     * <p>The id is checked against the published document rather than sanitised. That is the
     * stronger rule and the simpler one: a cell the current results do not name has no log worth
     * serving, and an id that cannot reach the filesystem cannot be made to traverse it.
     */
    @GetMapping(value = "/verification/cells/{cellId}/log", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> cellLog(@PathVariable String cellId) {
        if (!badges.published(cellId)) {
            throw new UnknownVerificationCellException(cellId);
        }
        return logs.forCell(cellId)
                .map(text ->
                        ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(text))
                .orElseThrow(() -> new UnknownVerificationCellException(cellId));
    }

    /**
     * What a client renders.
     *
     * <p>{@code choices} and {@code pairs} are deliberately two lists over the same shape. A choice
     * badge answers "has anything containing this ever been built?"; a pair badge answers "is this
     * combination known to be red?" — and §9 puts the warning at the pairing precisely because the
     * second question is the one a person is asking when they set the second dropdown.
     *
     * @param staleFor the digest the available results belong to when it is not this catalog's;
     *     null otherwise. A client showing "not verified" for a reason can say which reason.
     */
    public record VerificationDocument(
            int schemaVersion,
            String catalogDigest,
            String generatedAt,
            String staleFor,
            List<VerificationBadges.Badge> choices,
            List<VerificationBadges.Badge> pairs) {

        public static final int SCHEMA_VERSION = 1;

        static VerificationDocument of(VerificationBadges.Badges badges) {
            return new VerificationDocument(
                    SCHEMA_VERSION,
                    badges.catalogDigest(),
                    badges.generatedAt(),
                    badges.staleFor(),
                    badges.choices(),
                    badges.pairs());
        }
    }
}
