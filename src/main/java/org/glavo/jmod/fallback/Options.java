package org.glavo.jmod.fallback;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Options {
    public Path runtimePath;
    public Path jimagePath;
    public final Map<Path, Path> files = new LinkedHashMap<>(); // from file to target file

    public Path targetDir; // For jlink
    // final List<String> includePatterns = new ArrayList<>();
    // final List<String> excludePatterns = new ArrayList<>();
}
