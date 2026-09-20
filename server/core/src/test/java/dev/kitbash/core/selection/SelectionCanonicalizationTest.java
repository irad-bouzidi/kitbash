package dev.kitbash.core.selection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hash that three subsystems key on (§7, §10, §12), pinned by test.
 *
 * <p>A canonicalisation that is merely "usually stable" is worse than none: the zip cache would
 * return the right project most of the time, and the verification dedupe would occasionally spend a
 * container re-proving something already green. These assertions are what make it exactly stable.
 */
class SelectionCanonicalizationTest {

    @Test
    @DisplayName("two selections differing only in key order hash identically")
    void keyOrderDoesNotReachTheHash() {
        Map<String, OptionValue> oneOrder = new LinkedHashMap<>();
        oneOrder.put("backend", OptionValue.text("backend-spring-java"));
        oneOrder.put("architecture", OptionValue.text("layered"));
        oneOrder.put("docker", OptionValue.flag(true));

        Map<String, OptionValue> otherOrder = new LinkedHashMap<>();
        otherOrder.put("docker", OptionValue.flag(true));
        otherOrder.put("backend", OptionValue.text("backend-spring-java"));
        otherOrder.put("architecture", OptionValue.text("layered"));

        Map<String, String> variablesOneWay = new LinkedHashMap<>();
        variablesOneWay.put("groupId", "com.acme");
        variablesOneWay.put("packageName", "com.acme.customer");

        Map<String, String> variablesOtherWay = new LinkedHashMap<>();
        variablesOtherWay.put("packageName", "com.acme.customer");
        variablesOtherWay.put("groupId", "com.acme");

        Selection first = new Selection("customer-management", oneOrder, variablesOneWay);
        Selection second = new Selection("customer-management", otherOrder, variablesOtherWay);

        assertThat(first.canonicalJson()).isEqualTo(second.canonicalJson());
        assertThat(first.hash()).isEqualTo(second.hash());
    }

    @Test
    @DisplayName("the canonical form round-trips through the envelope unchanged")
    void roundTripsThroughTheEnvelope() {
        Selection original = new Selection(
                "customer-management",
                Map.of("backend", OptionValue.text("backend-spring-java"), "docker", OptionValue.flag(true)),
                Map.of("groupId", "com.acme"));

        Selection roundTripped = original.toEnvelope().parse();

        assertThat(roundTripped.canonicalJson()).isEqualTo(original.canonicalJson());
        assertThat(roundTripped.hash()).isEqualTo(original.hash());
    }

    /**
     * The same round trip, through JSON — which is the one that matters.
     *
     * <p>In memory the parser accepts either an {@link OptionValue} or its wire form, so the test
     * above passed while {@code toEnvelope} was handing back domain objects wearing an envelope's
     * name. Serialised, a {@code Text} record writes itself as {@code {"value":"…"}} and comes
     * back as a map the parser refuses — which is what kitbash-24 found the moment a selection was
     * stored in a row and read out again.
     */
    @Test
    @DisplayName("and through JSON, because that is what storing or sending one does to it")
    void roundTripsThroughJson() throws Exception {
        Selection original = new Selection(
                "customer-management",
                Map.of(
                        "backend",
                        OptionValue.text("backend-spring-java"),
                        "docker",
                        OptionValue.flag(true),
                        "features",
                        OptionValue.multi(List.of("auth", "metrics"))),
                Map.of("groupId", "com.acme"));

        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        String wire = json.writeValueAsString(original.toEnvelope());

        assertThat(wire)
                .as("the envelope has to be the wire shape, not the domain one")
                .contains("\"backend\":\"backend-spring-java\"")
                .contains("\"docker\":true")
                .doesNotContain("\"value\"");

        Selection roundTripped = json.readValue(wire, SelectionEnvelope.class).parse();
        assertThat(roundTripped.hash()).isEqualTo(original.hash());
    }

    @Test
    @DisplayName("the hash is pinned, so a change to the canonical form cannot slip through")
    void hashIsPinned() {
        // Hard-coded on purpose. If this value changes, every cached zip and every verification
        // run keyed on the old hash is orphaned (§10, §12) — that is a deliberate migration, not
        // an incidental refactor, and this assertion is what forces the conversation.
        Selection selection = new Selection(
                "customer-management",
                Map.of("backend", OptionValue.text("backend-spring-java")),
                Map.of("groupId", "com.acme"));

        assertThat(selection.canonicalJson())
                .isEqualTo("{\"projectName\":\"customer-management\",\"options\":"
                        + "{\"backend\":\"backend-spring-java\"},\"variables\":{\"groupId\":\"com.acme\"}}");
        assertThat(selection.hash()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("blank variables are elided, so 'unset' and 'set to empty' are the same selection")
    void blankVariablesAreElided() {
        Selection withBlank = new Selection("svc", Map.of(), Map.of("groupId", "com.acme", "packageName", "   "));
        Selection without = new Selection("svc", Map.of(), Map.of("groupId", "com.acme"));

        assertThat(withBlank.hash()).isEqualTo(without.hash());
    }

    @Test
    @DisplayName("options left at their catalog default drop out of the hash (§7)")
    void defaultsAreElided() {
        Map<String, OptionValue> defaults =
                Map.of("architecture", OptionValue.text("layered"), "docker", OptionValue.flag(true));

        Selection clickedEverything = new Selection(
                "svc",
                Map.of(
                        "architecture", OptionValue.text("layered"),
                        "docker", OptionValue.flag(true),
                        "backend", OptionValue.text("backend-spring-java")),
                Map.of());
        Selection acceptedDefaults =
                new Selection("svc", Map.of("backend", OptionValue.text("backend-spring-java")), Map.of());

        assertThat(clickedEverything.withDefaultsElided(defaults).hash())
                .isEqualTo(acceptedDefaults.withDefaultsElided(defaults).hash());
    }

    @Test
    @DisplayName("a multi-select hashes by its contents, not by the order they were ticked")
    void multiSelectIsOrderIndependent() {
        Selection first =
                new Selection("svc", Map.of("features", OptionValue.multi(List.of("auth", "otel"))), Map.of());
        Selection second =
                new Selection("svc", Map.of("features", OptionValue.multi(List.of("otel", "auth", "otel"))), Map.of());

        assertThat(first.hash()).isEqualTo(second.hash());
    }

    @Test
    @DisplayName("JSON special characters are escaped rather than corrupting the canonical form")
    void escapesJsonSpecials() {
        Selection selection = new Selection("svc", Map.of("note", OptionValue.text("a\"b\\c\nd")), Map.of());

        assertThat(selection.canonicalJson()).contains("\"note\":\"a\\\"b\\\\c\\nd\"");
    }

    @Test
    @DisplayName("the hash survives a JVM restart, because nothing in it depends on identity hashing")
    void hashIsStableAcrossRuns() {
        // The realistic failure is a HashMap iteration order reaching the output, which varies with
        // the JVM's hash seed. Recomputing over freshly built maps in a different insertion order
        // is the cheap in-process proxy for that; GenerationDeterminismTest forks a second JVM.
        String first =
                new Selection("svc", Map.of("a", OptionValue.text("1"), "b", OptionValue.text("2")), Map.of()).hash();
        Map<String, OptionValue> reversed = new LinkedHashMap<>();
        reversed.put("b", OptionValue.text("2"));
        reversed.put("a", OptionValue.text("1"));

        assertThat(new Selection("svc", reversed, Map.of()).hash()).isEqualTo(first);
    }
}
