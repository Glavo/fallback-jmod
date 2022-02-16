package org.glavo.jmod.fallback.jlink;

import jdk.internal.jimage.ImageLocation;
import jdk.internal.jimage.ImageReader;
import jdk.tools.jlink.internal.plugins.*;
import jdk.tools.jlink.plugin.Plugin;
import jdk.tools.jlink.plugin.ResourcePool;
import jdk.tools.jlink.plugin.ResourcePoolBuilder;
import jdk.tools.jlink.plugin.ResourcePoolEntry;
import org.glavo.jmod.fallback.Main;
import org.glavo.jmod.fallback.Options;
import org.glavo.jmod.fallback.util.FallbackUtils;
import org.glavo.jmod.fallback.util.JmodUtils;
import org.glavo.jmod.fallback.util.MessageDigestUtils;
import org.glavo.jmod.fallback.util.Messages;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

public class FallbackJmodPlugin extends AbstractPlugin {
    private final Options options;
    private final ImageReader image;

    public FallbackJmodPlugin(Options options, ImageReader image) {
        super("fallback-jmod");
        this.options = options;
        this.image = image;
    }

    @Override
    public Category getType() {
        return Category.FILTER;
    }

    @Override
    public Set<State> getState() {
        return EnumSet.of(State.AUTO_ENABLED, State.FUNCTIONAL);
    }

    @Override
    public boolean hasArguments() {
        return true;
    }

    @Override
    public String getOption() {
        return "fallback-jmod";
        //return "runtime-path";
    }

    @Override
    public String getDescription() {
        return "JUST FOR TEST";
    }

    @Override
    public ResourcePool transform(ResourcePool in, ResourcePoolBuilder out) {
        in.entries().forEach(resource -> {
            String moduleName = resource.moduleName();
            if (resource.path().equals("/" + moduleName + "/" + Main.FALLBACK_LIST_FILE_NAME)) {
                try {
                    Map<String, String> list;
                    try (InputStream input = resource.content()) {
                        list = FallbackUtils.readFallbackList(input);
                    }

                    for (Map.Entry<String, String> entry : list.entrySet()) {
                        String fileName = entry.getKey();
                        String hash = entry.getValue();

                        ResourcePoolEntry.Type type;
                        if (fileName.startsWith(JmodUtils.SECTION_CLASSES + "/")) {
                            type = ResourcePoolEntry.Type.CLASS_OR_RESOURCE;
                        } else if (fileName.startsWith(JmodUtils.SECTION_CONF + "/")) {
                            type = ResourcePoolEntry.Type.CONFIG;
                        } else if (fileName.startsWith(JmodUtils.SECTION_INCLUDE + "/")) {
                            type = ResourcePoolEntry.Type.HEADER_FILE;
                        } else if (fileName.startsWith(JmodUtils.SECTION_LEGAL + "/")) {
                            type = ResourcePoolEntry.Type.LEGAL_NOTICE;
                        } else if (fileName.startsWith(JmodUtils.SECTION_MAN + "/")) {
                            type = ResourcePoolEntry.Type.MAN_PAGE;
                        } else if (fileName.startsWith(JmodUtils.SECTION_BIN)) {
                            type = ResourcePoolEntry.Type.NATIVE_CMD;
                        } else if (fileName.startsWith(JmodUtils.SECTION_LIB)) {
                            type = ResourcePoolEntry.Type.NATIVE_LIB;
                        } else {
                            type = ResourcePoolEntry.Type.TOP;
                        }


                        String entryPath = type == ResourcePoolEntry.Type.CLASS_OR_RESOURCE
                                ? "/" + moduleName + fileName.substring(JmodUtils.SECTION_CLASSES.length())
                                : "/" + moduleName + "/" + fileName;

                        if (in.findEntry(entryPath).isPresent()) {
                            continue;
                        }

                        if (type == ResourcePoolEntry.Type.CLASS_OR_RESOURCE) {
                            ImageLocation location = image.findLocation(moduleName, fileName.substring(JmodUtils.SECTION_CLASSES.length() + 1));
                            if (location == null) {
                                throw new FileNotFoundException(fileName);
                            }

                            if (hash != null) {
                                String actualHash;
                                try (InputStream i = image.getResourceStream(location)) {
                                    actualHash = MessageDigestUtils.hash(i);
                                }

                                if (!hash.equals(actualHash)) {
                                    throw new IOException(Messages.getMessage("error.mismatch.hash", fileName));
                                }
                            }

                            byte[] classContent;
                            try (InputStream i = image.getResourceStream(location)) {
                                classContent = i.readAllBytes();
                            }

                            out.add(ResourcePoolEntry.create(entryPath, type, classContent));
                        } else {
                            Path runtimeFilePath = options.runtimePath.resolve(JmodUtils.mapToRuntimePath(fileName));

                            if (!Files.isRegularFile(runtimeFilePath)) {
                                throw new FileNotFoundException(runtimeFilePath.toString());
                            }

                            if (hash != null) {
                                String actualHash = MessageDigestUtils.hash(runtimeFilePath);
                                if (!hash.equals(actualHash)) {
                                    throw new IOException(Messages.getMessage("error.mismatch.hash", fileName));
                                }
                            }

                            out.add(ResourcePoolEntry.create(entryPath, type, runtimeFilePath));
                        }

                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                out.add(resource);
            }
        });

        return out.build();
    }
}
