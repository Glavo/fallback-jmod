package org.glavo.jmod.fallback.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class FallbackUtils {
    public static Map<String, String> readFallbackList(InputStream input) throws IOException {
        LinkedHashMap<String, String> res = new LinkedHashMap<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));

        for (String line : reader.lines().collect(Collectors.toList())) {
            if (line.isBlank()) {
                continue;
            }

            String fileName = null;
            String hash = null;

            int idx = line.indexOf(' ');
            if (idx == 1                // without verify
                    || idx == 64) {     // SHA-256
                fileName = line.substring(idx + 1);
                hash = line.substring(0, idx);
            }

            if (fileName == null
                    || fileName.isEmpty()
                    || (idx == 1 && !"-".equals(hash)
                    || (idx == 64 && !MessageDigestUtils.checkSHA256Hash(hash)))) {
                throw new IOException(Messages.getMessage("error.invalid.record", line));
            }

            if (idx == 1) {
                hash = null;
            }

            if (fileName.startsWith("/")) {
                throw new IOException(Messages.getMessage("error.invalid.record", line));
            }

            for (String s : fileName.split("/")) {
                if (".".equals(s) || "..".equals(s)) {
                    throw new IOException("zip slip: " + line);
                }
            }

            String oldValue = res.putIfAbsent(fileName, hash);
            if (oldValue != null && hash != null && !oldValue.equals(hash)) {
                throw new IOException(Messages.getMessage("error.conflict.record", fileName, hash, oldValue));
            }
        }
        return res;
    }

    public static Map<String, String> readFallbackList(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return readFallbackList(input);
        }
    }

    public static Map<String, String> readFallbackListOrEmpty(Path path) throws IOException {
        if (Files.exists(path)) {
            return readFallbackList(path);
        } else {
            return Collections.emptyMap();
        }
    }
}
