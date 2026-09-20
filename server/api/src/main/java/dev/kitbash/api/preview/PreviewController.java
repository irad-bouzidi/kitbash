package dev.kitbash.api.preview;

import dev.kitbash.api.generate.GenerateRequest;
import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.hash.Sha256;
import dev.kitbash.core.pipeline.GenerationPipeline;
import dev.kitbash.core.selection.Selection;
import dev.kitbash.core.selection.SelectionEnvelope;
import dev.kitbash.core.workspace.GeneratedFile;
import dev.kitbash.core.workspace.Workspace;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/preview} — see the tree before downloading it (§6, §8, §9).
 *
 * <p>The user value is trust, and it is worth naming because it is the reason this exists at all:
 * a generator you cannot look inside is one you try once; a generator whose output you can inspect
 * before committing to it is one a team standardises on.
 *
 * <p>It is cheap because §6 split the pipeline into stages precisely so that this could be a
 * <i>partial run</i> rather than a second implementation. Stages 1–5 and stop: no packaging, no
 * git skeleton, no history row, nothing persisted. The same §13 caps apply, enforced at the plan
 * stage as they are for a real generation, because a preview is the same work minus the zip.
 */
@RestController
@RequestMapping("/api/v1")
public class PreviewController {

    private final GenerationPipeline pipeline;
    private final PreviewCache cache;

    public PreviewController(GenerationPipeline pipeline, PreviewCache cache) {
        this.pipeline = pipeline;
        this.cache = cache;
    }

    /** The tree: every path this selection produces, with its size. */
    @PostMapping("/preview")
    public PreviewTree tree(@RequestBody GenerateRequest request) {
        SelectionEnvelope envelope = request.toEnvelope();
        Selection selection = pipeline.parse(envelope);
        return tree(selection, cache.get(key(selection), () -> pipeline.preview(envelope)));
    }

    /**
     * One file's real rendered content.
     *
     * <p>§8 describes this as {@code ?path=} on the endpoint above, and it is a sub-path instead
     * for one reason: OpenAPI keys an operation by path and method, so one path serving two shapes
     * can only be documented as {@code Object} — and §9 generates the web client's types from that
     * document. A URL shape is worth less than a client that stops compiling when a DTO changes.
     *
     * <p>Everything else about it is as specified: the same body, the same cached render, and a
     * file fetched only when somebody asks for it.
     */
    @PostMapping("/preview/file")
    public PreviewFile file(@RequestBody GenerateRequest request, @RequestParam("path") String path) {
        SelectionEnvelope envelope = request.toEnvelope();
        Selection selection = pipeline.parse(envelope);
        return file(cache.get(key(selection), () -> pipeline.preview(envelope)), path);
    }

    private PreviewTree tree(Selection selection, Workspace workspace) {
        List<PreviewTree.Entry> entries = new ArrayList<>();
        workspace
                .files()
                .forEach((path, file) -> entries.add(new PreviewTree.Entry(path, file.size(), isBinary(file))));

        return new PreviewTree(
                selection.projectName(),
                pipeline.catalog().digest(),
                selection.hash(),
                workspace.fileCount(),
                workspace.totalBytes(),
                List.copyOf(entries));
    }

    private PreviewFile file(Workspace workspace, String path) {
        GeneratedFile file = workspace.get(path);
        if (file == null) {
            throw GenerationError.invalidIdentifier(
                            "path",
                            "'" + path + "'",
                            "is not a file this selection produces",
                            "Ask for the tree first: POST /api/v1/preview with the same body lists "
                                    + "every file this selection produces.")
                    .asException();
        }

        boolean binary = isBinary(file);
        return new PreviewFile(
                path, file.size(), binary, binary ? null : new String(file.content(), StandardCharsets.UTF_8));
    }

    /**
     * Whether a file has a text form worth showing.
     *
     * <p>Decided by decoding rather than by extension, so a {@code .properties} full of bytes is
     * binary and a file with no extension at all is not automatically suspect. §26 asks for binary
     * files to be reported as binary with their size and never streamed as text, and the honest
     * test for "is this text" is whether it decodes as text.
     */
    private static boolean isBinary(GeneratedFile file) {
        byte[] content = file.content();
        for (byte b : content) {
            // A NUL is the one byte no text file contains, and the cheapest thing to look for.
            if (b == 0) {
                return true;
            }
        }
        try {
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(content));
            return false;
        } catch (CharacterCodingException notText) {
            return true;
        }
    }

    /** §10's key: the catalog and the canonical selection, since either changes what is rendered. */
    private String key(Selection selection) {
        return Sha256.ofUtf8(pipeline.catalog().digest() + "\u0000" + selection.hash());
    }

    /**
     * A handful of recent previews, kept so that clicking a file does not re-run the pipeline.
     *
     * <p>§26 suggests caching the <i>plan</i> and re-rendering one file per click. This caches the
     * rendered workspace instead, which is simpler and — at the sizes involved — cheaper: a
     * preview is the same few hundred kilobytes §2 already holds in memory for a generation, and
     * re-rendering a single file would mean a second render path to keep in step with the first.
     * Sixteen of them is a few megabytes; the bound is what stops a busy afternoon from becoming
     * a leak.
     *
     * <p>Deliberately not the zip cache ({@code kitbash-27}): that one is durable, shared and
     * keyed for artifacts. This is a scratchpad for a page somebody has open.
     */
    @org.springframework.stereotype.Component
    static class PreviewCache {

        private static final int KEEP = 16;

        private final Map<String, Workspace> recent =
                java.util.Collections.synchronizedMap(new LinkedHashMap<>(KEEP + 1, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Workspace> eldest) {
                        return size() > KEEP;
                    }
                });

        Workspace get(String key, java.util.function.Supplier<Workspace> render) {
            Workspace cached = recent.get(key);
            if (cached != null) {
                return cached;
            }
            // Rendered outside the map's lock: holding it across a render would serialise every
            // preview in the service behind the slowest one.
            Workspace rendered = render.get();
            recent.put(key, rendered);
            return rendered;
        }
    }
}
