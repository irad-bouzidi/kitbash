package dev.kitbash.api.generate;

import dev.kitbash.core.git.GitSkeletonWriter;
import dev.kitbash.core.selection.Identifiers;
import dev.kitbash.core.selection.Selection;
import dev.kitbash.core.workspace.GeneratedFile;
import dev.kitbash.core.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * PHASE-0 SCAFFOLDING — deleted by kitbash-13.
 *
 * <p>Turns a selection into a project by copying the reference project and replacing its known
 * names. There is no resolver, no recipe and no template engine: the point of this path is to find
 * the awkward parts of streaming a generated tree — modes, line endings, the git skeleton, download
 * headers — while everything else is still trivial. Phase 1 deletes this class and keeps the
 * plumbing underneath it.
 *
 * <p>What survives from this task lives in {@code core}: the workspace, the deterministic zip
 * writer and the git skeleton writer.
 */
@Component
public class Phase0ProjectGenerator {

    static final String COMMIT_MESSAGE = "Initial commit";

    /**
     * Files whose bytes are left alone. Detected by content rather than by extension, so a binary
     * added to the reference project later does not need this class to be edited.
     */
    private static boolean isBinary(byte[] content) {
        int inspected = Math.min(content.length, 8_000);
        for (int i = 0; i < inspected; i++) {
            if (content[i] == 0) {
                return true;
            }
        }
        return false;
    }

    public Workspace generate(Selection selection) {
        Variables variables = Variables.from(selection);
        LiteralSubstitution substitution = substitutionFor(variables);

        Workspace workspace = new Workspace();
        for (ReferenceProject.Entry entry : ReferenceProject.load()) {
            String path = substitution.apply(entry.path());
            byte[] content = entry.content();

            if (!isBinary(content)) {
                String text = new String(content, StandardCharsets.UTF_8);
                // Normalised before substitution so a CRLF checkout cannot change the output bytes.
                text = substitution.apply(text.replace("\r\n", "\n"));
                content = text.getBytes(StandardCharsets.UTF_8);
            }

            workspace.put(path, new GeneratedFile(content, entry.executable()));
        }

        GitSkeletonWriter.write(workspace, COMMIT_MESSAGE);
        return workspace;
    }

    /**
     * Ordered longest-first: every literal must come before any literal that is a prefix of it, or
     * the shorter rule wins and the longer one never fires.
     */
    private static LiteralSubstitution substitutionFor(Variables variables) {
        return new LiteralSubstitution()
                .rule("com/example/demo", variables.packagePath())
                .rule("com.example.demo", variables.packageName())
                .rule("com/example", variables.groupPath())
                .rule("com.example", variables.groupId())
                // The Java version is replaced only where it is a version, never as a bare "21".
                .rule("JavaLanguageVersion.of(21)", "JavaLanguageVersion.of(" + variables.javaVersion() + ")")
                .rule("temurin:21-jdk-alpine", "temurin:" + variables.javaVersion() + "-jdk-alpine")
                .rule("temurin:21-jre-alpine", "temurin:" + variables.javaVersion() + "-jre-alpine")
                .rule("DEMO_", variables.environmentPrefix() + "_")
                // Covers DemoApplication and DemoProperties together.
                .rule("Demo", variables.className())
                .rule("demo", variables.projectName());
    }

    /** The four honoured inputs, plus the names derived from them. */
    record Variables(String projectName, String groupId, String packageName, String javaVersion) {

        static Variables from(Selection selection) {
            String projectName = Identifiers.requireProjectName(selection.projectName());
            String groupId = Identifiers.requireGroupId(selection.variable("groupId", "com.example"));
            String packageName = Identifiers.requirePackageName(
                    selection.variable("packageName", groupId + "." + projectName.replace("-", "")));
            String javaVersion = Identifiers.requireJavaVersion(selection.variable("javaVersion", "21"));
            return new Variables(projectName, groupId, packageName, javaVersion);
        }

        String packagePath() {
            return packageName.replace('.', '/');
        }

        String groupPath() {
            return groupId.replace('.', '/');
        }

        /** {@code customer-management} becomes {@code CustomerManagement}. */
        String className() {
            return Arrays.stream(projectName.split("-"))
                    .filter(part -> !part.isEmpty())
                    .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1))
                    .reduce("", String::concat);
        }

        /** {@code customer-management} becomes {@code CUSTOMER_MANAGEMENT}. */
        String environmentPrefix() {
            return projectName.toUpperCase(Locale.ROOT).replace('-', '_');
        }
    }
}
