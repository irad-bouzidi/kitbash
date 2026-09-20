package dev.kitbash.core.recipe;

import java.util.regex.Pattern;

/**
 * The three patterns {@link WhenExpression} parses with, kept off the interface so they do not
 * become part of its public surface — everything declared in an interface is public, and a regex is
 * an implementation detail of a grammar, not part of it.
 */
final class WhenGrammar {

    static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z][a-zA-Z0-9]*");
    static final Pattern COMPARISON = Pattern.compile("([a-zA-Z][a-zA-Z0-9]*)\\s*(==|!=)\\s*'([^']*)'");
    static final Pattern CAPABILITY = Pattern.compile("capability\\(\\s*'([^']*)'\\s*\\)");

    private WhenGrammar() {}
}
