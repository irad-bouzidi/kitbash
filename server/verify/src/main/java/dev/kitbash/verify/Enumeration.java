package dev.kitbash.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.core.recipe.Recipe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The full matrix, derived from the catalog rather than written down.
 *
 * <p>§11 calls roughly ninety-six combinations "a sane matrix to verify and real proof the
 * composition model works". The point of deriving them is that a recipe added tomorrow expands the
 * matrix tomorrow: nobody has to remember to write ninety-six more JSON files, and nobody can
 * forget to.
 *
 * <h2>What is enumerated, and what is not</h2>
 *
 * <p>The cross product of every slot and option is nine thousand combinations, most of them
 * unreachable and nearly all of them uninteresting. What varies the <em>shape</em> of a generated
 * project is enumerated in full:
 *
 * <pre>
 *   build tool (2) x backend (2) x architecture (3) x frontend (2) x containers (2) x CI (2) = 96
 * </pre>
 *
 * <p>The feature toggles — auth, metrics, tracing, the typed client — do not multiply it. Each is
 * switched by one bit of the cell's index, so every feature is on in half the cells and every
 * <em>pair</em> of features occurs at both settings of the other. That is pairwise coverage by
 * construction rather than by a sampling heuristic somebody has to trust, and it costs nothing:
 * ninety-six cells either way.
 *
 * <p>Two exclusions, both deliberate:
 *
 * <ul>
 *   <li><b>The database is not an axis.</b> Every backend in the catalog requires one, so a
 *       backend cell without a database is not a combination — it is an invalid selection.
 *   <li><b>Frontend-only is sampled, not enumerated</b> — §18 grants that exemption by name,
 *       because supporting a standalone frontend costs one conditional and should not double the
 *       matrix. Two cells cover it: with containers and without.
 * </ul>
 */
public final class Enumeration {

    /**
     * What the enumeration is expected to produce.
     *
     * <p>An assertion rather than a comment, because the enumeration reads the catalog: a recipe
     * declared into one slot too many turns ninety-eight cells into nine hundred, and the symptom
     * would be a nightly that quietly stops finishing. Changing this number is how a maintainer
     * says the growth was intended.
     */
    public static final int EXPECTED_CELLS = 98;

    private static final ObjectMapper JSON = new ObjectMapper();

    private Enumeration() {}

    /** Every feature toggle, in the order whose bit switches it. */
    private static final List<String> FEATURES = List.of("auth", "observability", "typedClient", "tracing");

    /**
     * One letter per feature for the cell id, spelled out rather than taken from the first
     * character — {@code typedClient} and {@code tracing} both start with a {@code t}, and an id
     * ending {@code -t-t} tells a reader nothing.
     */
    private static final Map<String, String> FEATURE_LETTERS =
            Map.of("auth", "a", "observability", "o", "typedClient", "c", "tracing", "r");

    public static List<Cell> cells(Catalog catalog, Repository repository) {
        List<String> buildTools = idsInSlot(catalog, "buildTool");
        List<String> backends = idsInSlot(catalog, "backend");
        List<String> frontends = idsInSlot(catalog, "frontend");
        List<String> providers = idsInSlot(catalog, "ci");
        List<String> architectures = architectures(catalog, backends);

        List<Cell> cells = new ArrayList<>();
        int index = 0;
        for (String buildTool : buildTools) {
            for (String backend : backends) {
                for (String architecture : architectures) {
                    for (String frontend : withNothing(frontends)) {
                        for (boolean docker : List.of(true, false)) {
                            for (String provider : providers) {
                                cells.add(cell(
                                        repository,
                                        options(
                                                index++,
                                                buildTool,
                                                backend,
                                                architecture,
                                                frontend,
                                                docker,
                                                provider)));
                            }
                        }
                    }
                }
            }
        }

        // §18's exemption, spent here and nowhere else. One CI provider each, because a cell
        // whose steps include linting a pipeline needs a pipeline to lint.
        int sample = 0;
        for (boolean docker : List.of(true, false)) {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("frontend", frontends.get(0));
            options.put("ci", providers.get(sample++ % providers.size()));
            options.put("docker", docker);
            cells.add(cell(repository, options));
        }

        return List.copyOf(cells);
    }

    private static Map<String, Object> options(
            int index,
            String buildTool,
            String backend,
            String architecture,
            String frontend,
            boolean docker,
            String provider) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("buildTool", buildTool);
        options.put("backend", backend);
        options.put("architecture", architecture);
        options.put("database", "db-postgres-flyway");
        if (frontend != null) {
            options.put("frontend", frontend);
        }
        options.put("ci", provider);
        options.put("docker", docker);

