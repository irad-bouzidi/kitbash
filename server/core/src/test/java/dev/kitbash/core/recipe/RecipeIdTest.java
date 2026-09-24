package dev.kitbash.core.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The namespace is a security control, so it is tested as one (kitbash-47, threat model §3.6, §6.3).
 *
 * <p>The threat is not exotic. A contributed recipe is rendered in the wizard beside shipped ones,
 * in the same control, with the same styling — so {@code backend-spring-java-v2} costs an attacker
 * nothing and buys them a stack somebody picks by mistake. §6.3 answers it by making provenance
 * part of the identity rather than a flag beside it, and these tests are about the ways that could
 * quietly stop being true.
 */
class RecipeIdTest {

    @Nested
    @DisplayName("a shipped recipe")
    class Shipped {

        @ParameterizedTest
        @ValueSource(strings = {"core", "backend-spring-java", "db-postgres-flyway", "a1", "a-1-b"})
        @DisplayName("keeps the ids the catalog already uses")
        void stillParses(String id) {
            assertThat(RecipeId.of(id).contributed()).isFalse();
            assertThat(RecipeId.of(id).namespace()).isEmpty();
            assertThat(RecipeId.of(id).name()).isEqualTo(id);
        }
    }

    @Nested
    @DisplayName("a contributed recipe")
    class Contributed {

        @Test
        @DisplayName("carries its namespace in the id, not beside it")
        void carriesItsOrigin() {
            RecipeId id = RecipeId.of("@platform/audit-log");

            assertThat(id.contributed()).isTrue();
            assertThat(id.namespace()).contains("platform");
            assertThat(id.name()).isEqualTo("audit-log");
            // The one that matters: anything that writes the id writes the origin too. A log line,
            // a lock entry and a metric label all go through this method and none of them can
            // reach for the name without also reaching for the '@'.
            assertThat(id.toString()).isEqualTo("@platform/audit-log");
        }

        @Test
        @DisplayName("sorts apart from shipped recipes, so the two never interleave in a listing")
        void sortsApart() {
            List<RecipeId> ids = new java.util.ArrayList<>(List.of(
                    RecipeId.of("backend-spring-java"),
                    RecipeId.of("@platform/audit-log"),
                    RecipeId.of("core"),
                    RecipeId.of("@acme/widgets")));

            java.util.Collections.sort(ids);

            // '@' is below every letter in ASCII, so contributed ids group at the front rather
            // than scattering through the shipped ones. That is the property a reviewer scanning
            // a lock file relies on, and it is worth an assertion because it is an accident of
            // the character chosen — a different prefix would not have it.
            assertThat(ids)
                    .extracting(RecipeId::value)
                    .containsExactly("@acme/widgets", "@platform/audit-log", "backend-spring-java", "core");
        }

        @Test
        @DisplayName("is built from its parts without string assembly at the call site")
        void isBuiltFromParts() {
            assertThat(RecipeId.contributed("platform", "audit-log")).isEqualTo(RecipeId.of("@platform/audit-log"));
        }
    }

    @Nested
    @DisplayName("what an id may not be")
    class Refusals {

        /**
         * Each of these is a way to make a contributed recipe look shipped, or to smuggle a
         * character into something that later becomes a path, a log line or a JSON key.
         */
        @ParameterizedTest
        @ValueSource(
                strings = {
                    "@platform", // a namespace with no recipe
                    "@/audit-log", // an empty namespace
                    "@platform/", // an empty name
                    "@platform/sub/audit-log", // a forged second level
                    "@@platform/audit-log",
                    "@platform//audit-log",
                    "@platform/../audit-log", // the path-traversal shape, refused by the grammar
                    "@platform/audit log",
                    "@Platform/audit-log",
                    "@platform/Audit-Log",
                    "platform/audit-log", // namespaced without the marker
                    "-leading-hyphen",
                    "trailing-hyphen-",
                    "double--hyphen",
                    "1-starts-with-a-digit",
                    "has_underscore",
                    ""
                })
        @DisplayName("is refused by name rather than normalised into something legal")
        void refuses(String id) {
            // Refused rather than sanitised, per §13: silent rewriting produces surprising output,
            // and here it would produce a *different recipe's* id.
            assertThatThrownBy(() -> RecipeId.of(id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(id);
        }

        @Test
        @DisplayName("a namespaced id cannot be mistaken for a directory name")
        void cannotBeADirectoryName() {
            // CatalogLoader compares a shipped recipe's id to its directory name. A '/' in an id
            // can therefore never match one, which is what keeps a contributed recipe out of the
            // git half of the catalog — and the loader now says that in those words rather than
            // suggesting a rename.
            assertThat(RecipeId.of("@platform/audit-log").value()).contains("/");
        }
    }
}
