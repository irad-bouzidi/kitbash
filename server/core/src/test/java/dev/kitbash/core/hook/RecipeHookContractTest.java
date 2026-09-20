package dev.kitbash.core.hook;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kitbash.core.patch.PatchOp;
import dev.kitbash.core.recipe.Capability;
import dev.kitbash.core.recipe.FileRule;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.recipe.RecipeKind;
import dev.kitbash.core.recipe.RecipeVersion;
import dev.kitbash.core.selection.OptionValue;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The contract §4 puts on every hook, asserted against every hook that is registered — so a new one
 * inherits the tests rather than needing its own.
 */
class RecipeHookContractTest {

    static Stream<RecipeHook> registeredHooks() {
        return RecipeHooks.all().stream();
    }

    static PlanContext context() {
        return new PlanContext(
                List.of(TestRecipes.base(), TestRecipes.gradle(), TestRecipes.backend()),
                Map.of("architecture", OptionValue.text("layered")),
                Map.of("groupId", "com.acme", "packageName", "com.acme.customer"),
                Set.of(Capability.of("project-root"), Capability.of("build-tool"), Capability.of("rest-api")));
    }

    @ParameterizedTest
    @MethodSource("registeredHooks")
    @DisplayName("a hook only ever returns ops owned by its own recipe")
    void ownsWhatItReturns(RecipeHook hook) {
        List<PatchOp> ops = hook.contribute(context());

        assertThat(ops).allSatisfy(op -> assertThat(op.owner().value()).isEqualTo(hook.recipeId()));
    }

    @ParameterizedTest
    @MethodSource("registeredHooks")
    @DisplayName("a hook is pure: the same context gives an equal answer, every time")
    void isPure(RecipeHook hook) {
        PlanContext context = context();

        assertThat(hook.contribute(context)).isEqualTo(hook.contribute(context));
    }

    @ParameterizedTest
    @MethodSource("registeredHooks")
    @DisplayName("a hook holds no state between calls")
    void isStateless(RecipeHook hook) {
        assertThat(hook.getClass().getDeclaredFields())
                .withFailMessage("%s has instance state; a hook is a function, not an object", hook.getClass())
                .allMatch(field -> java.lang.reflect.Modifier.isStatic(field.getModifiers()));
    }

    @ParameterizedTest
    @MethodSource("registeredHooks")
    @DisplayName("a hook touches no filesystem, network, clock or randomness")
    void doesNoIo(RecipeHook hook) {
        assertThat(HookPurity.violations(hook.getClass()))
                .withFailMessage(
                        "%s references %s. Hooks are pure (§4): PlanContext deliberately offers no way "
                                + "to do this, so it had to be reached for through a static.",
                        hook.getClass().getSimpleName(), HookPurity.violations(hook.getClass()))
                .isEmpty();
    }

    @Test
    @DisplayName("the purity check catches a hook that does reach for the filesystem")
    void catchesAnImpureHook() {
        // The contract test is only worth having if it can fail, so here is one that must.
        assertThat(HookPurity.violations(ImpureHook.class)).isNotEmpty();
    }

    @Test
    @DisplayName("the registry stays at or under the three §4 allows")
    void staysSmall() {
        // Not a technical limit. §4: if more than about three recipes need a hook, the manifest
        // format is missing a feature and should gain one instead — and the moment to have that
        // conversation is when this fails, not two hooks later.
        assertThat(RecipeHooks.all()).hasSizeLessThanOrEqualTo(RecipeHooks.MAX_HOOKS);
    }

    @Test
    @DisplayName("a recipe declaring hook: true with nothing registered is caught at plan time")
    void refusesAMissingHook() {
        Recipe claimsAHook = Recipe.implied(
                RecipeId.of("feature-invented"),
                RecipeVersion.parse("1.0.0"),
                null,
                RecipeKind.FEATURE,
                "Invented",
                Set.of(),
                Set.of(),
                Set.of(),
                List.of(),
                Set.of(),
                List.of(FileRule.always("files/**")),
                List.of(),
                true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        RecipeHooks.contributions(new PlanContext(List.of(claimsAHook), Map.of(), Map.of(), Set.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot supply one");
    }

    /** Exists solely so {@link #catchesAnImpureHook} has something to catch. */
    static final class ImpureHook implements RecipeHook {

        @Override
        public String recipeId() {
            return "base";
        }

        @Override
        public List<PatchOp> contribute(PlanContext context) {
            // Precisely the convenient mistake the check exists to find.
            Path somewhere = Path.of("/etc/passwd");
            return somewhere.toFile().exists() ? List.of() : List.of();
        }
    }
}
