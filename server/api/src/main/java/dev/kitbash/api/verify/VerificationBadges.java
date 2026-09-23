package dev.kitbash.api.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The matrix, aggregated into the badges §9 wants at an option pairing.
 *
 * <p>§9 is specific about placement: a combination the nightly reports red gets a warning badge
 * <b>at the offending option pairing</b>, not in a page banner, because that is where the decision
 * is made. So the unit here is a <em>pair</em> of chosen values — {@code backend=…} with
 * {@code buildTool=…} — and not a recipe, an option or a cell.
 *
 * <h2>Why this is not in the metadata document</h2>
 *
 * <p>{@code kitbash-15} reserved a {@code verification} field on each choice, and the matrix has
 * since shown that the reservation was for the wrong shape. Two facts settle it.
 *
 * <p>Verification is a property of <em>combinations</em>. Kotlin is not red; Kotlin <em>with the
 * typed client</em> was, in §36, while Kotlin with everything else was green — and a per-choice
 * field could only have said "Kotlin: failed", which is both alarming and false.
 *
 * <p>And the metadata document is immutable per catalog digest, which is what makes its ETag
 * honest: two responses carrying the same digest are the same bytes. Verification results change
 * <em>within</em> a digest — the nightly runs later than the deploy — so putting them there would
 * quietly turn a correct cache into a stale one.
 *
 * <p>Hence a separate document with its own entity tag, over the catalog digest <b>and</b> the run
 * that produced it.
 */
public class VerificationBadges {

    private static final Logger log = LoggerFactory.getLogger(VerificationBadges.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;

    /** What was last read, and the file stamp it was read at. Null until the first read. */
    private volatile Snapshot snapshot;

    private volatile long readAt = -1;

    public VerificationBadges(Path file) {
        this.file = file;
    }

    /**
     * The badges for this catalog, or an empty document when there are none for it.
     *
     * <p>Results from a <em>different</em> catalog are discarded rather than shown. §38 is explicit
     * that badges must not show results from another catalog, and the failure mode it prevents is
     * the expensive kind: a combination marked green by yesterday's recipes, chosen on that basis,
     * and broken by today's.
     */
    public Badges forCatalog(String catalogDigest) {
        Snapshot current = current();
        if (current == null) {
            return Badges.none(catalogDigest);
        }
        if (!current.catalogDigest().equals(catalogDigest)) {
            return Badges.stale(catalogDigest, current.catalogDigest(), current.generatedAt());
        }
        return current.badges();
    }

    /**
     * The document, re-read when the file has changed.
     *
     * <p>Polled by last-modified rather than watched: the file is replaced once a night by a
     * process on the other side of a deployment, and a watch service across that boundary is a
     * daemon thread and a class of bug in exchange for freshness nobody can perceive.
     */
    private Snapshot current() {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            long stamp = Files.getLastModifiedTime(file).toMillis();
            Snapshot cached = snapshot;
            if (cached != null && stamp == readAt) {
                return cached;
            }
            Snapshot parsed = parse(JSON.readTree(file.toFile()));
            snapshot = parsed;
            readAt = stamp;
            return parsed;
        } catch (IOException unreadable) {
            // Badges are decoration on a working wizard. A results file that cannot be read makes
            // every combination "not verified", which is exactly what it is when nothing can say
            // otherwise — and is not a reason to fail a request for the catalog.
            log.warn("The verification results at {} could not be read ({})", file, unreadable.getMessage());
            return null;
        }
    }

    static Snapshot parse(JsonNode document) {
        String digest = document.path("catalogDigest").asText("");
        String generatedAt = document.path("generatedAt").asText(null);

        Map<String, Verdict> pairs = new TreeMap<>();
        Map<String, Verdict> singles = new TreeMap<>();
        // Which cells the document names. A log is served only for one of these, which is both the
        // correct rule — the log belongs to the published run — and the reason a cell id out of a
        // URL can never become a path.
        List<String> cellIds = new ArrayList<>();

        for (JsonNode cell : document.path("cells")) {
            boolean passed = "PASSED".equals(cell.path("outcome").asText());
            String cellId = cell.path("id").asText("");
            String failedStep = cell.path("failedStep").asText(null);
            String reproduce = cell.path("reproduce").asText(null);
            cellIds.add(cellId);

            List<String> chosen = new ArrayList<>();
            cell.path("options").properties().forEach(entry -> {
                JsonNode value = entry.getValue();
                // A flag that is off is not a choice anybody made visible in the wizard as a
                // pairing, and badging "containers: false" against everything would put a badge on
                // every control in the page. Only positive selections are pairings.
                if (value.isBoolean() && !value.asBoolean()) {
                    return;
                }
                chosen.add(entry.getKey() + "=" + (value.isBoolean() ? "true" : value.asText()));
            });

            for (String one : chosen) {
                singles.merge(one, verdict(passed, cellId, failedStep, reproduce), Verdict::worse);
            }
            for (int i = 0; i < chosen.size(); i++) {
                for (int j = i + 1; j < chosen.size(); j++) {
                    pairs.merge(
                            key(chosen.get(i), chosen.get(j)),
                            verdict(passed, cellId, failedStep, reproduce),
                            Verdict::worse);
                }
            }
        }

        return new Snapshot(
                digest,
                generatedAt,
                java.util.Set.copyOf(cellIds),
                new Badges(digest, generatedAt, null, singles, pairs));
    }

