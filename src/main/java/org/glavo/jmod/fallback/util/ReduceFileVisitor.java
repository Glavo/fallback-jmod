package org.glavo.jmod.fallback.util;

import jdk.internal.jimage.ImageLocation;
import jdk.internal.jimage.ImageReader;
import org.glavo.jmod.fallback.Main;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class ReduceFileVisitor implements FileVisitor<Path> {

    private final String moduleName;
    private final Path runtimePath;
    private final ImageReader image;
    public final List<PathMatcher> excludePatterns = new ArrayList<>();
    public final List<PathMatcher> withoutVerifyPatterns = new ArrayList<>();

    private final SortedMap<Path, String> recordedHash = new TreeMap<>(PathArrayComparator.PATH_COMPARATOR);

    public ReduceFileVisitor(String moduleName, Path runtimePath, ImageReader image) throws IOException {
        this.moduleName = moduleName;
        this.runtimePath = runtimePath;
        this.image = image;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        String dirName = dir.toString();
        if (dirName.equals("/")
                || dirName.startsWith(JmodUtils.SECTION_BIN, 1)
                || dirName.startsWith(JmodUtils.SECTION_LIB, 1)
                || dirName.startsWith(JmodUtils.SECTION_CLASSES, 1)
                || dirName.startsWith(JmodUtils.SECTION_INCLUDE, 1)
        ) {
            return FileVisitResult.CONTINUE;
        } else {
            return FileVisitResult.SKIP_SUBTREE;
        }
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if ("module-info.class".equals(file.getFileName().toString())) {
            return FileVisitResult.CONTINUE;
        }

        for (PathMatcher excludePattern : excludePatterns) {
            if (excludePattern.matches(file)) {
                Main.printDebugMessage("Exclude: " + file);
                return FileVisitResult.CONTINUE;
            }
        }

        if (Files.isRegularFile(file)) {
            String filePath = file.toString().substring(1);

            boolean verify = true;

            for (PathMatcher pattern : withoutVerifyPatterns) {
                if (pattern.matches(file)) {
                    Main.printDebugMessage("No Verify: " + filePath);
                    verify = false;
                }
            }

            if (filePath.startsWith(JmodUtils.SECTION_CLASSES)) {
                ImageLocation location = image.findLocation(moduleName, filePath.substring(JmodUtils.SECTION_CLASSES.length() + 1));
                if (location != null && (!verify || location.getUncompressedSize() == Files.size(file))) {
                    if (verify) {
                        String expectedHash = MessageDigestUtils.hash(file);
                        String actualHash;
                        try (InputStream input = image.getResourceStream(location)) {
                            actualHash = MessageDigestUtils.hash(input);
                        }

                        if (expectedHash.equals(actualHash)) {
                            recordedHash.put(file, expectedHash);
                        } else {
                            Main.printDebugMessage("Mismatch: " + filePath);
                        }
                    } else {
                        recordedHash.put(file, "");
                    }
                } else {
                    Main.printDebugMessage(() -> (location != null ? "Mismatch: " : "Not found: ") + filePath);
                }
            } else {
                Path runtimeFilePath = runtimePath.resolve(JmodUtils.mapToRuntimePath(filePath));

                if (Files.isRegularFile(runtimeFilePath) && (!verify || Files.size(runtimeFilePath) == Files.size(file))) {
                    if (verify) {
                        String expectedHash = MessageDigestUtils.hash(file);
                        String actualHash = MessageDigestUtils.hash(runtimeFilePath);

                        if (expectedHash.equals(actualHash)) {
                            recordedHash.put(file, expectedHash);
                        } else {
                            Main.printDebugMessage("Mismatch: " + filePath);
                        }
                    } else {
                        recordedHash.put(file, "");
                    }
                } else {
                    Main.printDebugMessage(() -> (Files.isRegularFile(runtimeFilePath) ? "Mismatch: " : "Not found: ") + filePath);
                }
            }
        }

        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
        throw exc;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        return FileVisitResult.CONTINUE;
    }

    public SortedMap<Path, String> getRecordedHash() {
        return recordedHash;
    }
}
