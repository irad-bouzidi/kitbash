package dev.kitbash.render;

import dev.kitbash.core.hash.Sha256;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Every template the engine is allowed to load, fixed at construction.
 *
 * <p>This is the §13 "no include from user-controlled paths" rule, expressed as a type rather than
 * as a check somebody has to remember: the loader resolves names against this map and nothing else,
 * so there is no filesystem for a template to reach into and {@code {% include "/etc/passwd" %}}
 * fails because that name is not in the registry — not because a path check happened to catch it.
 *
 * <p>The set of templates is known at boot, because recipes are loaded and frozen at boot (§10).
 * That is what lets the engine be built once and immutably (§9) instead of per request, and what
 * makes a content-addressed template cache safe: the key carries the template's own hash, so a
 * stale cached template cannot outlive an edit to it.
 */
public final class TemplateRegistry {

    private final Map<String, String> sources;
    private final Map<String, String> cacheKeys;

    private TemplateRegistry(Map<String, String> sources) {
        this.sources = Map.copyOf(sources);
        Map<String, String> keys = new LinkedHashMap<>();
        this.sources.forEach((name, source) -> keys.put(name, name + "@" + Sha256.ofUtf8(source)));
        this.cacheKeys = Map.copyOf(keys);
    }

    public static TemplateRegistry of(Map<String, String> sources) {
        return new TemplateRegistry(sources);
    }

    public static TemplateRegistry empty() {
        return new TemplateRegistry(Map.of());
    }

    /** This registry plus one more template — how a single ad-hoc render (a path) is served. */
    TemplateRegistry with(String name, String source) {
        Map<String, String> merged = new LinkedHashMap<>(sources);
        merged.put(name, source);
        return new TemplateRegistry(merged);
    }

    Optional<String> source(String name) {
        return Optional.ofNullable(sources.get(name));
    }

    boolean contains(String name) {
        return sources.containsKey(name);
    }

    /** Name plus content hash: a template edit changes the key, so nothing stale is ever served. */
    String cacheKey(String name) {
        return cacheKeys.getOrDefault(name, name);
    }

    public int size() {
        return sources.size();
    }
}
