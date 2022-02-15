package org.glavo.jmod.fallback.util;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;

public class ZipUtils {
    public static final FileSystemProvider ZIPFS_PROVIDER =
            FileSystemProvider.installedProviders().stream()
                    .filter(it -> "jar".equalsIgnoreCase(it.getScheme()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Could not find zipfs"));


    public static FileSystem openForWrite(Path path) throws IOException {
        return ZIPFS_PROVIDER.newFileSystem(path, Map.of("create", true, "useTempFile", true));
    }
}
