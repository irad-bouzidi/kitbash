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

            Options:
              --selection <file>  The §7 selection envelope, as JSON.
              --out <dir>         Where to write. With --zip, the path of the zip to write.
              --zip               Write one deterministic zip instead of a directory.
              --catalog <dir>     A recipe tree other than the repository's, for local work.

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
}
