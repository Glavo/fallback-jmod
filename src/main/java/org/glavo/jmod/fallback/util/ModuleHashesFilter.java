package org.glavo.jmod.fallback.util;

import jdk.internal.module.ModuleHashes;
import jdk.internal.module.ModuleInfoExtender;
import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.commons.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ModuleHashesFilter extends ClassVisitor {
    private static final ModuleHashes EMPTY_MODULE_HASHES;

    static {
        try {
            Constructor<ModuleHashes> constructor = ModuleHashes.class.getDeclaredConstructor(String.class, Map.class);
            constructor.setAccessible(true);
            EMPTY_MODULE_HASHES = constructor.newInstance("SHA-256", Collections.emptyMap());
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    public ModuleHashesFilter(ClassVisitor classVisitor) {
        super(Opcodes.ASM6, classVisitor);
    }

    @Override
    public void visitAttribute(Attribute attribute) {
        if ("ModuleHashes".equals(attribute.type)) {
            attribute = new ModuleHashesAttribute("SHA-256", List.of(), List.of());
        }
        super.visitAttribute(attribute);
    }

    public static byte[] filter(Path source) throws IOException {


        try (InputStream input = Files.newInputStream(source)) {
            return ModuleInfoExtender.newExtender(input)
                    .hashes(EMPTY_MODULE_HASHES)
                    .toByteArray();
        }
    }
}
