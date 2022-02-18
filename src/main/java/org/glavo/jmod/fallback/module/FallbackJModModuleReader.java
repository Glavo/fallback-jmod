package org.glavo.jmod.fallback.module;

import org.glavo.jmod.fallback.util.JmodUtils;

import java.io.IOException;
import java.lang.module.ModuleReader;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

public class FallbackJModModuleReader implements ModuleReader {

    private final Path runtimePath;
    private final FileSystem fs;
    private final Map<String, String> fallbackList;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private boolean closed = false;

    public FallbackJModModuleReader(Path runtimePath, FileSystem fs, Map<String, String> fallbackList) {
        this.runtimePath = runtimePath;
        this.fs = fs;
        this.fallbackList = fallbackList;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("ModuleReader is closed");
        }
    }

    private Path findPath(String name) {
        boolean expectDirectory = name.endsWith("/");

        String fileName = JmodUtils.SECTION_CLASSES + "/" + name;
        Path path = fs.getPath("/", fileName);
        if (!Files.exists(path)) {
            if (expectDirectory) {
                if (fallbackList.keySet().stream().allMatch(it -> it.startsWith(fileName))) {
                    path = runtimePath.resolve(JmodUtils.mapToRuntimePath(fileName));
                }
            } else {
                if (fallbackList.containsKey(name)) {
                    path = runtimePath.resolve(JmodUtils.mapToRuntimePath(name));
                }
            }
        }
        return Files.exists(path) && (!expectDirectory || Files.isDirectory(path)) ? path : null;
    }


    @Override
    public Optional<URI> find(String name) throws IOException {
        lock.readLock().lock();
        try {
            ensureOpen();
            return Optional.ofNullable(findPath(name)).map(Path::toUri);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Stream<String> list() throws IOException {
        lock.readLock().lock();
        try {
            ensureOpen();

            HashSet<String> res = new HashSet<>();

            for (String fileName : fallbackList.keySet()) {
                if (fileName.startsWith(JmodUtils.SECTION_CLASSES + "/")) {
                    res.add(fileName.substring(JmodUtils.SECTION_CLASSES.length() + 1));
                }
            }

            Path classesDir = fs.getPath("/", JmodUtils.SECTION_CLASSES);

            SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    res.add(classesDir.relativize(file).toString());
                    return FileVisitResult.CONTINUE;
                }
            };

            Files.walkFileTree(classesDir, visitor);
            return res.stream();

        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() throws IOException {
        lock.writeLock().lock();
        try {
            if (!closed) {
                closed = true;
                fs.close();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}
