package dev.kitbash.core.patch;

import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.workspace.GeneratedFile;
import dev.kitbash.core.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 5 of §6: the typed edits that make composition real.
 *
 * <p>Several recipes need to touch the same file — {@code build.gradle.kts}, {@code
 * application.yml}, {@code compose.yaml}, {@code package.json}, {@code .gitignore} — and §4 is
 * blunt about the alternative: <i>free-text appending produces broken syntax within a week.</i> So
 * every applier here parses, merges and re-serialises through the format's real model, and
 * {@link #applyOne} dispatches with a pattern-matching switch that carries no {@code default}
 * branch: a ninth operation is a compile error in this file, not a silent no-op.
 *
 * <p>Two invariants hold for every operation:
 *
 * <ul>
 *   <li><b>Idempotent.</b> Applying an op twice equals applying it once. That is what lets two
 *       recipes contribute the same {@code .gitignore} line or the same dependency without the
 *       output depending on how many of them were selected.
 *   <li><b>Owned.</b> An op against a file no selected recipe produced fails with {@code
 *       PATCH_TARGET_MISSING} and the §14 hint, before any bytes stream — never silently.
 * </ul>
 *
 * <p>Ops are applied in resolved recipe order, which is total (kitbash-8), so the result is
 * deterministic.
 */
public final class PatchApplier {

    private PatchApplier() {}

    public static void apply(Workspace workspace, List<PatchOp> ops) {
        ops.forEach(op -> applyOne(workspace, op));
    }

    /** Exhaustive over the §4 table. No default branch — deliberately, permanently. */
    static void applyOne(Workspace workspace, PatchOp op) {
        requireTarget(workspace, op, op.target());
        switch (op) {
            case PatchOp.AddDependency value -> addDependency(workspace, value);
            case PatchOp.MergeYaml value -> mergeYaml(workspace, value);
            case PatchOp.MergeJson value -> mergeJson(workspace, value);
            case PatchOp.AddScript value -> addScript(workspace, value);
            case PatchOp.InsertAtMarker value -> insertAtMarker(workspace, value);
            case PatchOp.AppendLines value -> appendLines(workspace, value);
            case PatchOp.AddEnvVar value -> addEnvVar(workspace, value);
            case PatchOp.AddComposeService value -> addComposeService(workspace, value);
        }
    }

    // --- structured formats --------------------------------------------------

    /**
     * Dispatches on the build system the target file belongs to. This is the one place a file name
     * decides behaviour, and it is a *format* decision rather than a technology one: the op says
     * "add this dependency", and how a dependency is spelled is the build file's business.
     */
    private static void addDependency(Workspace workspace, PatchOp.AddDependency op) {
        String target = op.target();
        String source = read(workspace, target);
        String patched;
        if (target.endsWith(".gradle.kts") || target.endsWith(".gradle")) {
            patched = GradleBuildFile.addDependency(source, op.configuration(), notation(op));
        } else if (target.endsWith("pom.xml")) {
            patched = MavenPomFile.addDependency(source, op.coordinate(), op.configuration());
        } else if (target.endsWith("package.json")) {
            patched = addNpmDependency(source, op);
        } else {
            throw GenerationError.patchTargetMissing(op.owner().value(), target, op.operation())
                    .asException();
        }
        write(workspace, target, patched);
    }

    /**
     * A version catalog reference when the recipe named one, a literal coordinate otherwise. §4
     * asks dependencies to "respect the version catalog", and Gradle's generated accessors turn
     * the alias {@code spring-boot-starter-web} into {@code libs.spring.boot.starter.web}.
     */
    private static String notation(PatchOp.AddDependency op) {
        return op.versionRef() == null || op.versionRef().isBlank()
                ? "\"" + op.coordinate() + "\""
                : "libs." + op.versionRef().replace('-', '.');
    }

    private static String addNpmDependency(String source, PatchOp.AddDependency op) {
        Map<String, Object> document = Documents.readJson(source);
        // "implementation" means nothing to npm; the configuration names the block.
        String block = op.configuration().isBlank() ? "dependencies" : op.configuration();
        Map<String, Object> dependencies = Documents.child(document, block);
        int at = op.coordinate().lastIndexOf('@');
        String name = at > 0 ? op.coordinate().substring(0, at) : op.coordinate();
        String version = at > 0 ? op.coordinate().substring(at + 1) : "*";

        Object existing = dependencies.get(name);
        if (existing != null && !existing.equals(version)) {
            throw GenerationError.patchCollision(
                            op.owner().value(), op.target(), op.operation(), block + "." + name, "an earlier recipe")
                    .asException();
        }
        dependencies.put(name, version);
        sortInPlace(dependencies);
        return Documents.writeJson(document);
    }

    private static void mergeYaml(Workspace workspace, PatchOp.MergeYaml op) {
        Map<String, Object> document =
                parse(op, op.target(), "YAML", () -> Documents.readYaml(read(workspace, op.target())));
        Documents.deepMerge(document, op.content(), "", collisionReporter(op, op.operation()));
        write(workspace, op.target(), Documents.writeYaml(document));
    }

    private static void mergeJson(Workspace workspace, PatchOp.MergeJson op) {
        Map<String, Object> document =
                parse(op, op.target(), "JSON", () -> Documents.readJson(read(workspace, op.target())));
        Documents.deepMerge(document, op.content(), "", collisionReporter(op, op.operation()));
        write(workspace, op.target(), Documents.writeJson(document));
    }

    private static void addScript(Workspace workspace, PatchOp.AddScript op) {
        Map<String, Object> document =
                parse(op, op.target(), "JSON", () -> Documents.readJson(read(workspace, op.target())));
        Map<String, Object> scripts = Documents.child(document, "scripts");
        Object existing = scripts.get(op.name());
        if (existing != null && !existing.equals(op.command())) {
            // Loudly, per §4: two recipes disagreeing about what `pnpm test` runs is not something
            // to resolve by ordering.
            throw GenerationError.patchCollision(
                            op.owner().value(),
                            op.target(),
                            op.operation(),
                            "scripts." + op.name(),
                            "an earlier recipe")
                    .asException();
        }
        scripts.put(op.name(), op.command());
        sortInPlace(scripts);
        write(workspace, op.target(), Documents.writeJson(document));
    }

    private static void addComposeService(Workspace workspace, PatchOp.AddComposeService op) {
        Map<String, Object> compose =
                parse(op, op.target(), "YAML", () -> Documents.readYaml(read(workspace, op.target())));
        Map<String, Object> services = Documents.child(compose, "services");

        Map<String, Object> definition = new LinkedHashMap<>(op.definition());
        if (!op.dependsOn().isEmpty()) {
            definition.put("depends_on", op.dependsOn());
        }

        Object existing = services.get(op.serviceName());
        if (existing instanceof Map<?, ?> existingMap) {
            Documents.deepMerge(
                    Documents.asMap(existingMap),
                    definition,
                    "services." + op.serviceName(),
                    collisionReporter(op, op.operation()));
        } else {
            services.put(op.serviceName(), definition);
        }
        write(workspace, op.target(), Documents.writeYaml(compose));
    }

    /**
     * One declaration, both places it has to exist: the {@code .env.example} that documents the
     * variable and the compose service that consumes it. Two ops is how those two files drift.
     */
    private static void addEnvVar(Workspace workspace, PatchOp.AddEnvVar op) {
        String source = read(workspace, op.target());
        List<String> lines = new ArrayList<>(source.lines().toList());
        String assignment = op.name() + "=" + op.value();
        boolean alreadyDeclared = lines.stream().anyMatch(line -> line.strip().startsWith(op.name() + "="));
        if (!alreadyDeclared) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            if (op.comment() != null && !op.comment().isBlank()) {
                lines.add("# " + op.comment());
            }
            lines.add(assignment);
            write(workspace, op.target(), String.join("\n", lines) + "\n");
        }

        if (op.composeTarget() == null || op.composeService() == null) {
            return;
        }
        if (!workspace.contains(op.composeTarget())) {
            // Containers were not selected. Not an error: the example file is the durable record
            // of the variable, and compose is the optional consumer.
            return;
        }
        Map<String, Object> compose = Documents.readYaml(read(workspace, op.composeTarget()));
        Map<String, Object> service = Documents.child(Documents.child(compose, "services"), op.composeService());
        Map<String, Object> environment = Documents.child(service, "environment");
        // ${NAME:-default} so the compose file works with an empty .env and still defers to one.
        environment.put(op.name(), "${" + op.name() + ":-" + op.effectiveComposeValue() + "}");
        write(workspace, op.composeTarget(), Documents.writeYaml(compose));
    }

    // --- text formats --------------------------------------------------------

    /**
     * Idempotency is judged on the op's <i>non-blank</i> lines: if every one of them is already in
     * the file, the op has been applied and nothing happens. Otherwise the op's lines are appended
     * verbatim, blank lines included, minus any non-blank line already present.
     *
     * <p>Treating a blank line as "already present" was the obvious first implementation and it is
     * wrong: a recipe appending a titled block — a blank line, {@code # Gradle}, then the entries —
     * would lose its separator and the sections would run together.
     */
    private static void appendLines(Workspace workspace, PatchOp.AppendLines op) {
        String source = read(workspace, op.target());
        List<String> lines = new ArrayList<>(source.lines().toList());
        boolean allPresent = op.lines().stream().filter(line -> !line.isBlank()).allMatch(line -> lines.stream()
                .anyMatch(existing -> existing.strip().equals(line.strip())));
        if (allPresent) {
            return;
        }
        op.lines().stream()
                .filter(line -> line.isBlank()
                        || lines.stream().noneMatch(existing -> existing.strip().equals(line.strip())))
                .forEach(lines::add);
        write(workspace, op.target(), String.join("\n", lines) + "\n");
    }

    /**
     * Markers are a contract between recipes: one places {@code // kitbash:imports}, another writes
     * into it. Lines go <b>above</b> the marker, so several recipes sharing one anchor stack in
     * resolved recipe order rather than in reverse.
     */
    private static void insertAtMarker(Workspace workspace, PatchOp.InsertAtMarker op) {
        List<String> lines =
                new ArrayList<>(read(workspace, op.target()).lines().toList());
        int marker = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(op.marker())) {
                marker = i;
                break;
            }
        }
        if (marker < 0) {
            throw new GenerationError.PatchTargetMissing(
                            new dev.kitbash.core.error.ErrorDetail(
                                    dev.kitbash.core.error.Stage.PATCH,
                                    op.owner().value(),
                                    op.target(),
                                    "The marker '" + op.marker() + "' is not in " + op.target() + ".",
                                    "Markers are a contract between recipes: whichever recipe owns " + op.target()
                                            + " has to place '" + op.marker() + "' for " + op.owner()
                                            + " to insert at.",
                                    null),
                            op.operation())
                    .asException();
        }
        if (containsBlock(lines, op.lines())) {
            return;
        }
        lines.addAll(marker, op.lines());
        write(workspace, op.target(), String.join("\n", lines) + "\n");
    }

    /**
     * Whether the file already contains this exact run of lines, anywhere.
     *
     * <p>The obvious check — "are these the lines right at the marker?" — stops being idempotent as
     * soon as a second recipe inserts at the same marker, because the first recipe's block is no
     * longer adjacent to it. Looking for the block anywhere holds however many recipes share an
     * anchor.
     */
    private static boolean containsBlock(List<String> lines, List<String> block) {
        if (block.isEmpty()) {
            return true;
        }
        for (int start = 0; start + block.size() <= lines.size(); start++) {
            if (lines.subList(start, start + block.size()).equals(block)) {
                return true;
            }
        }
        return false;
    }

    // --- plumbing ------------------------------------------------------------

    /**
     * Turns a malformed target into a typed error naming the file. A target that does not parse is
     * a recipe bug — some template emitted invalid YAML — and the user should be told which file,
     * not handed a Jackson stack trace (§14).
     */
    private static Map<String, Object> parse(
            PatchOp op, String target, String format, java.util.function.Supplier<Map<String, Object>> read) {
        try {
            return read.get();
        } catch (Documents.DocumentParseException e) {
            throw new GenerationError.RenderFailed(
                            new dev.kitbash.core.error.ErrorDetail(
                                    dev.kitbash.core.error.Stage.PATCH,
                                    op.owner().value(),
                                    target,
                                    target + " is not valid " + format + ": " + e.getMessage(),
                                    "Fix the template in whichever recipe produces " + target
                                            + "; a patch target has to parse before it can be merged into.",
                                    null),
                            target,
                            0,
                            e.getMessage())
                    .asException();
        }
    }

    private static Documents.CollisionReporter collisionReporter(PatchOp op, String operation) {
        return (path, existing, incoming) -> {
            throw GenerationError.patchCollision(op.owner().value(), op.target(), operation, path, "an earlier recipe")
                    .asException();
        };
    }

    private static void requireTarget(Workspace workspace, PatchOp op, String target) {
        if (!workspace.contains(target)) {
            throw GenerationError.patchTargetMissing(op.owner().value(), target, op.operation())
                    .asException();
        }
    }

    /** npm and Gradle both sort these by convention, and a sorted map diffs far better. */
    private static void sortInPlace(Map<String, Object> map) {
        Map<String, Object> sorted = new java.util.TreeMap<>(map);
        map.clear();
        map.putAll(sorted);
    }

    private static String read(Workspace workspace, String path) {
        GeneratedFile file = workspace.get(path);
        return file == null ? "" : new String(file.content(), StandardCharsets.UTF_8);
    }

    private static void write(Workspace workspace, String path, String content) {
        GeneratedFile existing = workspace.get(path);
        workspace.put(
                path,
                new GeneratedFile(content.getBytes(StandardCharsets.UTF_8), existing != null && existing.executable()));
    }
}
