package org.glavo.jmod.fallback;

import jdk.internal.module.ModulePath;
import jdk.tools.jlink.internal.Jlink;
import jdk.tools.jlink.builder.*;
import org.glavo.jmod.fallback.jlink.FallbackJmodPlugin;
import org.glavo.jmod.fallback.util.*;

import java.io.*;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jdk.internal.jimage.*;

public class Main {
    public static boolean debugOutput = Boolean.getBoolean("org.glavo.jmod.fallback.debug");
    public static final String FALLBACK_LIST_FILE_NAME = "fallback.list";

    public static void main(String[] args) throws Throwable {

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

        Options options = handleOptions(mode, Arrays.copyOfRange(args, 1, args.length));

        switch (mode) {
            case JLINK:
                jlink(options);
                break;
            case REDUCE:
                reduce(options);
                break;
            case RESTORE:
                restore(options);
                break;
            default:
                throw new AssertionError(mode);
        }

        System.out.println(Messages.getMessage("message.done"));
    }

    enum Mode {
        REDUCE,
        RESTORE,
        JLINK
    }

    enum Status {
        INCOMPLETE,
        COMPLETED,
        SKIP
    }

    public static void showHelpMessage(PrintStream out) {
        out.println(Messages.getMessage("message.help"));
    }

    static Options handleOptions(Mode mode, String[] args) throws IOException {
        Options res = new Options();
        Path outputDir = null;
        Path runtimePath = null;
        String withoutVerify = null;
        String exclude = null;

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
                case "--output":
                case "-d":
                    if (outputDir != null) {
                        printErrorMessageAndExit(Messages.getMessage("error.repeat.options", arg));
                    }
                    if (i == args.length - 1) {
                        printErrorMessageAndExit(Messages.getMessage("error.missing.arg", arg));
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
                        printErrorMessageAndExit(Messages.getMessage("error.repeat.options", arg));
                    }
                    if (i == args.length - 1) {
                        printErrorMessageAndExit(Messages.getMessage("error.missing.arg", arg));
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
                case "--include-without-verify":
                    if (withoutVerify != null) {
                        printErrorMessageAndExit(Messages.getMessage("error.options.repeat", arg));
                    }
                    if (i == args.length - 1) {
                        printErrorMessageAndExit(Messages.getMessage("error.missing.arg"));
                    }
                    withoutVerify = args[++i];
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
                default:
                    break loop;
            }
            i++;
        }

        if (i == args.length) {
            printErrorMessageAndExit(Messages.getMessage("error.missing.inputs"));
        }

        if (mode == Mode.JLINK) {
            if (outputDir == null) {
                printErrorMessageAndExit(Messages.getMessage("error.missing.outputs"));
            }
            //noinspection ConstantConditions
            if (Files.exists(outputDir)) {
                if (!Files.isDirectory(outputDir)) {
                    printErrorMessageAndExit(Messages.getMessage("error.target.already.exists", outputDir));
                }
                try (Stream<Path> stream = Files.list(outputDir)) {
                    if (stream.findAny().isPresent()) {
                        printErrorMessageAndExit(Messages.getMessage("error.target.already.exists", outputDir));
                    }
                }
            }

            res.targetDir = outputDir;
        }

        if (outputDir != null) {
            Files.createDirectories(outputDir);
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
                                    Path targetFile = null;
                                    if (mode != Mode.JLINK) {
                                        targetFile = outputDir == null ? jmod : outputDir.resolve(jmod.getFileName());
                                    }
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

                    Path targetFile = null;
                    if (mode != Mode.JLINK){
                        targetFile = outputDir == null ? path : outputDir.resolve(path.getFileName());
                    }
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

        if (exclude != null) {
            res.excludePatterns.addAll(Arrays.asList(exclude.split(":")));
        }

        if (withoutVerify != null) {
            res.withoutVerifyPatterns.addAll(Arrays.asList(withoutVerify.split(":")));
        }

        return res;
    }

    private static void reduce(Options options) throws IOException {
        try (ImageReader image = ImageReader.open(options.jimagePath)) {
            for (Map.Entry<Path, Path> entry : options.files.entrySet()) {
                try {
                    reduce(options, options.runtimePath, image, entry.getKey(), entry.getValue());
                } catch (Throwable ex) {
                    ex.printStackTrace();
                }
            }

        }
    }

