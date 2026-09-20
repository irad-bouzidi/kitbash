package dev.kitbash.api.history;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * What changed between two locks (§7, §24).
 *
 * <p>§7 calls this the debugging tool, and the claim is worth taking literally: when somebody says
 * <i>this used to work</i>, the difference between the generation that worked and the one that did
 * not is usually one line of this. The lock is small, so the diff is readable rather than a wall.
 *
 * @param changed recipes in both, at different versions — the interesting case
 * @param added recipes only the second has
 * @param removed recipes only the first has
 */
public record LockDiff(List<VersionChange> changed, List<VersionChange> added, List<VersionChange> removed) {

    /** One recipe, and what its version was on each side. Null means "not in that lock". */
    public record VersionChange(String recipeId, String before, String after) {}

    public boolean isEmpty() {
        return changed.isEmpty() && added.isEmpty() && removed.isEmpty();
    }

    /** A one-line summary, for a log line or a header where the whole diff would not fit. */
    public String summary() {
        if (isEmpty()) {
            return "no change";
        }
        List<String> parts = new ArrayList<>();
        changed.forEach(change -> parts.add("%s %s→%s".formatted(change.recipeId(), change.before(), change.after())));
        added.forEach(change -> parts.add("+%s %s".formatted(change.recipeId(), change.after())));
        removed.forEach(change -> parts.add("-%s %s".formatted(change.recipeId(), change.before())));
        return String.join(", ", parts);
    }

    /**
     * Compares two locks, each as recipe id to version.
     *
     * <p>Sorted by recipe id, because the two things people do with a diff are read it and paste
     * it into a message, and both want the same order every time.
     */
    public static LockDiff between(Map<String, String> before, Map<String, String> after) {
        List<VersionChange> changed = new ArrayList<>();
        List<VersionChange> added = new ArrayList<>();
        List<VersionChange> removed = new ArrayList<>();

        for (String recipeId : new TreeSet<>(union(before, after))) {
            String was = before.get(recipeId);
            String now = after.get(recipeId);
            if (was == null) {
                added.add(new VersionChange(recipeId, null, now));
            } else if (now == null) {
                removed.add(new VersionChange(recipeId, was, null));
            } else if (!was.equals(now)) {
                changed.add(new VersionChange(recipeId, was, now));
            }
        }
        return new LockDiff(List.copyOf(changed), List.copyOf(added), List.copyOf(removed));
    }

    private static java.util.Set<String> union(Map<String, String> before, Map<String, String> after) {
        java.util.Set<String> all = new java.util.LinkedHashSet<>(before.keySet());
        all.addAll(after.keySet());
        return all;
    }
}
