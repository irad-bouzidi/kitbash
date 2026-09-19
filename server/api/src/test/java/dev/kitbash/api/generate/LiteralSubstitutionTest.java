package dev.kitbash.api.generate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LiteralSubstitutionTest {

    @Test
    @DisplayName("a rule's own output is never re-matched by a later rule")
    void outputIsNotReprocessed() {
        // The bug this guards: com.example → com.demo, then demo → my-service, giving
        // com.my-service. A sequence of String.replace calls does exactly that.
        String result = new LiteralSubstitution()
                .rule("com.example", "com.demo")
                .rule("demo", "my-service")
                .apply("package com.example;");

        assertThat(result).isEqualTo("package com.demo;");
    }

    @Test
    @DisplayName("rules are tried in order, so the longest literal registered first wins")
    void longestRuleFirstWins() {
        LiteralSubstitution substitution = new LiteralSubstitution()
                .rule("com.example.demo", "com.acme.customer")
                .rule("com.example", "com.acme");

        assertThat(substitution.apply("com.example.demo.Widget")).isEqualTo("com.acme.customer.Widget");
        assertThat(substitution.apply("com.example.Other")).isEqualTo("com.acme.Other");
    }

    @Test
    @DisplayName("text with no match is returned unchanged")
    void leavesUnmatchedTextAlone() {
        assertThat(new LiteralSubstitution().rule("x", "y").apply("nothing here"))
                .isEqualTo("nothing here");
    }

    @Test
    @DisplayName("every occurrence is replaced, not just the first")
    void replacesEveryOccurrence() {
        assertThat(new LiteralSubstitution().rule("a", "b").apply("aaa")).isEqualTo("bbb");
    }
}
