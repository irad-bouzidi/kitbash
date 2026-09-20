package dev.kitbash.core.plan;

import dev.kitbash.core.hook.PlanContext;
import dev.kitbash.core.hook.RecipeHooks;
import dev.kitbash.core.patch.PatchOp;
import dev.kitbash.core.recipe.FileRule;
import dev.kitbash.core.recipe.PatchRule;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.recipe.WhenContext;
import dev.kitbash.core.recipe.WhenExpression;
import dev.kitbash.core.resolve.Resolution;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 3 of §6: walk the resolved recipes in order, evaluate every {@code when}, run the hooks,
 * and collect the file entries and patch ops — without rendering anything.
 *
 * <p>That last part is the reason the stage exists as its own step. The plan knows the whole shape
 * of the project while it is still cheap: the §13 caps are enforced here, a patch whose target no
 * recipe produces is visible here, and {@code /preview} can stop after stage 5 because stages 1–3
 * did the structural work.
 *
 * <p>Hooks run after every recipe has contributed, because a hook's whole purpose is to compute
 * something from the union of the selected set (§4) — running one mid-walk would show it half a
 * catalog.
 */
public final class Planner {

    private Planner() {}

    public static FilePlan plan(Resolution resolution, RecipeContent content, Caps caps) {
        WhenContext when = resolution.whenContext();
        List<FileEntry> entries = new ArrayList<>();
        List<PatchOp> patches = new ArrayList<>();
        Map<String, List<RecipeId>> claims = new LinkedHashMap<>();

        for (Recipe recipe : resolution.recipes()) {
            for (FileRule rule : recipe.files()) {
                if (!WhenExpression.parse(rule.when()).evaluate(when)) {
                    continue;
                }
                for (RecipeContent.RecipeFile file : content.filesMatching(recipe.id(), rule.from())) {
                    String path =
                            SafePaths.requireTemplatePath(recipe.id().value(), stripPrefix(rule.from(), file.path()));
                    caps.checkFileBytes(path, file.size());
                    entries.add(new FileEntry(
                            path,
                            file.content(),
                            FileMode.of(file.executable()),
                            recipe.id(),
                            path.endsWith(".peb"),
                            file.size()));
                    claims.computeIfAbsent(projectPath(path), ignored -> new ArrayList<>())
                            .add(recipe.id());
                }
            }
            for (PatchRule rule : recipe.patches()) {
                if (WhenExpression.parse(rule.when()).evaluate(when)) {
                    patches.add(rule.op());
                }
            }
        }

        patches.addAll(RecipeHooks.contributions(new PlanContext(
                resolution.recipes(), resolution.effectiveOptions(), Map.of(), resolution.capabilities())));

        FilePlan plan = new FilePlan(entries, patches, claims);
        caps.checkFileCount(plan.fileCount());
        caps.checkTotalBytes(plan.declaredBytes());
        return plan;
    }

    /**
     * A manifest glob's literal prefix is the recipe's own layout, not the project's: {@code from:
     * files/**} puts {@code files/src/Main.java} at {@code src/Main.java}, and {@code from:
     * arch/hexagonal/**} puts its tree at the same place. That is what lets a recipe hold several
     * alternative trees and select between them with {@code when}.
     */
    static String stripPrefix(String glob, String path) {
        int firstWildcard = firstWildcard(glob);
        String literal = firstWildcard < 0 ? glob : glob.substring(0, firstWildcard);
        int lastSlash = literal.lastIndexOf('/');
        String prefix = lastSlash < 0 ? "" : literal.substring(0, lastSlash + 1);
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    private static int firstWildcard(String glob) {
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == '{') {
                return i;
            }
        }
        return -1;
    }

    /** The path a patch will target: the same path with the template suffix gone. */
    private static String projectPath(String path) {
        return path.endsWith(".peb") ? path.substring(0, path.length() - ".peb".length()) : path;
    }
}
