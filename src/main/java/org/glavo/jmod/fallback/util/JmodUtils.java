package org.glavo.jmod.fallback.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

public class JmodUtils {
    private static final int MAJOR_VERSION = 0x01;
    private static final int MINOR_VERSION = 0x00;
    private static final byte[] MAGIC_NUMBER = {0x4A, 0x4D};

    public static FileSystem open(Path path) throws IOException {
        byte[] magic = new byte[4];
        int n;
        try (InputStream input = Files.newInputStream(path)) {
            n = input.read(magic);
        }

        if (n != 4 || magic[0] != MAGIC_NUMBER[0] || magic[1] != MAGIC_NUMBER[1]) {
            throw new IOException("Incorrect jmod file format");
        }

        if (magic[2] != MAJOR_VERSION || magic[3] != MINOR_VERSION) {
            throw new IOException("Unsupported jmod file version");
        }

        return ZipUtils.ZIPFS_PROVIDER.newFileSystem(path, Collections.emptyMap());
    }

    public static void writeMagicNumber(OutputStream output) throws IOException {
        output.write(MAGIC_NUMBER);
        output.write(MAJOR_VERSION);
        output.write(MINOR_VERSION);
    }

    public static String mapToRuntimePath(String filePath) {
        // See jdk.tools.jlink.builder.DefaultImageBuilder#nativeDir
        if (filePath.startsWith(JmodUtils.SECTION_LIB) && (filePath.endsWith(".dll")
                || filePath.endsWith(".diz")
                || filePath.endsWith(".pdb")
                || filePath.endsWith(".map"))) {
            return JmodUtils.SECTION_BIN + filePath.substring(JmodUtils.SECTION_LIB.length());
        } else {
            return filePath;
        }
    }

    public static final String SECTION_CLASSES = "classes";
    public static final String SECTION_CONF = "conf";
    public static final String SECTION_INCLUDE = "include";
    public static final String SECTION_LEGAL = "legal";
    public static final String SECTION_MAN = "man";
    public static final String SECTION_LIB = "lib";
    public static final String SECTION_BIN = "bin";
}
