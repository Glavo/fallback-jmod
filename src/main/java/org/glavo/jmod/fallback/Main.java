package org.glavo.jmod.fallback;

import org.glavo.jmod.fallback.util.JmodUtils;
import org.glavo.jmod.fallback.util.Messages;
import org.glavo.jmod.fallback.util.ZipUtils;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.spi.ToolProvider;

public class Main {
    public static boolean debugOutput = Boolean.getBoolean("org.glavo.jmod.fallback.debug");

    public static void main(String[] args) throws Exception {

        if (args.length == 0) {
            showHelpMessage(System.err);
            System.exit(1);
        }

        Mode mode;
        switch (args[0]) {
            case "reduce":
                mode = Mode.REDUCE;
                break;
            case "restore":
                mode = Mode.RESTORE;
                break;
            case "jlink":
                mode = Mode.JLINK;
                break;
            default:
                printErrorMessage(Messages.getMessage("error.missing.mode", args[0]));
                System.exit(1);
                return;
        }

        printDebugMessage(() -> "Mode: " + mode.toString().toLowerCase(Locale.ROOT));

        if (mode == Mode.JLINK) {
            Optional<ToolProvider> jlink = ToolProvider.findFirst("jlink");
            jlink.ifPresentOrElse(tool -> {
                String[] jlinkArgs = Arrays.copyOfRange(args, 1, args.length);
                printDebugMessage(() -> "Run jlink with args: " + Arrays.toString(jlinkArgs));
                tool.run(System.out, System.err, jlinkArgs);
            }, () -> {
                printErrorMessage(Messages.getMessage("error.missing.jlink"));
                System.exit(1);
            });
            return;
        }

        Options options = handleOptions(mode, Arrays.copyOfRange(args, 1, args.length));

        switch (mode) {
            case REDUCE:
                reduce(options);
                break;
            case RESTORE:
                break;
            default:
                throw new AssertionError(mode);
        }
    }

    enum Mode {
        REDUCE,
        RESTORE,
        JLINK
    }

    public static void showHelpMessage(PrintStream out) {
        out.println("TODO: showHelpMessage"); // TODO
    }

    static final class Options {
        Path runtimePath;
        final Map<Path, Path> files = new LinkedHashMap<>(); // from file to target file
        // final List<String> includePatterns = new ArrayList<>();
        // final List<String> excludePatterns = new ArrayList<>();
    }