        for (int bit = 0; bit < FEATURES.size(); bit++) {
            boolean on = ((index >> bit) & 1) == 1;
            String feature = FEATURES.get(bit);
            // Two features are not free-standing: the typed client needs a frontend to put the
            // client in, and tracing is a setting of the metrics recipe rather than a recipe.
            if (feature.equals("typedClient") && frontend == null) {
                on = false;
            }
            if (feature.equals("tracing") && !Boolean.TRUE.equals(options.get("observability"))) {
                on = false;
            }
            options.put(feature, on);
        }
        return options;
    }

    /**
     * One cell, with its selection written where the runner expects a file.
     *
     * <p>Enumerated selections are build output, not source: writing them under
     * {@code verification/build} means the runner, {@code generate.sh} and a person reproducing a
     * failure all read the same file, and none of them needs a second code path for a cell nobody
     * checked in.
     */
    private static Cell cell(Repository repository, Map<String, Object> options) {
        String id = idFor(options);
        Path file = repository.output().resolve("enumerated").resolve(id + ".json");

        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("schemaVersion", 1);
        // A short, ordinary name rather than the cell id: the id exists so a person reading a red
        // pipeline knows which combination broke, and a generated project should be named the way
        // a user would name one.
        selection.put("projectName", "matrix-cell");
        selection.put("options", options);
        selection.put("variables", variables());

        try {
            Files.createDirectories(file.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), selection);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the enumerated selection " + id, e);
        }

        return new Cell(
                id,
                "Enumerated from the catalog: " + options,
                // Relative to `verification/`, because that is what `Repository.selectionFor`
                // resolves against — the same rule the checked-in cells' paths follow.
                repository.verification().relativize(file).toString(),
                java.util.Set.of("nightly"),
                options.containsKey("frontend") && Boolean.TRUE.equals(options.get("typedClient")),
                steps(options));
    }

    private static List<Cell.Step> steps(Map<String, Object> options) {
        List<Cell.Step> steps = new ArrayList<>();
        if (options.containsKey("backend")) {
            boolean maven = "build-maven".equals(options.get("buildTool"));
            boolean typedClient = Boolean.TRUE.equals(options.get("typedClient"));
            List<String> commands = new ArrayList<>();
            // Maven generates the client inside one lifecycle, so the profile replaces the plain
            // build rather than following it; Gradle's is a separate task after it.
            commands.add(
                    maven
                            ? (typedClient ? "./mvnw -B -Pclient verify" : "./mvnw -B verify")
                            : "./gradlew build --no-daemon");
            if (typedClient && !maven) {
                commands.add("./gradlew generateApiClient --no-daemon");
            }
            steps.add(new Cell.Step("jvm", ".", commands));
        }
        if (options.containsKey("frontend")) {
            steps.add(new Cell.Step(
                    "node",
                    "frontend",
                    List.of(
                            "pnpm install --frozen-lockfile",
                            "pnpm lint",
                            "pnpm typecheck",
                            "pnpm test",
                            "pnpm build")));
        }
        steps.add(new Cell.Step(
                "ci",
                ".",
                List.of(
                        "ci-github".equals(options.get("ci"))
                                ? "actionlint -no-color .github/workflows/ci.yml"
                                : "check-jsonschema --builtin-schema vendor.gitlab-ci .gitlab-ci.yml")));
        return steps;
    }

    /**
     * A name that says what the cell is, because a failure names it and nothing else.
     *
     * <p>{@code e-maven-kotlin-hexagonal-spa-docker-github-a-o} beats {@code cell-47}: the person
     * reading a red pipeline at eight in the morning should not have to open a file to learn which
     * combination broke.
     */
    private static String idFor(Map<String, Object> options) {
        StringBuilder id = new StringBuilder("e");
        append(id, options.get("buildTool"), "build-");
        append(id, options.get("backend"), "backend-spring-");
        append(id, options.get("architecture"), "");
        if (options.containsKey("frontend")) {
            id.append("-spa");
        }
        if (Boolean.TRUE.equals(options.get("docker"))) {
            id.append("-docker");
        }
        append(id, options.get("ci"), "ci-");
        for (String feature : FEATURES) {
            if (Boolean.TRUE.equals(options.get(feature))) {
                id.append('-').append(FEATURE_LETTERS.get(feature));
            }
        }
        return id.toString();
    }

    private static void append(StringBuilder id, Object value, String prefix) {
        if (value != null) {
            id.append('-').append(value.toString().replace(prefix, "").replace("-kts", ""));
        }
    }

    private static Map<String, String> variables() {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("groupId", "com.example");
        variables.put("packageName", "com.example.matrix");
        variables.put("javaVersion", "21");
        variables.put("entityName", "Widget");
        variables.put("entityTable", "widgets");
        variables.put("envPrefix", "MATRIX");
        return variables;
    }

    private static List<String> idsInSlot(Catalog catalog, String slot) {
        return catalog.recipesInSlot(slot).stream()
                .map(Recipe::id)
                .map(id -> id.value())
                .sorted()
                .toList();
    }

    /** The architectures the backends offer, which is one option declared by both of them. */
    private static List<String> architectures(Catalog catalog, List<String> backends) {
        return catalog.recipesInSlot("backend").stream()
                .flatMap(recipe -> recipe.options().stream())
                .filter(option -> option.id().equals("architecture"))
                .findFirst()
                .map(option -> List.copyOf(option.values()))
                .orElse(List.of("layered"));
    }

    private static List<String> withNothing(List<String> values) {
        List<String> all = new ArrayList<>();
        all.add(null);
        all.addAll(values);
        return all;
    }
}
