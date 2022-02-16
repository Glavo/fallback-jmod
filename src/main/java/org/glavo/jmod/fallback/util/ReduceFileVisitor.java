package org.glavo.jmod.fallback.util;

import jdk.internal.jimage.ImageLocation;
import jdk.internal.jimage.ImageReader;
import org.glavo.jmod.fallback.Main;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;

public class ReduceFileVisitor implements FileVisitor<Path> {

    private final String moduleName;
    private final Path runtimePath;
    private final ImageReader image;

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
        if (Files.isRegularFile(file) && !"module-info.class".equals(file.getFileName().toString())) {
            String filePath = file.toString().substring(1);

            if (filePath.startsWith(JmodUtils.SECTION_CLASSES)) {
                ImageLocation location = image.findLocation(moduleName, filePath.substring(JmodUtils.SECTION_CLASSES.length() + 1));
                if (location != null && location.getUncompressedSize() == Files.size(file)) {
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
                    Main.printDebugMessage(() -> (location != null ? "Mismatch: " : "Not found: ") + filePath);
                }
            } else {
                Path runtimeFilePath = runtimePath.resolve(JmodUtils.mapToRuntimePath(filePath));

                if (Files.isRegularFile(runtimeFilePath) && Files.size(runtimeFilePath) == Files.size(file)) {
                    String expectedHash = MessageDigestUtils.hash(file);
                    String actualHash = MessageDigestUtils.hash(runtimeFilePath);

                    if (expectedHash.equals(actualHash)) {
                        recordedHash.put(file, expectedHash);
                    } else {
                        Main.printDebugMessage("Mismatch: " + filePath);
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