    private static void reduce(Options options, Path runtimePath, ImageReader image, Path sourcePath, Path targetPath) throws
            IOException {
        printDebugMessage(() -> String.format("Reduce: [runtimePath=%s, sourcePath=%s, targetPath=%s]", runtimePath, sourcePath, targetPath));
        Path tempFile = targetPath.resolveSibling(targetPath.getFileName().toString() + ".tmp");

        Status status = Status.INCOMPLETE;

        try (FileSystem input = JmodUtils.open(sourcePath)) {
            Path fallbackList = input.getPath("/", JmodUtils.SECTION_CLASSES, FALLBACK_LIST_FILE_NAME);
            if (Files.exists(fallbackList)) {
                System.out.println(Messages.getMessage("info.already.fallback", sourcePath.getFileName()));
                return;
            }

            Path moduleInfo = input.getPath("/", JmodUtils.SECTION_CLASSES, "module-info.class");
            if (Files.notExists(moduleInfo)) {
                printErrorMessage(Messages.getMessage("error.missing.module_info", sourcePath.getFileName()));
                status = Status.SKIP;
                return;
            }

            String moduleName = ModuleNameFinder.findModuleName(moduleInfo);
            if (moduleName == null) {
                status = Status.SKIP;
                printErrorMessage(Messages.getMessage("error.missing.module_name", sourcePath.getFileName()));
                return;
            }
            printDebugMessage(() -> "Module Name: " + moduleName);

            byte[] filteredModuleInfo = ModuleHashesUtils.filter(moduleInfo);

            if (image.findNode("/modules/" + moduleName) == null) {
                System.out.println(Messages.getMessage("info.module_not_in_runtime_path", moduleName));
                status = Status.SKIP;
                return;
            }

            Path root = input.getPath("/");

            ReduceFileVisitor visitor = new ReduceFileVisitor(moduleName, runtimePath, image);

            for (String excludePattern : options.excludePatterns) {
                visitor.excludePatterns.add(input.getPathMatcher("glob:" + excludePattern));
            }

            for (String withoutVerifyPattern : options.withoutVerifyPatterns) {
                visitor.withoutVerifyPatterns.add(input.getPathMatcher("glob:" + withoutVerifyPattern));
            }

            Files.walkFileTree(root, visitor);

            SortedMap<Path, String> hash = visitor.getRecordedHash();
            if (hash.isEmpty()) {
                System.out.println(Messages.getMessage("info.module_not_in_runtime_path", moduleName));
                status = Status.SKIP;
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
                                    if (entry.getValue().isEmpty()) {
                                        zipOutput.write('-');
                                    } else {
                                        zipOutput.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                                    }

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
                                if (fileName.equals(JmodUtils.SECTION_CLASSES + "/module-info.class")) {
                                    zipOutput.write(filteredModuleInfo);
                                } else {
                                    if (Files.isSymbolicLink(file)) {
                                        throw new IOException("Cannot process symbolic links");
                                    } else {
                                        Files.copy(file, zipOutput);
                                    }
                                }

                                zipOutput.closeEntry();
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    };

                    Files.walkFileTree(root, v);
                }

                status = Status.COMPLETED;
            }
        } finally {
            try {
                if (status == Status.COMPLETED) {
                    Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
                } else if (status == Status.SKIP) {
                    if (!Files.exists(targetPath) || !Files.isSameFile(sourcePath, targetPath)) {
                        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
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

    private static void restore(Path runtimePath, ImageReader image, Path sourcePath, Path targetPath) throws
            IOException {
        printDebugMessage(() -> String.format("Restore: [runtimePath=%s, sourcePath=%s, targetPath=%s]", runtimePath, sourcePath, targetPath));
        Path tempFile = targetPath.resolveSibling(targetPath.getFileName().toString() + ".tmp");
        Files.deleteIfExists(tempFile);

        Status status = Status.INCOMPLETE;

        try (FileSystem input = JmodUtils.open(sourcePath)) {

            Path fallbackList = input.getPath("/", JmodUtils.SECTION_CLASSES, FALLBACK_LIST_FILE_NAME);
            if (Files.notExists(fallbackList)) {
                System.out.println(Messages.getMessage("info.not.fallback", sourcePath.getFileName()));
                status = Status.SKIP;
                return;
            }

            Map<String, String> list = FallbackUtils.readFallbackList(fallbackList);

            Path moduleInfo = input.getPath("/", JmodUtils.SECTION_CLASSES, "/module-info.class");
            if (Files.notExists(moduleInfo)) {
                printErrorMessage(Messages.getMessage("error.missing.module_info", sourcePath.getFileName()));
                return;
            }

            String moduleName = ModuleNameFinder.findModuleName(moduleInfo);
            if (moduleName == null) {
                printErrorMessage(Messages.getMessage("error.missing.module_name", sourcePath.getFileName()));
                return;
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
                                    throw new IOException(Messages.getMessage("error.mismatch.hash", fileName));
                                }
                            }

                            ZipEntry zipEntry = new ZipEntry(fileName);
                            zipOutput.putNextEntry(zipEntry);
                            try (InputStream i = image.getResourceStream(location)) {
                                i.transferTo(zipOutput);
                            }
                            zipOutput.closeEntry();

                        } else {
                            Path runtimeFilePath = runtimePath.resolve(JmodUtils.mapToRuntimePath(fileName));

                            if (!Files.isRegularFile(runtimeFilePath)) {
                                throw new FileNotFoundException(runtimeFilePath.toString());
                            }

                            if (hash != null) {
                                String actualHash = MessageDigestUtils.hash(runtimeFilePath);
                                if (!hash.equals(actualHash)) {
                                    throw new IOException(Messages.getMessage("error.mismatch.hash", fileName));
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

                status = Status.COMPLETED;
            }

        } finally {
            try {
                if (status == Status.COMPLETED) {
                    Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
                } else if (status == Status.SKIP) {
                    if (!Files.exists(targetPath) || !Files.isSameFile(sourcePath, targetPath)) {
                        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    private static void jlink(Options options) throws IOException {

        Path[] jmods = options.files.keySet().toArray(Path[]::new);

        Set<String> moduleNames = new LinkedHashSet<>();

        for (Path jmod : jmods) {
            try (FileSystem fs = JmodUtils.open(jmod)) {
                Path path = fs.getPath("/classes/module-info.class");
                if (!Files.isRegularFile(path)) {
                    throw new IOException(Messages.getMessage("error.missing.module_info", jmod.getFileName()));
                }
                String moduleName = ModuleNameFinder.findModuleName(path);
                if (moduleName == null) {
                    throw new IOException(Messages.getMessage("error.missing.module_name", jmod.getFileName()));
                }

                if (!moduleNames.add(moduleName)) {
                    throw new IOException(Messages.getMessage("error.repeat.module", moduleName));
                }
            }
        }

        ModuleFinder finder = ModulePath.of(Runtime.version(), true, jmods);
        ModuleReference baseModule = finder.find("java.base")
                .orElseThrow(() -> new IllegalArgumentException(Messages.getMessage("error.missing.base")));

        Runtime.Version baseVersion =
                Runtime.Version.parse(baseModule.descriptor().version()
                        .orElseThrow(() -> new IllegalArgumentException("No version in java.base descriptor")).toString());

        if (Runtime.version().feature() != baseVersion.feature() ||
                Runtime.version().interim() != baseVersion.interim()) {
            printErrorMessageAndExit(Messages.getMessage("error.mismatch.java.version", baseVersion, Runtime.version()));
        }

        Jlink.JlinkConfiguration configuration = new Jlink.JlinkConfiguration(
                options.targetDir, moduleNames, ByteOrder.nativeOrder(), finder
        );

        try (ImageReader image = ImageReader.open(options.jimagePath)) {
            FallbackJmodPlugin plugin = new FallbackJmodPlugin(options, image);
            Jlink.PluginsConfiguration pluginsConfiguration = new Jlink.PluginsConfiguration(
                    List.of(plugin), new DefaultImageBuilder(options.targetDir, Map.of()), null
            );

            Jlink jlink = new Jlink();
            jlink.build(configuration, pluginsConfiguration);
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