    private static Verdict verdict(boolean passed, String cellId, String failedStep, String reproduce) {
        return passed ? new Verdict(1, 0, null, null, null) : new Verdict(0, 1, cellId, failedStep, reproduce);
    }

    /** Both orders map to one key, because a pairing has no direction. */
    static String key(String left, String right) {
        return left.compareTo(right) <= 0 ? left + " & " + right : right + " & " + left;
    }

    /**
     * What the matrix says about one choice or one pairing.
     *
     * <p>Counts rather than a flag, because "red in one of forty-eight" and "red in all of them"
     * are different things to a person deciding, and a boolean would tell them the same story.
     */
    public record Verdict(int passed, int failed, String cell, String failedStep, String reproduce) {

        /**
         * The worse of two verdicts, with the failing cell kept.
         *
         * <p>Worse rather than newer: if any combination containing this pairing is red, the
         * pairing is worth warning about, and averaging it away is how a badge comes to mean
         * nothing.
         */
        public Verdict worse(Verdict other) {
            int allPassed = passed + other.passed;
            int allFailed = failed + other.failed;
            Verdict failing = failed > 0 ? this : other;
            return new Verdict(allPassed, allFailed, failing.cell, failing.failedStep, failing.reproduce);
        }

        public String status() {
            if (failed > 0) {
                return "failed";
            }
            return passed > 0 ? "passed" : "unverified";
        }
    }

    /** One entry as the wire carries it: what was verified, and what the matrix found. */
    public record Badge(String key, String status, int passed, int failed, String cell, String failedStep) {

        static Badge of(String key, Verdict verdict) {
            return new Badge(
                    key, verdict.status(), verdict.passed(), verdict.failed(), verdict.cell(), verdict.failedStep());
        }
    }

    /**
     * The document {@code GET /api/v1/verification} returns.
     *
     * @param catalogDigest the catalog these badges may be shown against — always the running one
     * @param generatedAt when the run that produced them finished, so a green badge carries
     *     evidence rather than a claim
     * @param staleFor the digest the available results actually belong to, when that is not the
     *     running catalog; null otherwise. Named rather than hidden: "no badges" and "badges for a
     *     catalog you are not using" are different situations and a client may want to say so.
     */
    public record Badges(
            String catalogDigest,
            String generatedAt,
            String staleFor,
            Map<String, Verdict> choiceVerdicts,
            Map<String, Verdict> pairVerdicts) {

        static Badges none(String catalogDigest) {
            return new Badges(catalogDigest, null, null, Map.of(), Map.of());
        }

        static Badges stale(String catalogDigest, String resultsFor, String generatedAt) {
            return new Badges(catalogDigest, generatedAt, resultsFor, Map.of(), Map.of());
        }

        /** Sorted, so two responses with the same entity tag really are the same bytes. */
        public List<Badge> choices() {
            return sorted(choiceVerdicts);
        }

        public List<Badge> pairs() {
            return sorted(pairVerdicts);
        }

        private static List<Badge> sorted(Map<String, Verdict> verdicts) {
            return new TreeMap<>(verdicts)
                    .entrySet().stream()
                            .map(entry -> Badge.of(entry.getKey(), entry.getValue()))
                            .toList();
        }

        /**
         * The entity tag: the catalog and the run, both.
         *
         * <p>The catalog alone would let a client keep yesterday's badges through today's nightly;
         * the run alone would let it keep badges across a catalog change. Neither half is enough on
         * its own, which is the whole reason this is not the metadata document's tag.
         */
        public String eTag() {
            return "\"%s/%s\""
                    .formatted(catalogDigest, Optional.ofNullable(generatedAt).orElse("none"));
        }
    }

    record Snapshot(String catalogDigest, String generatedAt, java.util.Set<String> cellIds, Badges badges) {}

    /**
     * Whether this run published that cell.
     *
     * <p>The gate on serving a log. A cell id arrives from a URL, and answering "is this one of the
     * cells the document names?" is both the right rule — a log belongs to the run that produced it
     * — and the reason a caller cannot turn an id into a path.
     */
    public boolean published(String cellId) {
        Snapshot current = current();
        return current != null && current.cellIds().contains(cellId);
    }
}
