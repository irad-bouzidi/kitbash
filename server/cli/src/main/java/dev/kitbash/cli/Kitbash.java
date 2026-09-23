package dev.kitbash.cli;

import java.io.PrintStream;
import java.util.List;

/**
 * The generator with no server, no database and no Spring context.
 *
 * <p>§12 has every verification cell call the generator through {@code cli} rather than over HTTP,
 * and that is not a convenience: it keeps verification independent of the API, its auth and its
 * persistence, so a red cell means the generator is broken rather than the deployment.
 *
 * <p>It also keeps the §6 module boundary honest. This module depends on {@code core}, {@code
 * catalog} and {@code render} and nothing else — a Spring dependency appearing here is a build
 * failure — so if the CLI can generate a project, the domain really is framework-free.
 *
 * <p>Argument parsing is deliberately thin. Everything below is a shell around the same pipeline
 * functions the API calls; logic that appears here rather than in {@code core} will eventually
 * differ between the two surfaces, and the one place that would show is a user's download.
 */
public final class Kitbash {

    private static final String USAGE =
            """
            kitbash — generate a project that already builds

            Usage:
              kitbash generate --selection <file> --out <dir> [--zip] [--catalog <dir>]
              kitbash validate --selection <file> [--catalog <dir>]
              kitbash catalog --json [--catalog <dir>]
              kitbash recipe check <id> [--catalog <dir>]
              kitbash recipe new <id> --from <reference-project> [--catalog <dir>]
              kitbash --version

            Options:
              --selection <file>  The §7 selection envelope, as JSON.
              --out <dir>         Where to write. With --zip, the path of the zip to write.
              --zip               Write one deterministic zip instead of a directory.
              --catalog <dir>     A recipe tree other than the repository's, for local work.
              --from <project>    The reference project to derive a new recipe from (§4).

            Exit codes:
              0  success
              1  the selection was refused; stderr carries the §14 error envelope as JSON
              2  the command line was wrong
            """;

    private Kitbash() {}

    public static void main(String[] args) {
        System.exit(run(List.of(args), System.out, System.err));
    }

    /** The whole CLI as a function, so the tests do not have to fork a JVM to read its output. */
    public static int run(List<String> args, PrintStream out, PrintStream err) {
        if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
            out.print(USAGE);
            return args.isEmpty() ? 2 : 0;
        }

        if (args.contains("--version")) {
            return version(args, out, err);
        }

        // Parsed before Arguments, because `recipe` takes a sub-command and a bare id rather than
        // the flag-only shape everything else has. Two grammars in one parser would make the one
        // people use every day harder to read.
        if (args.get(0).equals("recipe")) {
            return recipe(args, out, err);
        }

        Arguments arguments;
        try {
            arguments = Arguments.parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println();
            err.print(USAGE);
            return 2;
        }

        return switch (arguments.command()) {
            case GENERATE -> new GenerateCommand().run(arguments, out, err);
            case VALIDATE -> new ValidateCommand().run(arguments, out, err);
            case CATALOG -> new CatalogCommand().run(arguments, out, err);
        };
    }

    /**
     * Both halves of what this binary is (§8, §42).
     *
     * <p>The tool version alone does not identify a build: a CLI carries its catalog, so two
     * installs of the same version built from different commits generate different projects. §8
     * exposes the digest for the same reason the API does — it is what makes a bug report
     * actionable — and §42 adds that a stale binary emitting a stale catalog should be visible
     * rather than surprising.
     *
     * <p>The digest is computed from the recipes this run would actually use, including whatever
     * {@code --catalog} points at. A digest recorded at build time would describe what was intended
     * rather than what is there, and the two differ exactly when somebody needs to know.
     */
    private static int version(List<String> args, PrintStream out, PrintStream err) {
        try {
            Arguments arguments = Arguments.parseCatalogOnly(args);
            java.nio.file.Path catalog = CatalogLocator.locate(arguments);
            out.print(Distribution.describe(
                    CatalogLocator.load(arguments).catalog().digest(), catalog));
            return 0;
        } catch (RuntimeException noCatalog) {
            // Broadly, on purpose. A catalog can fail to load in more ways than it can fail to be
            // found — a malformed manifest throws from `catalog`, not from here — and §39 is
            // absolute that no user-facing stack traces, ever. `--version` is the command somebody
            // runs *because* something is wrong, so it is the last place to hand them one.
            //
            // The version is still worth printing: "which binary is this" is answerable even when
            // "which catalog does it carry" is not, and refusing both withholds the half that works.
            out.printf("kitbash %s%ncatalog unavailable: %s%n", Distribution.version(), noCatalog.getMessage());
            return 1;
        }
    }

    /**
     * {@code kitbash recipe …} — the authoring SDK's half of the CLI (§43).
     *
     * <p>Separate from the three generation commands because the audience is: those are for
     * somebody who wants a project, these are for somebody writing the recipes that produce one.
     * Sharing the binary is what makes the second audience's tools give the same verdicts as the
     * first audience's generator, which is the whole requirement §43 sets.
     */
    private static int recipe(List<String> args, PrintStream out, PrintStream err) {
        if (args.size() < 3) {
            err.println("Usage: kitbash recipe check <id> | kitbash recipe new <id> --from <reference-project>");
            return 2;
        }
        String subcommand = args.get(1);
        String id = args.get(2);
        List<String> rest = args.subList(3, args.size());

        try {
            Arguments arguments = Arguments.parseCatalogOnly(rest);
            return switch (subcommand) {
                case "check" -> RecipeCheck.run(arguments, id, out, err);
                case "new" -> RecipeScaffold.run(arguments, id, from(rest), out, err);
                default -> {
                    err.printf("Unknown recipe command '%s'. Expected check or new.%n", subcommand);
                    yield 2;
                }
            };
        } catch (IllegalArgumentException wrong) {
            err.println(wrong.getMessage());
            return 2;
        }
    }

    private static String from(List<String> args) {
        int index = args.indexOf("--from");
        if (index < 0 || index + 1 >= args.size()) {
            throw new IllegalArgumentException(
                    "--from is required for `recipe new`: name the reference project to derive from. "
                            + "§4's workflow is to start from a project somebody maintains by hand.");
        }
        return args.get(index + 1);
    }
}
