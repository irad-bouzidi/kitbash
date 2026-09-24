package dev.kitbash.api.contributed;

import dev.kitbash.catalog.contributed.ContributedRecipeRules;
import dev.kitbash.sandbox.Confinement;
import dev.kitbash.sandbox.SandboxedRenderer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Wires the two controls and the sandbox (§47, ADR 0004).
 *
 * <p>Boots whether or not contributed recipes are usable, and that is deliberate: the probe's
 * answer is logged at startup either way, so an operator learns that this host cannot confine them
 * from a boot line rather than from the first refused submission.
 */
@Configuration
public class ContributedRecipeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ContributedRecipeConfiguration.class);

    /**
     * The allowlist, from a file rather than from properties.
     *
     * <p>A property would put it in a deployment's configuration, where it is edited by whoever
     * is deploying and reviewed by nobody. §6.1's whole argument is that the list is a small
     * reviewable file that changes on a merge request, so it ships as one — overridable by path
     * for an operator who keeps theirs elsewhere, which still leaves it a file with a diff.
     */
    @Bean
    public ContributedRecipeRules contributedRecipeRules(
            ResourceLoader resources,
            @Value("${kitbash.contributed.allowlist:classpath:contributed-coordinate-allowlist.txt}") String location) {
        Set<String> allowed = read(resources.getResource(location));
        log.info("Contributed-recipe coordinate allowlist: {} entries from {}", allowed.size(), location);
        return new ContributedRecipeRules(allowed);
    }

    @Bean
    public Confinement confinement() {
        Confinement confinement = Confinement.probe();
        if (confinement.isAvailable()) {
            log.info("Contributed-recipe sandbox: confinement active ({})", confinement.reason());
        } else {
            // Warn rather than info: the feature is off, and it is off for a reason an operator
            // can act on. Shipped recipes are unaffected, and saying so here saves the question.
            log.warn(
                    "Contributed-recipe sandbox: UNAVAILABLE ({}). Contributed recipes will be refused "
                            + "rather than rendered unconfined. Shipped recipes are unaffected.",
                    confinement.reason());
        }
        return confinement;
    }

    @Bean
    public SandboxedRenderer sandboxedRenderer(
            Confinement confinement, @Value("${kitbash.contributed.worker-classpath:}") String classpath) {
        // Defaults to this JVM's own classpath. In the shipped image that is the application jar,
        // which is what the worker needs; the property exists for a deployment that lays the
        // classes out differently. Note that trimming it is not a security control — the network
        // namespace is what denies the database, not the absence of a driver.
        return new SandboxedRenderer(
                confinement, classpath.isBlank() ? System.getProperty("java.class.path") : classpath);
    }

    private static Set<String> read(Resource resource) {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            Set<String> coordinates = new LinkedHashSet<>();
            reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(coordinates::add);
            return Set.copyOf(coordinates);
        } catch (IOException e) {
            // Fails the boot rather than starting with an empty list. An empty allowlist refuses
            // every submission, which looks like the feature being broken rather than like the
            // control being missing — and the two want very different responses.
            throw new UncheckedIOException("The contributed-recipe allowlist could not be read", e);
        }
    }
}
