package dev.kitbash.core.recipe;

import dev.kitbash.core.patch.PatchOp;
import java.util.Objects;

/**
 * A patch a recipe contributes, plus the condition under which it does.
 *
 * <p>The condition lives here rather than on {@link PatchOp} deliberately: by the time an op
 * reaches an applier it is unconditional, so no applier has to know that conditions exist at all.
 * The plan stage is the only place {@code when} is ever read (§6).
 */
public record PatchRule(PatchOp op, String when) {

    public PatchRule {
        Objects.requireNonNull(op, "op");
        when = when == null || when.isBlank() ? FileRule.ALWAYS : when;
    }

    public static PatchRule always(PatchOp op) {
        return new PatchRule(op, FileRule.ALWAYS);
    }

    public boolean unconditional() {
        return FileRule.ALWAYS.equals(when);
    }
}
