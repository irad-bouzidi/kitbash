package dev.kitbash.api.validate;

import dev.kitbash.api.generate.GenerateRequest;
import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.pipeline.GenerationPipeline;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.resolve.Resolution;
import dev.kitbash.core.resolve.ResolutionWarning;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/validate} — stages 1–2 of §6 and nothing else.
 *
 * <p>It exists as a separate endpoint precisely because it is cheap: the wizard calls it on every
 * debounced option change (§9), and it can afford that because resolving touches no template, no
 * filesystem and no database. Keeping it free of side effects is therefore a requirement rather
 * than good manners.
 *
 * <p>It always answers 200. A conflict is the expected outcome of a half-made selection, not an
 * error — returning 400 would make the wizard's normal state an error state, and a client cannot
 * render inline field messages from a stack trace.
 */
@RestController
@RequestMapping("/api/v1")
public class ValidateController {

    private final GenerationPipeline pipeline;

    public ValidateController(GenerationPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @PostMapping("/validate")
    public ResponseEntity<ValidationResponse> validate(@RequestBody GenerateRequest request) {
        Resolution resolution = pipeline.validate(request.toEnvelope());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ValidationResponse.of(resolution, pipeline.catalog().digest()));
    }

    /**
     * What {@code /validate} returns: the stack a selection means, and everything wrong with it.
     *
     * <p>{@code implied} is separate from {@code recipes} because §9 wants the right rail to show
     * what the resolver <i>added</i> — a user who picked a backend and got a database should be
     * told, not left to find it in the zip.
     */
    public record ValidationResponse(
            boolean valid,
            String catalogDigest,
            String selectionHash,
            List<ResolvedRecipe> recipes,
            List<String> capabilities,
            Map<String, Object> effectiveOptions,
            List<Diagnostic> conflicts,
            List<Diagnostic> warnings) {

        static ValidationResponse of(Resolution resolution, String catalogDigest) {
            Map<String, Object> options = new LinkedHashMap<>();
            resolution.effectiveOptions().forEach((id, value) -> options.put(id, value.templateValue()));

            return new ValidationResponse(
                    resolution.valid(),
                    catalogDigest,
                    null,
                    resolution.recipes().stream()
                            .map(recipe -> ResolvedRecipe.of(recipe, resolution))
                            .toList(),
                    resolution.capabilities().stream()
                            .map(dev.kitbash.core.recipe.Capability::name)
                            .sorted()
                            .toList(),
                    options,
                    resolution.conflicts().stream().map(Diagnostic::of).toList(),
                    resolution.warnings().stream().map(Diagnostic::of).toList());
        }
    }

    public record ResolvedRecipe(
            String id, String label, String kind, String recipeVersion, String frameworkVersion, boolean implied) {

        static ResolvedRecipe of(Recipe recipe, Resolution resolution) {
            return new ResolvedRecipe(
                    recipe.id().value(),
                    recipe.label(),
                    recipe.kind().wireName(),
                    recipe.version().toString(),
                    recipe.frameworkVersion(),
                    resolution.implied().contains(recipe.id()));
        }
    }

    /**
     * A conflict and a warning in one shape, because a client renders them the same way — inline on
     * the field named by {@code optionId}, never as a banner (§9).
     */
    public record Diagnostic(String code, String stage, String optionId, String recipe, String message, String hint) {

        static Diagnostic of(GenerationError error) {
            return new Diagnostic(
                    error.code().name(),
                    error.stage().wireName(),
                    optionOf(error),
                    error.recipe(),
                    error.message(),
                    error.hint());
        }

        static Diagnostic of(ResolutionWarning warning) {
            return new Diagnostic("WARNING", "resolve", warning.optionId(), null, warning.message(), warning.hint());
        }

        /** The control the user can change, which is the whole point of the §14 structured fields. */
        private static String optionOf(GenerationError error) {
            return switch (error) {
                case GenerationError.CapabilityUnsatisfied value -> value.optionId();
                case GenerationError.Conflict value -> value.optionId();
                case GenerationError.InvalidIdentifier value -> value.field();
                case GenerationError.UnknownRecipe value -> null;
                case GenerationError.Cycle value -> null;
                case GenerationError.PatchTargetMissing value -> null;
                case GenerationError.PatchCollision value -> null;
                case GenerationError.PathEscape value -> null;
                case GenerationError.LimitExceeded value -> null;
                case GenerationError.RenderFailed value -> null;
            };
        }
    }
}
