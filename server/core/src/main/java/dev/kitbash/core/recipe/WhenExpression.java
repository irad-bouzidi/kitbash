package dev.kitbash.core.recipe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * The whole conditional surface of the manifest format (§4, §7).
 *
 * <p>It is this small on purpose. §7 calls {@code when} "the surface most likely to grow accidental
 * features", and a manifest language that can express anything is a manifest language that has to
 * be sandboxed, versioned and debugged. The grammar is:
 *
 * <pre>
 *   expression := term (('&amp;&amp;' | '||') term)*     // one operator per expression, no mixing
 *   term       := 'always'
 *               | option                            // true when the option reads as true
 *               | '!' term
 *               | option '==' 'literal'
 *               | option '!=' 'literal'
 *               | "capability('name')"
 * </pre>
 *
 * <p>No parentheses, no arithmetic, no function calls beyond {@code capability(…)}. Mixing {@code
 * &amp;&amp;} and {@code ||} in one expression is rejected rather than given a precedence rule
 * nobody would remember — split it into two rules instead.
 *
 * <p>{@code capability(…)} exists because the standalone-frontend case (§18) genuinely needs it: a
 * recipe has to be able to say "wire this up only when something provides a REST API" without
 * naming the recipe that does. That is the capability model doing its job, not an escape hatch.
 */
public sealed interface WhenExpression {

    boolean evaluate(WhenContext context);

    /** Option ids this expression reads. The loader checks every one is declared (§7). */
    default Set<String> referencedOptions() {
        Set<String> ids = new LinkedHashSet<>();
        collectOptions(ids);
        return ids;
    }

    default Set<String> referencedCapabilities() {
        Set<String> names = new LinkedHashSet<>();
        collectCapabilities(names);
        return names;
    }

    void collectOptions(Set<String> into);

    void collectCapabilities(Set<String> into);

    record Always() implements WhenExpression {
        @Override
        public boolean evaluate(WhenContext context) {
            return true;
        }

        @Override
        public void collectOptions(Set<String> into) {}

        @Override
        public void collectCapabilities(Set<String> into) {}
    }

    record Truthy(String optionId, boolean negated) implements WhenExpression {
        @Override
        public boolean evaluate(WhenContext context) {
            return context.truthy(optionId) != negated;
        }

        @Override
        public void collectOptions(Set<String> into) {
            into.add(optionId);
        }

        @Override
        public void collectCapabilities(Set<String> into) {}
    }

    record Equals(String optionId, String literal, boolean negated) implements WhenExpression {
        @Override
        public boolean evaluate(WhenContext context) {
            return Objects.equals(context.scalar(optionId), literal) != negated;
        }

        @Override
        public void collectOptions(Set<String> into) {
            into.add(optionId);
        }

        @Override
        public void collectCapabilities(Set<String> into) {}
    }

    /** {@code !term} — the one place the grammar composes rather than enumerates. */
    record Not(WhenExpression term) implements WhenExpression {
        @Override
        public boolean evaluate(WhenContext context) {
            return !term.evaluate(context);
        }

        @Override
        public void collectOptions(Set<String> into) {
            term.collectOptions(into);
        }

        @Override
        public void collectCapabilities(Set<String> into) {
            term.collectCapabilities(into);
        }
    }

    record HasCapability(String capability) implements WhenExpression {
        @Override
        public boolean evaluate(WhenContext context) {
            return context.capabilities().contains(Capability.of(capability));
        }

        @Override
        public void collectOptions(Set<String> into) {}

        @Override
        public void collectCapabilities(Set<String> into) {
            into.add(capability);
        }
    }

    record All(List<WhenExpression> terms) implements WhenExpression {
        public All {
            terms = List.copyOf(terms);
        }

        @Override
        public boolean evaluate(WhenContext context) {
            return terms.stream().allMatch(term -> term.evaluate(context));
        }

        @Override
        public void collectOptions(Set<String> into) {
            terms.forEach(term -> term.collectOptions(into));
        }

        @Override
        public void collectCapabilities(Set<String> into) {
            terms.forEach(term -> term.collectCapabilities(into));
        }
    }

    record Any(List<WhenExpression> terms) implements WhenExpression {
        public Any {
            terms = List.copyOf(terms);
        }

        @Override
        public boolean evaluate(WhenContext context) {
            return terms.stream().anyMatch(term -> term.evaluate(context));
        }

        @Override
        public void collectOptions(Set<String> into) {
            terms.forEach(term -> term.collectOptions(into));
        }

        @Override
        public void collectCapabilities(Set<String> into) {
            terms.forEach(term -> term.collectCapabilities(into));
        }
    }

    static WhenExpression parse(String source) {
        String expression = source == null || source.isBlank() ? FileRule.ALWAYS : source.trim();
        boolean hasAnd = expression.contains("&&");
        boolean hasOr = expression.contains("||");
        if (hasAnd && hasOr) {
            throw new IllegalArgumentException("when expression mixes '&&' and '||' ('" + source
                    + "'); split it into two rules rather than relying on a precedence rule");
        }
        String separator = hasOr ? "\\|\\|" : "&&";
        List<WhenExpression> terms = new ArrayList<>();
        for (String part : expression.split(separator)) {
            terms.add(parseTerm(part.trim(), source));
        }
        if (terms.size() == 1) {
            return terms.get(0);
        }
        return hasOr ? new Any(terms) : new All(terms);
    }

    private static WhenExpression parseTerm(String term, String source) {
        if (term.isEmpty()) {
            throw new IllegalArgumentException("when expression has an empty term ('" + source + "')");
        }
        if (FileRule.ALWAYS.equals(term)) {
            return new Always();
        }
        Matcher capability = WhenGrammar.CAPABILITY.matcher(term);
        if (capability.matches()) {
            return new HasCapability(capability.group(1));
        }
        Matcher comparison = WhenGrammar.COMPARISON.matcher(term);
        if (comparison.matches()) {
            return new Equals(comparison.group(1), comparison.group(3), "!=".equals(comparison.group(2)));
        }
        boolean negated = term.startsWith("!");
        String identifier = negated ? term.substring(1).trim() : term;
        if (WhenGrammar.IDENTIFIER.matcher(identifier).matches()) {
            return new Truthy(identifier, negated);
        }
        if (negated) {
            // `!capability('docker')` is the case that forced this: a recipe has to be able to
            // describe the stack it is part of both when the container recipe is selected and when
            // it is not, and enumerating a negated form of every term would double the grammar.
            return new Not(parseTerm(identifier, source));
        }
        throw new IllegalArgumentException("cannot parse when expression term '" + term + "' (in '" + source
                + "'). Supported forms: always, option, !option, option == 'value', "
                + "option != 'value', capability('name')");
    }
}
