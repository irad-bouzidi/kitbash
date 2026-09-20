package dev.kitbash.api.preview;

/**
 * One file's real rendered content (§9).
 *
 * <p>The <i>rendered</i> content, not the template — that distinction is the whole point of the
 * feature. A preview that showed {@code {{ packageName }}} would be showing the generator's
 * internals; what builds trust is seeing the Java somebody is about to be handed.
 *
 * @param content the text, or null when the file is binary
 * @param binary whether this file has no text form worth showing
 */
public record PreviewFile(String path, long bytes, boolean binary, String content) {}
