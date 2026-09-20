package dev.kitbash.core.plan;

import dev.kitbash.core.recipe.RecipeId;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One file a recipe intends to contribute, before anything has been read or rendered.
 *
 * <p>The content is a supplier, not bytes. The plan stage has to know the whole shape of the
 * project — how many files, how large, whose they are, whether any path escapes — and enforce the
 * §13 caps *before* rendering cost is paid (§6). Holding bodies at plan time would defeat that and
 * would put a 50 MB project in memory twice.
 *
 * <p>{@code declaredSize} is what the cap arithmetic runs on: the source template's size on disk,
 * known without rendering. Rendering can grow a file, so the caps are re-checked afterwards; this
 * is the cheap check that rejects the obvious cases early.
 */
public record FileEntry(
        String path, Supplier<byte[]> content, FileMode mode, RecipeId owner, boolean templated, long declaredSize) {

    public FileEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(owner, "owner");
        if (path.isBlank()) {
            throw new IllegalArgumentException("file path must not be blank");
        }
    }

    /** A copy of this entry at a different path — what path templating produces. */
    public FileEntry at(String renderedPath) {
        return new FileEntry(renderedPath, content, mode, owner, templated, declaredSize);
    }

    public byte[] read() {
        byte[] bytes = content.get();
        return bytes == null ? new byte[0] : bytes;
    }
}
