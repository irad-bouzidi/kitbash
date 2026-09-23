package dev.kitbash.verify.bump;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kitbash.verify.Repository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The job that stops the catalog rotting, tested without a network.
 *
 * <p>A bump job is a find-and-replace loose in a repository once a week, so what needs proving is
 * not that it can fetch a version — it is that it replaces the right strings, leaves the wrong ones
 * alone, and declines to propose a release candidate. {@link Releases} is an interface for exactly
 * that: the interesting logic runs against a list somebody wrote down.
 */
class BumperTest {

    @Nested
    @DisplayName("what counts as an upgrade")
    class WhatCounts {

        @Test
        @DisplayName("a pre-release is never proposed, however new it is")
        void ignoresPreReleases() {
            assertThat(Versions.releasesNewestFirst(
                            List.of("3.5.5", "4.0.0-M2", "4.0.0-RC1", "3.5.6", "4.0.0-SNAPSHOT")))
                    .as("a job that proposed 4.0.0-M2 every Monday would be turned off by the third")
                    .containsExactly("3.5.6", "3.5.5");
        }

        @Test
        @DisplayName(".Final means the opposite of the other suffixes, and the JVM insists on it")
        void acceptsFinal() {
            assertThat(Versions.isRelease("6.6.26.Final")).isTrue();
            assertThat(Versions.isRelease("6.6.26.CR1")).isFalse();
        }

        @Test
        @DisplayName("versions compare by number, so 10 is newer than 9")
        void comparesNumerically() {
            assertThat(Versions.isNewer("1.10.0", "1.9.0")).isTrue();
            assertThat(Versions.isNewer("1.9.0", "1.10.0")).isFalse();
            assertThat(Versions.isNewer("3.5.5", "3.5.5")).isFalse();
        }
    }

    @Nested
    @DisplayName("rewriting")
    class Rewriting {

        @Test
        @DisplayName("every file in the recipe moves together, which is what makes one bump coherent")
        void replacesAcrossTheRecipe(@TempDir Path root) throws IOException {
            Path recipe = root.resolve("backend");
            Files.createDirectories(recipe.resolve("files"));
            Files.writeString(
                    recipe.resolve("recipe.yaml"),
                    "id: backend\n"
                            + "frameworkVersion: \"3.5.5\"\n"
                            + "tracks:\n"
                            + "  - version: \"3.5.5\"\n"
                            + "    artifact: org.springframework.boot:spring-boot-starter-parent\n"
                            + "    label: Spring Boot\n");
            Files.writeString(recipe.resolve("files/catalog.toml"), "spring-boot = \"3.5.5\"\n");

            Bumper bumper = new Bumper(root, tracked -> List.of("3.5.5", "3.5.6"));
            TrackedVersion tracked = bumper.tracked().get(0);

            assertThat(bumper.apply(tracked, "3.5.6")).isEqualTo(2);
            assertThat(Files.readString(recipe.resolve("recipe.yaml")))
                    .contains("3.5.6")
                    .doesNotContain("3.5.5");
            assertThat(Files.readString(recipe.resolve("files/catalog.toml"))).contains("3.5.6");
        }

        /** The failure that would pass review because the diff looks fine. */
        @Test
        @DisplayName("a version inside a longer number is left alone")
        void doesNotCorruptNeighbouringNumbers(@TempDir Path root) throws IOException {
            Path recipe = root.resolve("db");
            Files.createDirectories(recipe);
            Files.writeString(
                    recipe.resolve("recipe.yaml"),
                    "id: db\n"
                            + "tracks:\n"
                            + "  - version: \"1.4.1\"\n"
                            + "    artifact: com.tngtech.archunit:archunit-junit5\n");
            Files.writeString(recipe.resolve("other.txt"), "archunit 1.4.1\nunrelated 11.4.10\nport 21.4.100\n");

            Bumper bumper = new Bumper(root, tracked -> List.of("1.4.2"));
            bumper.apply(bumper.tracked().get(0), "1.4.2");

            assertThat(Files.readString(recipe.resolve("other.txt")))
                    .contains("archunit 1.4.2")
                    .contains("unrelated 11.4.10")
                    .contains("port 21.4.100");
        }

        @Test
        @DisplayName("a bump stays inside the recipe that declared it")
        void staysInsideOneRecipe(@TempDir Path root) throws IOException {
            for (String name : List.of("one", "two")) {
                Files.createDirectories(root.resolve(name));
                Files.writeString(root.resolve(name).resolve("shared.txt"), "pinned 2.0.0\n");
            }
            Files.writeString(
                    root.resolve("one").resolve("recipe.yaml"),
                    "id: one\ntracks:\n  - version: \"2.0.0\"\n    artifact: com.example:thing\n");

            Bumper bumper = new Bumper(root, tracked -> List.of("2.0.1"));
            bumper.apply(bumper.tracked().get(0), "2.0.1");

            assertThat(Files.readString(root.resolve("one/shared.txt"))).contains("2.0.1");
            assertThat(Files.readString(root.resolve("two/shared.txt")))
                    .as("a recipe's bump reached into another recipe's files")
                    .contains("2.0.0");
        }
    }

