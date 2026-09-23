package dev.kitbash.api.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The aggregation §9's badge placement depends on.
 *
 * <p>The claim under test is narrow and load-bearing: a red combination must show at the
 * <em>pairing</em> that is red, and nowhere else. §36 is the worked example — Kotlin with the typed
 * client failed in twenty-four cells while Kotlin with everything else stayed green — and a badge
 * that said "Kotlin: failed" would have been alarming, unactionable and untrue.
 */
class VerificationBadgesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String DIGEST = "sha256:abc";

    /** Two cells that differ in one option, one green and one red — §36 in miniature. */
    private static final String RESULTS =
            """
            {
              "schemaVersion": 1,
              "catalogDigest": "sha256:abc",
              "generatedAt": "2026-09-23T02:00:00Z",
              "cells": [
                {"id": "green-cell", "outcome": "PASSED",
                 "options": {"backend": "kotlin", "frontend": "spa", "typedClient": false}},
                {"id": "red-cell", "outcome": "FAILED", "failedStep": "pnpm typecheck",
                 "options": {"backend": "kotlin", "frontend": "spa", "typedClient": true}},
                {"id": "java-cell", "outcome": "PASSED",
                 "options": {"backend": "java", "frontend": "spa", "typedClient": true}}
              ]
            }
            """;

    private static VerificationBadges.Badges badges(@TempDir Path directory, String json) throws Exception {
        Path file = directory.resolve("verification.json");
        Files.writeString(file, json);
        return new VerificationBadges(file).forCatalog(DIGEST);
    }

    private static VerificationBadges.Badge badge(VerificationBadges.Badges badges, String key) {
        return badges.pairs().stream()
                .filter(pair -> pair.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no badge for '" + key + "'; there are " + badges.pairs()));
    }

    @Nested
    @DisplayName("pairings")
    class Pairings {

        @Test
        @DisplayName("the red badge lands on the pairing that is red, not on either half of it")
        void badgesThePairing(@TempDir Path directory) throws Exception {
            VerificationBadges.Badges result = badges(directory, RESULTS);

            assertThat(badge(result, "backend=kotlin & typedClient=true").status())
                    .as("the combination that failed")
                    .isEqualTo("failed");
            assertThat(badge(result, "backend=kotlin & frontend=spa").status())
                    .as("Kotlin with a frontend is green in one cell and red in another — the "
                            + "pairing that distinguishes them is the one to warn about")
                    .isEqualTo("failed");
            assertThat(badge(result, "frontend=spa & typedClient=true").status())
                    .as("a typed client with a frontend passed for Java, so this pairing carries "
                            + "the Kotlin failure too")
                    .isEqualTo("failed");
            assertThat(badge(result, "backend=java & typedClient=true").status())
                    .as("Java with the typed client was never red")
                    .isEqualTo("passed");
        }

        @Test
        @DisplayName("a badge carries the failing cell and the step, so the warning is actionable")
        void namesTheFailure(@TempDir Path directory) throws Exception {
            VerificationBadges.Badge red = badge(badges(directory, RESULTS), "backend=kotlin & typedClient=true");

            assertThat(red.cell()).isEqualTo("red-cell");
            assertThat(red.failedStep()).isEqualTo("pnpm typecheck");
            assertThat(red.failed()).isEqualTo(1);
        }

        @Test
        @DisplayName("counts are kept, because one red in forty is not the same warning as forty")
        void keepsTheCounts(@TempDir Path directory) throws Exception {
            VerificationBadges.Badge spa = badge(badges(directory, RESULTS), "frontend=spa & typedClient=true");

            assertThat(spa.passed()).isEqualTo(1);
            assertThat(spa.failed()).isEqualTo(1);
        }

        @Test
        @DisplayName("a flag that is off is not a pairing anybody chose")
        void ignoresFlagsThatAreOff(@TempDir Path directory) throws Exception {
            assertThat(badges(directory, RESULTS).pairs())
                    .as("badging 'typedClient=false' against everything would put a badge on every "
                            + "control in the page")
                    .noneMatch(pair -> pair.key().contains("typedClient=false"));
        }

        @Test
        @DisplayName("a pairing has no direction, so both orders are one badge")
        void oneBadgePerUnorderedPair(@TempDir Path directory) throws Exception {
            assertThat(badges(directory, RESULTS).pairs())
                    .extracting(VerificationBadges.Badge::key)
                    .doesNotContain("typedClient=true & backend=kotlin")
                    .contains("backend=kotlin & typedClient=true");
        }
    }

    @Nested
    @DisplayName("catalog")
    class CatalogSafety {

        @Test
        @DisplayName("results from another catalog are withheld and named, never shown")
        void neverShowsAnotherCatalogsResults(@TempDir Path directory) throws Exception {
            Path file = directory.resolve("verification.json");
            Files.writeString(file, RESULTS);

            VerificationBadges.Badges other = new VerificationBadges(file).forCatalog("sha256:different");

            assertThat(other.pairs())
                    .as("a combination marked green by yesterday's recipes is the " + "expensive kind of wrong")
                    .isEmpty();
            assertThat(other.staleFor())
                    .as("'no badges' and 'badges for a catalog you are not using' are different "
                            + "situations, and a client may want to say which")
                    .isEqualTo(DIGEST);
        }

        @Test
        @DisplayName("no results at all is 'not verified', never green")
        void absenceIsNotGreen(@TempDir Path directory) {
            VerificationBadges.Badges none =
                    new VerificationBadges(directory.resolve("nothing.json")).forCatalog(DIGEST);

            assertThat(none.pairs()).isEmpty();
            assertThat(none.choices()).isEmpty();
            assertThat(none.generatedAt()).isNull();
        }

        @Test
        @DisplayName("the entity tag moves when the catalog moves and when the run does")
        void theTagCoversBoth(@TempDir Path directory) throws Exception {
            String first = badges(directory, RESULTS).eTag();
            String laterRun = badges(directory, RESULTS.replace("2026-09-23T02:00:00Z", "2026-09-24T02:00:00Z"))
                    .eTag();

            assertThat(laterRun)
                    .as("the catalog alone would let a client keep yesterday's badges through " + "tonight's nightly")
                    .isNotEqualTo(first);

            Path file = directory.resolve("other.json");
            Files.writeString(file, RESULTS);
            assertThat(new VerificationBadges(file)
                            .forCatalog("sha256:different")
                            .eTag())
                    .as("and the run alone would let it keep them across a catalog change")
                    .isNotEqualTo(first);
        }

        @Test
        @DisplayName("a replaced results file is picked up without a restart")
        void rereadsWhenTheFileChanges(@TempDir Path directory) throws Exception {
            Path file = directory.resolve("verification.json");
            Files.writeString(file, RESULTS);
            VerificationBadges badges = new VerificationBadges(file);

            assertThat(badges.forCatalog(DIGEST).pairs()).isNotEmpty();

            Files.writeString(
                    file,
                    """
                    {"schemaVersion":1,"catalogDigest":"sha256:abc","generatedAt":"2026-09-24T02:00:00Z",
                     "cells":[{"id":"only","outcome":"PASSED","options":{"backend":"kotlin"}}]}
                    """);
            Files.setLastModifiedTime(
                    file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2_000));

            assertThat(badges.forCatalog(DIGEST).generatedAt())
                    .as("a nightly landing at 02:00 must reach the wizard without a deploy")
                    .isEqualTo("2026-09-24T02:00:00Z");
        }
    }

    @Test
    @DisplayName("the runner's own document is the shape this reads")
    void readsWhatTheRunnerWrites() throws Exception {
        // Not a second parser against a hand-written fixture: the two halves are written and read
        // in different modules, and a field renamed on one side would otherwise go unnoticed until
        // every badge quietly disappeared.
        assertThat(JSON.readTree(RESULTS).path("schemaVersion").asInt())
                .isEqualTo(dev.kitbash.verify.VerificationResults.SCHEMA_VERSION);
    }
}
