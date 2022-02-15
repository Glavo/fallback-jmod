package org.glavo.jmod.fallback.util;

import jdk.internal.org.objectweb.asm.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModuleNameFinder extends ClassVisitor {
    private String moduleName;

    public ModuleNameFinder() {
        super(Opcodes.ASM6);
    }

    public static String findModuleName(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            ClassReader reader = new ClassReader(input);
            ModuleNameFinder finder = new ModuleNameFinder();
            reader.accept(finder, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            return finder.getModuleName();
        }
    }

    public String getModuleName() {
        return moduleName;
    }

    @Override
    public ModuleVisitor visitModule(String name, int access, String version) {
        this.moduleName = name;
        return null;
    }

}