    @Nested
    @DisplayName("the catalog's own declarations")
    class TheRealCatalog {

        private static final Bumper BUMPER =
                new Bumper(Repository.locate().root().resolve("recipes"), tracked -> List.of());

        @Test
        @DisplayName("every declared version actually appears in the recipe that declares it")
        void declarationsMatchReality() {
            List<String> missing = new ArrayList<>();
            for (TrackedVersion tracked : BUMPER.tracked()) {
                if (!appears(tracked)) {
                    missing.add(tracked.recipe() + " declares " + tracked.version() + " and never writes it");
                }
            }
            assertThat(missing)
                    .as("a tracks entry nothing matches is a bump that would silently do nothing")
                    .isEmpty();
        }

        @Test
        @DisplayName("something is tracked, so the weekly job has work to look at")
        void tracksSomething() {
            assertThat(BUMPER.tracked()).hasSizeGreaterThan(5);
            assertThat(BUMPER.tracked()).allSatisfy(tracked -> {
                assertThat(tracked.artifact()).contains(":");
                assertThat(tracked.version()).matches("\\d+\\.\\d+(\\.\\d+)?");
            });
        }

        private static boolean appears(TrackedVersion tracked) {
            Path directory = Repository.locate().root().resolve("recipes").resolve(tracked.recipe());
            try (var files = Files.walk(directory)) {
                return files.filter(Files::isRegularFile).anyMatch(file -> {
                    try {
                        return Files.readString(file).contains(tracked.version());
                    } catch (IOException notText) {
                        return false;
                    }
                });
            } catch (IOException e) {
                return false;
            }
        }
    }

    @Nested
    @DisplayName("planning")
    class Planning {

        /**
         * The first bump this job produced was red because ktlint 1.8 needs a newer Kotlin compiler
         * than our Spotless ships. Without a ceiling it would be proposed again every Monday, and a
         * job that is red every Monday is a job somebody switches off.
         */
        @Test
        @DisplayName("a declared ceiling holds a known-bad upgrade back, and says why")
        void honoursADeclaredCeiling(@TempDir Path root) throws IOException {
            Files.createDirectories(root.resolve("kotlin"));
            Files.writeString(
                    root.resolve("kotlin").resolve("recipe.yaml"),
                    "id: kotlin\n"
                            + "tracks:\n"
                            + "  - version: \"1.5.0\"\n"
                            + "    artifact: com.pinterest.ktlint:ktlint-cli\n"
                            + "    holdBelow: \"1.6.0\"\n"
                            + "    because: needs a newer Spotless\n");

            Bumper.Result result = new Bumper(root, tracked -> List.of("1.5.0", "1.8.0")).plan(new ArrayList<>());

            assertThat(result.available()).isEmpty();
            assertThat(result.held()).hasSize(1);
            assertThat(result.held().keySet())
                    .allSatisfy(tracked -> assertThat(tracked.because()).contains("Spotless"));
        }

        @Test
        @DisplayName("a ceiling still lets through the versions below it")
        void allowsUpgradesUnderTheCeiling(@TempDir Path root) throws IOException {
            Files.createDirectories(root.resolve("kotlin"));
            Files.writeString(
                    root.resolve("kotlin").resolve("recipe.yaml"),
                    "id: kotlin\n"
                            + "tracks:\n"
                            + "  - version: \"1.5.0\"\n"
                            + "    artifact: com.pinterest.ktlint:ktlint-cli\n"
                            + "    holdBelow: \"1.6.0\"\n"
                            + "    because: needs a newer Spotless\n");

            Bumper.Result result =
                    new Bumper(root, tracked -> List.of("1.5.0", "1.5.9", "1.8.0")).plan(new ArrayList<>());

            assertThat(result.available()).containsValue("1.5.9");
        }

        @Test
        @DisplayName("one artifact failing does not stop the others being checked")
        void keepsGoingPastAFailure(@TempDir Path root) throws IOException {
            for (String name : List.of("good", "bad")) {
                Files.createDirectories(root.resolve(name));
                Files.writeString(
                        root.resolve(name).resolve("recipe.yaml"),
                        "id: %s\ntracks:\n  - version: \"1.0.0\"\n    artifact: com.example:%s\n"
                                .formatted(name, name));
            }

            Releases flaky = tracked -> {
                if (tracked.name().equals("bad")) {
                    throw new IOException("gone");
                }
                return List.of("1.0.1");
            };

            List<String> problems = new ArrayList<>();
            Map<TrackedVersion, String> available =
                    new Bumper(root, flaky).plan(problems).available();

            assertThat(available).hasSize(1);
            assertThat(available.keySet())
                    .allSatisfy(tracked -> assertThat(tracked.recipe()).isEqualTo("good"));
            assertThat(problems).singleElement().asString().contains("com.example:bad", "gone");
        }
    }
}
