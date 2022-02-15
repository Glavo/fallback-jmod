package org.glavo.jmod.fallback;

import org.glavo.jmod.fallback.util.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Supplier;
import java.util.spi.ToolProvider;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jdk.internal.jimage.*;

public class Main {
    public static boolean debugOutput = Boolean.getBoolean("org.glavo.jmod.fallback.debug");
    public static final String FALLBACK_LIST_FILE_NAME = "fallback.list";

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
            case "-?":
            case "-help":
            case "--help":
                showHelpMessage(System.out);
                return;
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
                restore(options);
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
        Path jimagePath;
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
                case "-?":
                case "-help":
                case "--help":
                    showHelpMessage(System.out);
                    System.exit(0);
                    break;
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
                    parent = path.getParent().getParent();
                } else {
                    Path thisParent = path.getParent().getParent();
                    if (!Objects.equals(parent, thisParent)) {
                        printErrorMessageAndExit(Messages.getMessage("error.missing.inputs"));
                    }
                }
            }

            runtimePath = Objects.requireNonNull(parent);
        }

        Path jimagePath = runtimePath.resolve("lib").resolve("modules");
        if (Files.notExists(jimagePath) || Files.isDirectory(jimagePath)) {
            printErrorMessageAndExit(Messages.getMessage("error.missing.jimage"));
        }

        res.runtimePath = runtimePath;
        res.jimagePath = jimagePath;

        return res;
    }

    private static void reduce(Options options) throws IOException {
        try (ImageReader image = ImageReader.open(options.jimagePath)) {
            for (Map.Entry<Path, Path> entry : options.files.entrySet()) {
                try {
                    reduce(options.runtimePath, image, entry.getKey(), entry.getValue());
                } catch (Throwable ex) {
                    ex.printStackTrace();
                }
            }

        }
    }

    private static void reduce(Path runtimePath, ImageReader image, Path sourcePath, Path targetPath) throws IOException {
        printDebugMessage(() -> String.format("Reduce: [runtimePath=%s, sourcePath=%s, targetPath=%s]", runtimePath, sourcePath, targetPath));
        Path tempFile = targetPath.resolveSibling(targetPath.getFileName().toString() + ".tmp");

        boolean completed = false;

        try (FileSystem input = JmodUtils.open(sourcePath)) {
            Path fallbackList = input.getPath("/", JmodUtils.SECTION_CLASSES, FALLBACK_LIST_FILE_NAME);
            if (Files.exists(fallbackList)) {
                System.out.println(Messages.getMessage("info.already.fallback", sourcePath.getFileName()));
                return;
            }

            Path moduleInfo = input.getPath("/", JmodUtils.SECTION_CLASSES, "module-info.class");
            if (Files.notExists(moduleInfo)) {
                throw new FileNotFoundException(Messages.getMessage("error.missing.module_info", sourcePath.getFileName()));
            }

            String moduleName = ModuleNameFinder.findModuleName(moduleInfo);
            if (moduleName == null) {
                throw new IOException(Messages.getMessage("error.missing.module_name", sourcePath.getFileName()));
            }
            printDebugMessage(() -> "Module Name: " + moduleName);

            if (image.findNode("/modules/" + moduleName) == null) {
                System.out.println(Messages.getMessage("info.module_not_in_runtime_path", moduleName));
                return;
            }

            Path root = input.getPath("/");

            ReduceFileVisitor visitor = new ReduceFileVisitor(moduleName, runtimePath, image);
            Files.walkFileTree(root, visitor);

            SortedMap<Path, String> hash = visitor.getRecordedHash();
            if (hash.isEmpty()) {
                System.out.println(Messages.getMessage("info.module_not_in_runtime_path", moduleName));
                return;
            }

            try (BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(tempFile))) {
                JmodUtils.writeMagicNumber(output);
                try (ZipOutputStream zipOutput = new ZipOutputStream(output)) {
                    SimpleFileVisitor<Path> v = new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                            String dirName = dir.toString();
                            if (dirName.equals("/" + JmodUtils.SECTION_CLASSES)) {
                                zipOutput.putNextEntry(new ZipEntry(JmodUtils.SECTION_CLASSES + "/" + FALLBACK_LIST_FILE_NAME));
                                for (Map.Entry<Path, String> entry : hash.entrySet()) {
                                    zipOutput.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                                    zipOutput.write(' ');
                                    zipOutput.write(entry.getKey().toString().substring(1).getBytes(StandardCharsets.UTF_8));
                                    zipOutput.write('\n');
                                }
                                zipOutput.closeEntry();
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            if (!hash.containsKey(file)) {
                                String fileName = file.toString().substring(1);

                                ZipEntry entry = new ZipEntry(fileName);
                                // entry.setLastModifiedTime(attrs.lastModifiedTime());
                                // entry.setLastAccessTime(attrs.lastAccessTime());

                                zipOutput.putNextEntry(entry);
                                if (Files.isSymbolicLink(file)) {
                                    throw new IOException("Cannot process symbolic links");
                                } else {
                                    Files.copy(file, zipOutput);
                                }
                                zipOutput.closeEntry();
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    };

                    Files.walkFileTree(root, v);
                }

                completed = true;
            }
        } finally {
            try {
                if (completed) {
                    Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }

    }

    private static void restore(Options options) throws IOException {
        try (ImageReader image = ImageReader.open(options.jimagePath)) {
            for (Map.Entry<Path, Path> entry : options.files.entrySet()) {
                try {
                    restore(options.runtimePath, image, entry.getKey(), entry.getValue());
                } catch (Throwable ex) {
                    ex.printStackTrace();
                }
            }

        }
    }

    private static void restore(Path runtimePath, ImageReader image, Path sourcePath, Path targetPath) throws IOException {
        printDebugMessage(() -> String.format("Restore: [runtimePath=%s, sourcePath=%s, targetPath=%s]", runtimePath, sourcePath, targetPath));
        Path tempFile = targetPath.resolveSibling(targetPath.getFileName().toString() + ".tmp");
        Files.deleteIfExists(tempFile);

        boolean completed = false;

        try (FileSystem input = JmodUtils.open(sourcePath)) {

            Path fallbackList = input.getPath("/", JmodUtils.SECTION_CLASSES, FALLBACK_LIST_FILE_NAME);
            if (Files.notExists(fallbackList)) {
                System.out.println(Messages.getMessage("info.not.fallback", sourcePath.getFileName()));
                return;
            }

            Map<String, String> list = FallbackUtils.readFallbackList(fallbackList);

            Path moduleInfo = input.getPath("/", JmodUtils.SECTION_CLASSES, "/module-info.class");
            if (Files.notExists(moduleInfo)) {
                throw new FileNotFoundException(Messages.getMessage("error.missing.module_info", sourcePath.getFileName()));
            }

            String moduleName = ModuleNameFinder.findModuleName(moduleInfo);
            if (moduleName == null) {
                throw new IOException(Messages.getMessage("error.missing.module_name", sourcePath.getFileName()));
            }
            printDebugMessage(() -> "Module Name: " + moduleName);

            if (image.findNode("/modules/" + moduleName) == null) {
                System.out.println(Messages.getMessage("info.module_not_in_runtime_path", moduleName));
                return;
            }

            Path root = input.getPath("/");

            try (BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(tempFile))) {
                JmodUtils.writeMagicNumber(output);
                try (ZipOutputStream zipOutput = new ZipOutputStream(output)) {

                    for (Map.Entry<String, String> entry : list.entrySet()) {
                        String fileName = entry.getKey();
                        String hash = entry.getValue();

                        if (Files.exists(root.resolve(fileName))) {
                            continue;
                        }

                        if (fileName.startsWith(JmodUtils.SECTION_CLASSES)) {
                            ImageLocation location = image.findLocation(moduleName, fileName.substring(JmodUtils.SECTION_CLASSES.length() + 1));
                            if (location == null) {
                                throw new FileNotFoundException(fileName);
                            }

                            if (hash != null) {
                                String actualHash;
                                try (InputStream i = image.getResourceStream(location)) {
                                    actualHash = MessageDigestUtils.hash(i);
                                }

                                if (!hash.equals(actualHash)) {
                                    throw new IOException(Messages.getMessage("error.hash.mismatch", fileName));
                                }
                            }

                            ZipEntry zipEntry = new ZipEntry(fileName);
                            zipOutput.putNextEntry(zipEntry);
                            try (InputStream i = image.getResourceStream(location)) {
                                i.transferTo(zipOutput);
                            }
                            zipOutput.closeEntry();

                        } else {
                            Path runtimeFilePath;
                            if (fileName.startsWith(JmodUtils.SECTION_LIB) && fileName.toLowerCase(Locale.ROOT).endsWith(".dll")) {
                                runtimeFilePath = runtimePath.resolve(JmodUtils.SECTION_BIN + fileName.substring(JmodUtils.SECTION_LIB.length())).toAbsolutePath().normalize();
                            } else {
                                runtimeFilePath = runtimePath.resolve(fileName).toAbsolutePath().normalize();
                            }

                            if (!Files.isRegularFile(runtimeFilePath)) {
                                throw new FileNotFoundException(runtimeFilePath.toString());
                            }

                            if (hash != null) {
                                String actualHash = MessageDigestUtils.hash(runtimeFilePath);
                                if (!hash.equals(actualHash)) {
                                    throw new IOException(Messages.getMessage("error.hash.mismatch", fileName));
                                }
                            }

                            BasicFileAttributes attributes = Files.getFileAttributeView(runtimeFilePath, BasicFileAttributeView.class).readAttributes();

                            ZipEntry zipEntry = new ZipEntry(fileName);
                            //zipEntry.setLastAccessTime(attributes.lastAccessTime());
                            //zipEntry.setLastModifiedTime(attributes.lastModifiedTime());

                            zipOutput.putNextEntry(zipEntry);
                            Files.copy(runtimeFilePath, zipOutput);
                            zipOutput.closeEntry();
                        }

                    }

                    SimpleFileVisitor<Path> v = new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            if (!file.toString().equals("/" + JmodUtils.SECTION_CLASSES + "/" + FALLBACK_LIST_FILE_NAME)) {
                                String fileName = file.toString().substring(1);

                                ZipEntry entry = new ZipEntry(fileName);
                                entry.setLastModifiedTime(attrs.lastModifiedTime());
                                entry.setLastAccessTime(attrs.lastAccessTime());

                                zipOutput.putNextEntry(entry);
                                if (Files.isSymbolicLink(file)) {
                                    throw new IOException("Cannot process symbolic links");
                                } else {
                                    Files.copy(file, zipOutput);
                                }
                                zipOutput.closeEntry();
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    };

                    Files.walkFileTree(root, v);


                }

                completed = true;
            }

        } finally {
            try {
                if (completed) {
                    Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    public static void printDebugMessage(String message) {
        if (debugOutput) {
            System.out.println("[DEBUG] " + message);
        }
    }

    public static void printDebugMessage(Supplier<String> message) {
        if (debugOutput) {
            printDebugMessage(message.get());
        }
    }

    public static void printErrorMessage(String message) {
        System.err.println(Messages.getMessage("error", message));
        if (debugOutput) {
            for (StackTraceElement stackTraceElement : Thread.currentThread().getStackTrace()) {
                System.err.println("[DEBUG] at " + stackTraceElement);
            }
        }
    }

    public static void printErrorMessageAndExit(String message) {
        printErrorMessage(message);
        System.exit(1);
    }
}
