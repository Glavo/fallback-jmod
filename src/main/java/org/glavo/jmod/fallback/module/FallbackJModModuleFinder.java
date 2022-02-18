package org.glavo.jmod.fallback.module;

import jdk.internal.module.*;
import org.glavo.jmod.fallback.Main;
import org.glavo.jmod.fallback.util.FallbackUtils;
import org.glavo.jmod.fallback.util.JmodUtils;
import org.glavo.jmod.fallback.util.ModuleHashesUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.InvalidModuleDescriptorException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Supplier;

public class FallbackJModModuleFinder implements ModuleFinder {

    private final Map<String, ModuleReference> modules;

    private FallbackJModModuleFinder(Map<String, ModuleReference> modules) {
        this.modules = modules;
    }

    private static Set<String> jmodPackages(Path classesDir, Map<String, String> fallbackList) {
        HashSet<String> res = new HashSet<>();

        SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dir = file.getParent();
                String fileName = file.getFileName().toString();
                if (dir.equals(classesDir) && fileName.endsWith(".class") && !"module-info.class".equals(fileName)) {
                    throw new InvalidModuleDescriptorException(file + " found in top-level directory"
                            + " (unnamed package not allowed in module)");
                }
                String packageName = classesDir.relativize(dir).toString().replace('/', '.');
                if (Checks.isPackageName(packageName)) {
                    res.add(packageName);
                }
                return FileVisitResult.CONTINUE;
            }
        };


        try {
            Files.walkFileTree(classesDir, visitor);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        for (String fileName : fallbackList.keySet()) {
            if (fileName.startsWith(JmodUtils.SECTION_CLASSES + "/")) {
                String fn = fileName.substring(JmodUtils.SECTION_CLASSES.length() + 1);

                int idx = fn.indexOf('/');
                if (idx == -1 && fileName.endsWith(".class") && !"module-info.class".equals(fn)) {
                    throw new InvalidModuleDescriptorException(fileName + " found in top-level directory" + " (unnamed package not allowed in module)");
                }

                String packageName = fn.substring(0, idx).replace('/', '.');
                res.add(packageName);
            }
        }

        return res;
    }

    private static ModuleReference read(Path runtimePath, Path path) throws IOException {
        boolean succeed = false;
        FileSystem fs = JmodUtils.open(path);
        try {
            Path classes = fs.getPath("/", JmodUtils.SECTION_CLASSES).toAbsolutePath();

            Map<String, String> list = FallbackUtils.readFallbackListOrEmpty(fs.getPath("/", Main.FALLBACK_LIST_FILE_NAME));
            ModuleInfo.Attributes attrs;
            try (InputStream input = Files.newInputStream(classes.resolve("module-info.class"))) {
                attrs = ModuleInfo.read(input, () -> jmodPackages(classes, list));
            }

            Supplier<ModuleReader> supplier = () -> new FallbackJModModuleReader(runtimePath, fs, list);
            ModuleHashes.HashSupplier hasher = (a) -> ModuleHashesUtils.computeHash(supplier, a);

            ModuleReference mref = new ModuleReferenceImpl(attrs.descriptor(),
                    path.toUri(),
                    supplier,
                    null,
                    attrs.target(),
                    attrs.recordedHashes(),
                    hasher,
                    attrs.moduleResolution());

            succeed = true;
            return mref;

        } finally {
            if (!succeed) {
                fs.close();
            }
        }
    }

    public static FallbackJModModuleFinder of(Path runtimePath, List<Path> entries) throws IOException {
        LinkedHashMap<String, ModuleReference> modules = new LinkedHashMap<>();
        for (Path entry : entries) {
            ModuleReference ref = read(runtimePath, entry);
            String moduleName = ref.descriptor().name();

            if (modules.putIfAbsent(moduleName, ref) != null) {
                throw new IllegalArgumentException("Duplicate module " + moduleName);
            }
        }
        return new FallbackJModModuleFinder(modules);
    }

    @Override
    public Optional<ModuleReference> find(String name) {
        return Optional.ofNullable(modules.get(name));
    }

    @Override
    public Set<ModuleReference> findAll() {
        return new HashSet<>(modules.values());
    }
}
