package dev.kitbash.catalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds small recipe trees on disk, one per test.
 *
 * <p>Checked-in fixture directories would be worse here: each validation rule needs a tree that is
 * valid in every respect except one, and eight near-identical directories differing by a line is
 * the kind of fixture set nobody can diff. Writing them inline keeps the broken field visible in
 * the test that asserts on it.
 */
final class RecipeTreeFixture {

    private final Path root;

    RecipeTreeFixture(Path root) {
        this.root = root;
    }

    /** A minimal, valid base recipe: the thing every other fixture is a mutation of. */
    RecipeTreeFixture base() {
        return recipe(
                "base",
                """
                id: base
                version: 1.0.0
                kind: base
                label: Project skeleton
                provides: [project-root]
                files:
                  - from: files/**
                """,
                "files/README.md.peb",
                "# {{ projectName }}\n");
    }

    RecipeTreeFixture backend() {
        return recipe(
                "backend-spring-java",
                """
                id: backend-spring-java
                version: 1.4.0
                frameworkVersion: "3.5.5"
                kind: backend
                label: Spring Boot (Java)
                provides: [http-server, rest-api, jvm-project]
                requires: [project-root]
                options:
                  - id: architecture
                    type: enum
                    values: [layered]
                    default: layered
                    label: Architecture
                    help: Determines the package layout and dependency direction.
                variables:
                  required: [groupId, packageName]
                files:
                  - from: files/**
                    when: architecture == 'layered'
                patches:
                  - op: appendLines
                    target: .gitignore
                    lines: ["build/", ".gradle/"]
                """,
                "files/build.gradle.kts.peb",
                "// {{ groupId }}\n");
    }

    RecipeTreeFixture recipe(String id, String manifest, String filePath, String fileContent) {
        write(root.resolve(id).resolve("recipe.yaml"), manifest);
        if (filePath != null) {
            write(root.resolve(id).resolve(filePath), fileContent);
        }
        return this;
    }

    RecipeTreeFixture manifestOnly(String id, String manifest) {
        return recipe(id, manifest, null, null);
    }

    Path root() {
        return root;
    }

    private static void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
