package org.glavo.jmod.fallback.util;

import jdk.internal.jimage.ImageLocation;
import jdk.internal.jimage.ImageReader;

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
        ) {
            return FileVisitResult.CONTINUE;
        } else {
            return FileVisitResult.SKIP_SUBTREE;
        }
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (Files.isRegularFile(file)) {
            String filePath = file.toString();
            if (filePath.charAt(0) != '/') {
                throw new AssertionError(file);
            }

            filePath = filePath.substring(1);

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
                    }
                }
            } else {
                Path runtimeFilePath;

                if (filePath.startsWith(JmodUtils.SECTION_BIN)) {
                    runtimeFilePath = runtimePath.resolve(filePath);
                } else if (filePath.startsWith(JmodUtils.SECTION_LIB)) {
                    if (filePath.toLowerCase(Locale.ROOT).endsWith(".dll")) {
                        runtimeFilePath = runtimePath.resolve(JmodUtils.SECTION_BIN + filePath.substring(JmodUtils.SECTION_LIB.length()));
                    } else {
                        runtimeFilePath = runtimePath.resolve(filePath);
                    }
                } else {
                    throw new AssertionError(file);
                }

                if (Files.isRegularFile(runtimeFilePath) && Files.size(runtimeFilePath) == Files.size(file)) {
                    String expectedHash = MessageDigestUtils.hash(file);
                    String actualHash = MessageDigestUtils.hash(runtimeFilePath);

                    if (expectedHash.equals(actualHash)) {
                        recordedHash.put(file, expectedHash);
                    }
                }

            }
        }

        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
        exc.printStackTrace();
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        return FileVisitResult.CONTINUE;
    }

    public SortedMap<Path, String> getRecordedHash() {
        return recordedHash;
    }
}
