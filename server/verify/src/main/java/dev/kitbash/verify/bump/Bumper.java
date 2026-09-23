package dev.kitbash.verify.bump;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads what each recipe pins, asks what the latest release is, and rewrites the literals.
 *
 * <p>§12 is blunt about why this exists: <i>scaffolding rots by default; this is the only thing
 * that stops it, and it is what makes a six-month-old preset still produce a modern project.</i>
 *
 * <p>The replacement is deliberately dumb: every occurrence of the old literal, inside that one
 * recipe's directory, becomes the new one. That is what makes a Spring Boot bump land as a single
 * coherent change — {@code frameworkVersion}, the Gradle catalog entry and the Maven parent all
 * move together — instead of three edits that have to be kept in step by whoever remembers.
 *
 * <p>It is bounded three ways, because a find-and-replace loose in a repository is a bad day:
 * only inside the declaring recipe, only for a literal the manifest declared, and only when the
 * literal appears as a whole version rather than as part of a longer number.
 */
public final class Bumper {

    private final Path recipes;
    private final Releases releases;

    public Bumper(Path recipes, Releases releases) {
        this.recipes = recipes;
        this.releases = releases;
    }

    /** What each recipe says it pins, read from the manifests rather than inferred. */
    public List<TrackedVersion> tracked() {
        List<TrackedVersion> tracked = new ArrayList<>();
        try (Stream<Path> directories = Files.list(recipes)) {
            directories
                    .filter(Files::isDirectory)
                    .filter(directory -> Files.isRegularFile(directory.resolve("recipe.yaml")))
                    .sorted()
                    .forEach(directory -> tracked.addAll(
                            trackedIn(directory.getFileName().toString(), directory.resolve("recipe.yaml"))));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + recipes, e);
        }
        return List.copyOf(tracked);
    }

    /**
     * Parsed by hand rather than with a YAML library.
     *
     * <p>{@code verify} has no YAML dependency and this is four fields in a flat list; adding one
     * to read them would put a parser on the classpath of the module whose job is to run other
     * people's builds. The shape is fixed by the schema, so the reading can be.
     */
    private static List<TrackedVersion> trackedIn(String recipe, Path manifest) {
        List<TrackedVersion> tracked = new ArrayList<>();
        String version = null;
        String artifact = null;
        String label = null;
        String holdBelow = null;
        String because = null;
        boolean inside = false;

        for (String line : readLines(manifest)) {
            if (!line.startsWith(" ") && !line.startsWith("-")) {
                if (inside && version != null) {
                    tracked.add(new TrackedVersion(recipe, version, artifact, label, holdBelow, because));
                    version = null;
                }
                inside = line.startsWith("tracks:");
                continue;
            }
            if (!inside) {
                continue;
            }
            Matcher entry = Pattern.compile("^\\s*- version: \"([^\"]+)\"\\s*$").matcher(line);
            if (entry.matches()) {
                if (version != null) {
                    tracked.add(new TrackedVersion(recipe, version, artifact, label, holdBelow, because));
                }
                version = entry.group(1);
                artifact = null;
                label = null;
                holdBelow = null;
                because = null;
                continue;
            }
            Matcher field = Pattern.compile("^\\s*(artifact|label|holdBelow|because): (.+?)\\s*$")
                    .matcher(line);
            if (field.matches()) {
                String value = field.group(2).replaceAll("^[\"']|[\"']$", "");
                switch (field.group(1)) {
                    case "artifact" -> artifact = value;
                    case "label" -> label = value;
                    case "holdBelow" -> holdBelow = value;
                    default -> because = value;
                }
            }
        }
        if (inside && version != null) {
            tracked.add(new TrackedVersion(recipe, version, artifact, label, holdBelow, because));
        }
        return tracked;
    }

    /**
     * The bumps available, without writing anything.
     *
     * <p>A lookup that fails is reported and skipped rather than fatal: one artifact moving host
     * should not stop five other recipes being updated, which is §36's "bumps are applied per
     * recipe so a single incompatible library does not block every other update".
     */
    public Result plan(List<String> problems) {
        Map<TrackedVersion, String> available = new LinkedHashMap<>();
        Map<TrackedVersion, String> majors = new LinkedHashMap<>();
        Map<TrackedVersion, String> held = new LinkedHashMap<>();
        for (TrackedVersion tracked : tracked()) {
            try {
                List<String> published = releases.of(tracked);
                if (Versions.releasesNewestFirst(published).isEmpty()) {
                    problems.add(tracked.artifact() + " publishes no releases, only pre-releases");
                    continue;
                }
                // The ceiling filters the candidates rather than the answer. A hold means "not this
                // version or above", not "no upgrade at all" — so a project held below 1.6 still
                // gets 1.5.9, which is the difference between a ceiling and a freeze.
                String blocked = Versions.latestWithinMajor(tracked.version(), published);
                List<String> allowed = published.stream()
                        .filter(candidate -> !tracked.isHeld(candidate))
                        .toList();
                String within = Versions.latestWithinMajor(tracked.version(), allowed);
                if (within != null) {
                    available.put(tracked, within);
                }
                if (blocked != null && tracked.isHeld(blocked)) {
                    held.put(tracked, blocked);
                }
                String major = Versions.majorUpgrade(tracked.version(), published);
                if (major != null) {
                    majors.put(tracked, major);
                }
            } catch (IOException e) {
                problems.add(tracked.artifact() + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                problems.add(tracked.artifact() + ": interrupted");
            }
        }
        return new Result(available, majors, held);
    }

    /** Applies one bump, returning how many files it touched, or zero if the literal was gone. */
    public int apply(TrackedVersion tracked, String to) {
        Path directory = recipes.resolve(tracked.recipe());
        int touched = 0;
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String text;
                try {
                    text = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException notText) {
                    continue;
                }
                // Bounded by a word boundary on both sides, so bumping 1.4.1 does not corrupt
                // 11.4.10 — the sort of thing that would pass review because the diff looks fine.
                String replaced = text.replaceAll(
                        "(?<![\\w.])" + Pattern.quote(tracked.version()) + "(?![\\w.])", Matcher.quoteReplacement(to));
                if (!replaced.equals(text)) {
                    Files.writeString(file, replaced, StandardCharsets.UTF_8);
                    touched++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not rewrite " + directory, e);
        }
        return touched;
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }

    /**
     * What is available, before anything is written.
     *
     * @param available upgrades within the current major, which the job proposes
     * @param majors upgrades that cross a major, which it reports and leaves alone
     * @param held upgrades a manifest declares a ceiling against, reported with the reason
     */
    public record Result(
            Map<TrackedVersion, String> available,
            Map<TrackedVersion, String> majors,
            Map<TrackedVersion, String> held) {

        public boolean isEmpty() {
            return available.isEmpty();
        }
    }
}
