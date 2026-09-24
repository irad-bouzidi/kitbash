package dev.kitbash.render;

import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.plan.FileEntry;
import dev.kitbash.core.plan.FilePlan;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.workspace.GeneratedFile;
import dev.kitbash.core.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Stage 4 of §6: a plan of templates becomes a workspace of text.
 *
 * <p>Paths are rendered as well as bodies, because the package name has to reach the directory
 * layout: {@code files/src/main/java/{{ packageName | packagePath }}/Application.java.peb} lands
 * where javac expects it. The {@code .peb} suffix marks a file as a template and is stripped from
 * the output; a file without it is copied through byte for byte, which is what keeps binaries and
 * anything containing {@code {{ }}} for its own reasons safe.
 *
 * <p>Rendering fans out over virtual threads — the work is independent per file, and §5 picked
 * virtual threads for exactly this — but results are collected back in plan order rather than in
 * completion order. Determinism is not a nice property here: the zip cache key and the
 * reproducibility guarantee both assume two identical selections produce identical bytes (§4), and
 * "whichever thread finished first" is the classic way to lose that.
 */
public final class Renderer {

    private static final String TEMPLATE_SUFFIX = ".peb";

    private Renderer() {}

    public static Workspace render(FilePlan plan, TemplateVariables variables) {
        return render(plan, variables, Map.of());
    }

    /**
     * The same, with a per-recipe variable overlay so a template can read its own manifest's
     * framework version.
     */
    public static Workspace render(
            FilePlan plan, TemplateVariables variables, Map<RecipeId, TemplateVariables> perRecipe) {
        return render(plan, variables, perRecipe, Map.of());
    }

    /**
     * The same, for a caller that has already evaluated some of the templates elsewhere.
     *
     * <p>{@code alreadyRendered} maps a template name to its output, and exists for kitbash-48:
     * a contributed recipe's templates are evaluated in the sandbox, in another process, and
     * arrive here as finished strings. Everything after evaluation — stripping the {@code .peb}
     * suffix, checking the rendered path, the binary guard, plan-order collection — is the same
     * for both, and is deliberately not written twice.
     *
     * <p>The load-bearing half is {@link #registryFor}: an entry whose output is supplied here is
     * <b>left out of the in-process registry entirely</b>. So a contributed template is not merely
     * routed away from the engine, it is not loadable by it — and neither is it reachable by an
     * {@code include} from a shipped template, or from another recipe's.
     */
    public static Workspace render(
            FilePlan plan,
            TemplateVariables variables,
            Map<RecipeId, TemplateVariables> perRecipe,
            Map<String, String> alreadyRendered) {
        List<FileEntry> entries = plan.effectiveEntries();
        TemplateEngine engine = TemplateEngine.over(registryFor(entries, alreadyRendered));

        List<Rendered> rendered = new ArrayList<>(entries.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Rendered>> futures = new ArrayList<>(entries.size());
            for (FileEntry entry : entries) {
                futures.add(executor.submit(() ->
                        renderOne(engine, entry, perRecipe.getOrDefault(entry.owner(), variables), alreadyRendered)));
            }
            for (Future<Rendered> future : futures) {
                rendered.add(await(future));
            }
        }

        Workspace workspace = new Workspace();
        rendered.forEach(file -> workspace.put(file.path(), file.file()));
        return workspace;
    }

    /** One entry's rendered path and body. Public only in the sense that the pipeline drives it. */
    private record Rendered(String path, GeneratedFile file) {}

    private static Rendered renderOne(
            TemplateEngine engine, FileEntry entry, TemplateVariables variables, Map<String, String> alreadyRendered) {
        String recipeId = entry.owner().value();
        String path =
                stripTemplateSuffix(evaluate(engine, pathTemplateName(entry), recipeId, variables, alreadyRendered));
        // Checked whoever rendered it. A path that came back from the sandbox is a path an
        // untrusted template chose, so it earns this more than a shipped one does.
        requireSafe(recipeId, path);

        byte[] raw = entry.read();
        if (!entry.templated()) {
            return new Rendered(path, new GeneratedFile(raw, entry.mode().executable()));
        }
        if (looksBinary(raw)) {
            // A .peb suffix on a binary is a mistake somebody will otherwise discover as mojibake
            // in a generated jar.
            throw GenerationError.renderFailed(
                            recipeId, entry.path(), 0, "the file is binary but is marked as a template")
                    .asException();
        }
        String body = evaluate(engine, bodyTemplateName(entry), recipeId, variables, alreadyRendered);
        return new Rendered(
                path,
                new GeneratedFile(
                        body.getBytes(StandardCharsets.UTF_8), entry.mode().executable()));
    }

    /**
     * Every template this pass may load: the entries' path templates, and the bodies of the ones
     * marked as templates. Nothing else is reachable, which is what closes {@code include} (§13).
     */
    private static TemplateRegistry registryFor(List<FileEntry> entries, Map<String, String> alreadyRendered) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (FileEntry entry : entries) {
            if (alreadyRendered.containsKey(pathTemplateName(entry))) {
                // Rendered elsewhere, so not loadable here. This is the line that makes
                // "contributed templates never reach the in-process engine" true rather than
                // merely intended — without it they would still be in the registry, and an
                // include from any other template could pull one in.
                continue;
            }
            sources.put(pathTemplateName(entry), entry.path());
            if (entry.templated()) {
                sources.put(bodyTemplateName(entry), new String(entry.read(), StandardCharsets.UTF_8));
            }
        }
        return TemplateRegistry.of(sources);
    }

    /** Whatever the sandbox returned, or the engine if this template is ours to evaluate. */
    private static String evaluate(
            TemplateEngine engine,
            String templateName,
            String recipeId,
            TemplateVariables variables,
            Map<String, String> alreadyRendered) {
        String supplied = alreadyRendered.get(templateName);
        return supplied != null ? supplied : engine.render(templateName, recipeId, variables);
    }

    public static String pathTemplateName(FileEntry entry) {
        return entry.owner().value() + "::" + entry.path() + "::path";
    }

    public static String bodyTemplateName(FileEntry entry) {
        return entry.owner().value() + "::" + entry.path();
    }

    static String stripTemplateSuffix(String path) {
        return path.endsWith(TEMPLATE_SUFFIX) ? path.substring(0, path.length() - TEMPLATE_SUFFIX.length()) : path;
    }

    /**
     * The floor, not the whole defence — {@code kitbash-20} owns path safety in full, including
     * Windows reserved names and case collisions. What matters here is that a *rendered* path is
     * checked at all: a template can produce a path its author never typed.
     */
    private static void requireSafe(String recipeId, String path) {
        if (path.isBlank()) {
            throw GenerationError.pathEscape(recipeId, path, "the rendered path is empty")
                    .asException();
        }
        if (path.startsWith("/") || path.contains("\\") || path.matches("^[A-Za-z]:.*")) {
            throw GenerationError.pathEscape(recipeId, path, "it is absolute").asException();
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                throw GenerationError.pathEscape(recipeId, path, "it traverses above the project root")
                        .asException();
            }
        }
    }

    /** A NUL byte in the first block is the same heuristic git uses, and for the same reason. */
    private static boolean looksBinary(byte[] content) {
        int limit = Math.min(content.length, 8_000);
        for (int i = 0; i < limit; i++) {
            if (content[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static Rendered await(Future<Rendered> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Rendering was interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Rendering failed", e.getCause());
        }
    }
}
