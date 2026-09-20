package dev.kitbash.core.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable copies that keep their order.
 *
 * <p>{@code Map.of} and {@code Map.copyOf} are unordered <i>and</i> salted: their iteration order
 * varies between JVM runs by design, as a defence against hash-collision attacks. For a map that is
 * only ever looked up in, that is fine. For one whose contents get <b>serialised</b> — a compose
 * service definition, an {@code application.yml} fragment, a generation lock — it silently breaks
 * the property everything else rests on: two identical selections must produce byte-identical zips
 * (§4), or the cache key is wrong and the reproducibility guarantee is a claim rather than a fact.
 *
 * <p>The bug is invisible in a single JVM, which is what makes it worth a named helper: the golden
 * test that caught it only did so because two runs happened to disagree.
 */
public final class Ordered {

    private Ordered() {}

    public static <K, V> Map<K, V> copyOf(Map<K, V> source) {
        return source == null || source.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