    static Options handleOptions(Mode mode, String[] args) {
        Options res = new Options();
        Path outputDir = null;
        Path runtimePath = null;

        int i = 0;
        loop:
        while (i < args.length) {
            String arg = args[i];
            switch (arg) {
                case "-d":
                    if (outputDir != null) {
                        printErrorMessageAndExit(Messages.getMessage("error.options.repeat", arg));
                    }
                    if (i == args.length - 1) {
                        printErrorMessageAndExit(Messages.getMessage("error.missing.arg"));
                    }
                    String outputDirName = args[++i];
                    try {
                        outputDir = Paths.get(outputDirName).toAbsolutePath().normalize();
                    } catch (InvalidPathException e) {
                        printErrorMessageAndExit(Messages.getMessage("error.missing.file", outputDirName));
                    }
                    //noinspection ConstantConditions
                    if (Files.exists(outputDir) && !Files.isDirectory(outputDir)) {
                        printErrorMessageAndExit(Messages.getMessage("error.file.not.directory", outputDirName));
                    }

                    break;
                case "-p":
                case "--runtime-path":
                    if (runtimePath != null) {
                        printErrorMessageAndExit(Messages.getMessage("error.options.repeat", arg));
                    }
                    if (i == args.length - 1) {
                        printErrorMessageAndExit(Messages.getMessage("error.missing.arg"));
                    }
                    String runtimePathName = args[++i];
                    try {
                        runtimePath = Paths.get(runtimePathName).toAbsolutePath().normalize();
                    } catch (InvalidPathException e) {
                        printErrorMessageAndExit(Messages.getMessage("error.missing.file", runtimePathName));
                    }
                    //noinspection ConstantConditions
                    if (Files.exists(runtimePath) && !Files.isDirectory(runtimePath)) {
                        printErrorMessageAndExit(Messages.getMessage("error.file.not.directory", runtimePathName));
                    }
                    break;
                    /*
                case "--include":
                    if (include != null) {
                        printErrorMessageAndExit(Messages.getMessage("error.options.repeat", arg));
                    }
                    if (i == args.length - 1) {
                        printErrorMessageAndExit(Messages.getMessage("error.missing.arg"));
                    }
                    include = args[++i];
                    break;
                case "--exclude":
                    if (exclude != null) {
                        printErrorMessageAndExit(Messages.getMessage("error.options.repeat", arg));
                    }
                    if (i == args.length - 1) {
                        printErrorMessageAndExit(Messages.getMessage("error.missing.arg"));
                    }
                    exclude = args[++i];
                    break;
                     */
                default:
                    break loop;
            }
            i++;
        }

        if (i == args.length) {
            printErrorMessageAndExit(Messages.getMessage("error.missing.inputs"));
        }

        try {
            while (i < args.length) {
                String file = args[i++];

                if (file.endsWith("/*") || file.endsWith("\\*") || file.equals("*")) { // wildcard
                    file = file.substring(0, file.length() - 1);

                    Path searchPath = Paths.get(file);
                    if (Files.exists(searchPath)) {
                        if (!Files.isDirectory(searchPath)) {
                            printErrorMessageAndExit(Messages.getMessage("error.file.not.directory", file));
                        }

                        try (DirectoryStream<Path> jmods = Files.newDirectoryStream(searchPath, "*.jmod")) {
                            for (Path jmod : jmods) {
                                if (!Files.isDirectory(jmod)) {
                                    Path targetFile = outputDir == null ? jmod : outputDir.resolve(jmod.getFileName());
                                    res.files.put(jmod, targetFile);
                                }
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                            System.exit(1);
                        }
                    }

                } else {
                    Path path = Paths.get(file).toAbsolutePath().normalize();
                    if (!Files.exists(path) || Files.isDirectory(path)) {
                        printErrorMessageAndExit(Messages.getMessage("error.missing.file", file));
                    }

                    Path targetFile = outputDir == null ? path : outputDir.resolve(path.getFileName());
                    res.files.put(path, targetFile);
                }
            }
        } catch (InvalidPathException e) {
            printErrorMessageAndExit(Messages.getMessage("error.invalid.path", e.getInput()));
        }

        if (res.files.isEmpty()) {
            printErrorMessageAndExit(Messages.getMessage("error.missing.inputs"));
        }

        if (runtimePath == null) {
            Path parent = null;

            for (Path path : res.files.keySet()) {
                if (parent == null) {
                    parent = path.getParent();
                } else {
                    Path thisParent = path.getParent();
                    if (!Objects.equals(parent, thisParent)) {
                        printErrorMessageAndExit(Messages.getMessage("error.missing.inputs"));
                    }
                }
            }

            runtimePath = Objects.requireNonNull(parent);
        }

        res.runtimePath = runtimePath;

        return res;
    }

    private static void reduce(Options options) throws IOException {
        for (Map.Entry<Path, Path> entry : options.files.entrySet()) {
            reduce(options.runtimePath, entry.getKey(), entry.getValue());
        }
    }

    private static void reduce(Path runtimePath, Path sourcePath, Path targetPath) throws IOException {
        printDebugMessage(() -> String.format("Reduce: [runtimePath=%s, sourcePath=%s, targetPath=%s]", runtimePath, sourcePath, targetPath));

        Path tempFile = Files.createTempFile("fallback-", ".jmod");
        Files.deleteIfExists(tempFile);

        try (FileSystem input = JmodUtils.open(sourcePath);
             FileSystem output = ZipUtils.openForWrite(tempFile)) {


        }

        try (BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(targetPath))) {
            JmodUtils.writeMagicNumber(output);
            Files.copy(tempFile, output);
        }
    }

    private static void printDebugMessage(String message) {
        if (debugOutput) {
            System.out.println("[DEBUG] " + message);
        }
    }

    private static void printDebugMessage(Supplier<String> message) {
        if (debugOutput) {
            printDebugMessage(message.get());
        }
    }

    private static void printErrorMessage(String message) {
        System.err.println(Messages.getMessage("error", message));
        if (debugOutput) {
            for (StackTraceElement stackTraceElement : Thread.currentThread().getStackTrace()) {
                System.err.println("[DEBUG] at " + stackTraceElement);
            }
        }
    }

    private static void printErrorMessageAndExit(String message) {
        printErrorMessage(message);
        System.exit(1);
    }
}
