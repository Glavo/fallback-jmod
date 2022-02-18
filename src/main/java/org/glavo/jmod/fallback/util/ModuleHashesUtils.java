package org.glavo.jmod.fallback.util;

import jdk.internal.module.ModuleHashes;
import jdk.internal.module.ModuleInfoExtender;
import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.commons.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class ModuleHashesUtils {
    private static final ModuleHashes EMPTY_MODULE_HASHES;
    private static final Method computeHash;

    static {
        try {
            Constructor<ModuleHashes> constructor = ModuleHashes.class.getDeclaredConstructor(String.class, Map.class);
            constructor.setAccessible(true);
            EMPTY_MODULE_HASHES = constructor.newInstance("SHA-256", Collections.emptyMap());

            computeHash = ModuleHashes.class.getDeclaredMethod("computeHash", Supplier.class, String.class);
            computeHash.setAccessible(true);


        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }


    public static byte[] filter(Path source) throws IOException {
        try (InputStream input = Files.newInputStream(source)) {
            return ModuleInfoExtender.newExtender(input)
                    .hashes(EMPTY_MODULE_HASHES)
                    .toByteArray();
        }
    }

    public static byte[] computeHash(Supplier<ModuleReader> supplier, String algorithm) {
        try {
            return (byte[]) computeHash.invoke(null, supplier, algorithm);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }
}
