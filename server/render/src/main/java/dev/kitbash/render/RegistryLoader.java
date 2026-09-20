package dev.kitbash.render;

import io.pebbletemplates.pebble.error.LoaderException;
import io.pebbletemplates.pebble.loader.Loader;
import java.io.Reader;
import java.io.StringReader;

/**
 * A Pebble loader with no filesystem behind it.
 *
 * <p>Pebble's stock loaders resolve a template name against a classpath or a directory, which is
 * exactly the capability §13 says a generator must not hand to a template. This one resolves
 * against a {@link TemplateRegistry} and fails otherwise, so the blast radius of a hostile or
 * mistaken {@code include} is "template not found".
 *
 * <p>{@link #resolveRelativePath} returns the name unchanged rather than joining it to the
 * including template's directory: there are no directories here, and a relative path that could
 * walk anywhere is the traversal bug this design exists to not have.
 */
final class RegistryLoader implements Loader<String> {

    private final TemplateRegistry registry;

    RegistryLoader(TemplateRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Reader getReader(String cacheKey) {
        String name = nameOf(cacheKey);
        return new StringReader(registry.source(name)
                .orElseThrow(() -> new LoaderException(
                        null,
                        "No template named '" + name + "'. Templates resolve only against the recipe "
                                + "catalog; there is no filesystem behind this loader (plan §13).")));
    }

    @Override
    public void setCharset(String charset) {
        // Sources are already decoded strings by the time they reach the registry.
    }

    @Override
    public void setPrefix(String prefix) {
        // No prefix: a prefix only means something when names are paths, and these are not.
    }

    @Override
    public void setSuffix(String suffix) {
        // Likewise.
    }

    @Override
    public String resolveRelativePath(String relativePath, String anchorPath) {
        return relativePath;
    }

    @Override
    public String createCacheKey(String name) {
        return registry.cacheKey(name);
    }

    @Override
    public boolean resourceExists(String name) {
        return registry.contains(nameOf(name));
    }

    /** Cache keys are {@code name@hash}; everything else here works in names. */
    private static String nameOf(String cacheKey) {
        int at = cacheKey.lastIndexOf('@');
        return at < 0 ? cacheKey : cacheKey.substring(0, at);
    }
}
