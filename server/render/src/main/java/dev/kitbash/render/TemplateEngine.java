package dev.kitbash.render;

import dev.kitbash.core.error.GenerationError;
import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.attributes.methodaccess.MethodAccessValidator;
import io.pebbletemplates.pebble.cache.PebbleCache;
import io.pebbletemplates.pebble.cache.template.ConcurrentMapTemplateCache;
import io.pebbletemplates.pebble.error.PebbleException;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Pebble, with everything a code generator does not need taken away (§13).
 *
 * <p>Pebble was chosen over Thymeleaf because the output here is source code rather than HTML, and
 * over logic-less engines because those fight you the moment a template needs a conditional (§2,
 * §5). That power is precisely why it has to be fenced in: a generator writes strings into files
 * somebody is then going to execute.
 *
 * <p>What is switched off, and why:
 *
 * <ul>
 *   <li><b>All method access.</b> {@code isMethodAccessAllowed} returns false unconditionally, so
 *       {@code {{ ''.getClass() }}} and every other route from a value to the JVM is closed. Pebble
 *       ships a blacklist validator; a blacklist is a list of the attacks somebody already thought
 *       of, and there is nothing in the variable map worth calling a method on anyway.
 *   <li><b>The filesystem.</b> Templates resolve against a {@link TemplateRegistry} and nothing
 *       else, so an {@code include} has no path to traverse — see {@link RegistryLoader}.
 *   <li><b>Auto-escaping.</b> HTML escaping in generated Java is corruption, not safety.
 *   <li><b>Lenient variables.</b> {@code strictVariables} is on: a typo in a variable name becomes
 *       a typed {@code RENDER_FAILED} naming the line, instead of a silently empty package
 *       declaration that fails at the user's first compile.
 * </ul>
 *
 * <p>The parsed-template cache is shared across engines and keyed on {@code name@contentHash}, so
 * it is content-addressed: an edited template cannot be served from cache, and two engines over
 * different registries cannot collide. That is the §9 requirement — cache on content, never on
 * mutable state — met by construction rather than by remembering to flush.
 */
public final class TemplateEngine {

    private static final PebbleCache<Object, PebbleTemplate> TEMPLATE_CACHE = new ConcurrentMapTemplateCache();

    /** Denies every method call. Not a blacklist: there is nothing here worth calling. */
    private static final MethodAccessValidator DENY_ALL = new MethodAccessValidator() {
        @Override
        public boolean isMethodAccessAllowed(Object object, Method method) {
            return false;
        }
    };

    private final PebbleEngine engine;
    private final TemplateRegistry registry;

    private TemplateEngine(PebbleEngine engine, TemplateRegistry registry) {
        this.engine = engine;
        this.registry = registry;
    }

    public static TemplateEngine over(TemplateRegistry registry) {
        return new TemplateEngine(build(registry), registry);
    }

    private static PebbleEngine build(TemplateRegistry registry) {
        return new PebbleEngine.Builder()
                .loader(new RegistryLoader(registry))
                .extension(new KitbashFilters())
                .methodAccessValidator(DENY_ALL)
                .strictVariables(true)
                .autoEscaping(false)
                .newLineTrimming(false)
                .defaultLocale(Locale.ROOT)
                .cacheActive(true)
                .templateCache(TEMPLATE_CACHE)
                .build();
    }

    public TemplateRegistry registry() {
        return registry;
    }

    /**
     * Renders one registered template. Failures come back as {@code RENDER_FAILED} carrying the
     * recipe, the template and the line — never a raw Pebble stack trace, which tells a user
     * nothing they can act on (§14).
     */
    public String render(String templateName, String recipeId, TemplateVariables variables) {
        return render(registry, templateName, recipeId, variables);
    }

    /**
     * Renders against a registry other than the one this engine was built over.
     *
     * <p>Patch strings are known only after the plan stage, so they cannot be in the boot-time
     * registry. A throwaway engine for each of them would lose the shared parsed-template cache,
     * which is content-addressed and therefore safe to reuse — so the loader is swapped instead.
     */
    public String render(TemplateRegistry registry, String templateName, String recipeId, TemplateVariables variables) {
        PebbleEngine forRegistry = registry == this.registry ? engine : build(registry);
        try {
            PebbleTemplate template = forRegistry.getTemplate(templateName);
            StringWriter out = new StringWriter();
            template.evaluate(out, variables.asMap(), Locale.ROOT);
            return out.toString();
        } catch (PebbleException e) {
            throw GenerationError.renderFailed(
                            recipeId,
                            templateName,
                            e.getLineNumber() == null ? 0 : e.getLineNumber(),
                            e.getPebbleMessage() == null ? e.getMessage() : e.getPebbleMessage())
                    .asException();
        } catch (IOException e) {
            // A StringWriter does not do I/O; this is unreachable short of an OOM surfacing here.
            throw new UncheckedIOException("Rendering " + templateName + " failed unexpectedly", e);
        }
    }
}
