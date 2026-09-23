package dev.kitbash.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * What this binary is, and which catalog it carries (§8, §42).
 *
 * <h2>A catalog is part of the binary's identity</h2>
 *
 * <p>§42: <i>versioned alongside the catalog digest it embeds — a CLI carries its catalog, so which
 * catalog it carries is part of its identity.</i> That is the whole reason {@code --version} prints
 * two things. A released binary generates from the recipes it shipped with, so two installs of
 * "kitbash 0.1.0" that were built from different commits produce different projects, and the only
 * honest way to tell them apart is the digest.
 *
 * <p>It follows that a stale binary emits a stale catalog. §42 asks that this be <b>visible rather
 * than surprising</b>, which is why the digest is in {@code --version}, in the generated project's
 * README, and in every error envelope the CLI prints.
 *
 * <h2>Where the catalog comes from</h2>
 *
 * <p>A distribution ships {@code recipes/} beside its {@code lib/}, and the launcher points
 * {@code KITBASH_HOME} at the root. Inside the repository there is no such directory and the
 * upward search finds the working tree's, which is what keeps the verification matrix building the
 * catalog under review rather than one baked into a binary.
 */
public final class Distribution {

    /** Written at build time. Absent when running from source, which is not an error. */
    private static final String RESOURCE = "/kitbash-distribution.properties";

    private Distribution() {}

    /** The released version, or {@code dev} when this is not a release build. */
    public static String version() {
        return properties().getProperty("version", "dev");
    }

    /**
     * The catalog this distribution shipped with, if it shipped with one.
     *
     * <p>{@code KITBASH_HOME} rather than a jar resource: the recipes are a tree of template files
     * that the renderer reads as files, and unpacking them to a temporary directory on every run
     * would be slower and would give the same answer.
     */
    public static Optional<Path> embeddedCatalog() {
        String home = System.getenv("KITBASH_HOME");
        if (home == null || home.isBlank()) {
            return Optional.empty();
        }
        Path recipes = Path.of(home).resolve("recipes");
        return Files.isDirectory(recipes) ? Optional.of(recipes) : Optional.empty();
    }

    /**
     * The line {@code --version} prints.
     *
     * <p>The digest is computed rather than read from a build property, so it describes the recipes
     * this binary will actually use — including when {@code --catalog} points somewhere else.
     * A recorded digest would describe what was intended at build time, and the two differ exactly
     * when somebody most needs to know.
     */
    public static String describe(String catalogDigest, Path catalogPath) {
        return "kitbash %s%ncatalog %s%n  from %s%n".formatted(version(), catalogDigest, catalogPath);
    }

    private static Properties properties() {
        Properties properties = new Properties();
        try (InputStream stream = Distribution.class.getResourceAsStream(RESOURCE)) {
            if (stream != null) {
                properties.load(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8));
            }
        } catch (IOException unreadable) {
            // A version string is not worth failing a generation over.
        }
        return properties;
    }
}
