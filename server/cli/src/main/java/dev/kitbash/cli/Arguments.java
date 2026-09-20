package dev.kitbash.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The command line, parsed. Nothing here decides anything; it only reads.
 *
 * <p>Hand-rolled rather than pulled from a library, because the surface is three commands and four
 * flags and a dependency would be larger than the code it replaced. If interactive prompting ever
 * lands ({@code kitbash-44}) that calculation changes.
 */
record Arguments(Command command, Path selection, Path out, boolean zip, Path catalog, boolean json) {

    enum Command {
        GENERATE,
        VALIDATE,
        CATALOG
    }

    static Arguments parse(List<String> args) {
        Command command =
                switch (args.get(0)) {
                    case "generate" -> Command.GENERATE;
                    case "validate" -> Command.VALIDATE;
                    case "catalog" -> Command.CATALOG;
                    default ->
                        throw new IllegalArgumentException(
                                "Unknown command '" + args.get(0) + "'. Expected generate, validate or catalog.");
                };

        Path selection = null;
        Path out = null;
        Path catalog = null;
        boolean zip = false;
        boolean json = false;

        for (int i = 1; i < args.size(); i++) {
            String flag = args.get(i);
            switch (flag) {
                case "--selection" -> selection = Path.of(value(args, ++i, flag));
                case "--out" -> out = Path.of(value(args, ++i, flag));
                case "--catalog" -> catalog = Path.of(value(args, ++i, flag));
                case "--zip" -> zip = true;
                case "--json" -> json = true;
                default -> throw new IllegalArgumentException("Unknown option '" + flag + "'.");
            }
        }

        if (command != Command.CATALOG && selection == null) {
            throw new IllegalArgumentException("--selection is required for " + args.get(0) + ".");
        }
        if (command == Command.GENERATE && out == null) {
            throw new IllegalArgumentException("--out is required for generate.");
        }
        if (selection != null && !Files.isRegularFile(selection)) {
            throw new IllegalArgumentException("No selection file at " + selection.toAbsolutePath() + ".");
        }
        if (catalog != null && !Files.isDirectory(catalog)) {
            throw new IllegalArgumentException("No recipe tree at " + catalog.toAbsolutePath() + ".");
        }

        return new Arguments(command, selection, out, zip, catalog, json);
    }

    private static String value(List<String> args, int index, String flag) {
        if (index >= args.size()) {
            throw new IllegalArgumentException(flag + " needs a value.");
        }
        return args.get(index);
    }

    Optional<Path> catalogPath() {
        return Optional.ofNullable(catalog);
    }
}
