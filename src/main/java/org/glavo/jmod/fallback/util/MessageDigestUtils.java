package org.glavo.jmod.fallback.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class MessageDigestUtils {
    public static final String[] byte2str = {
            "00", "01", "02", "03", "04", "05", "06", "07", "08", "09", "0a", "0b", "0c", "0d", "0e", "0f",
            "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "1a", "1b", "1c", "1d", "1e", "1f",
            "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "2a", "2b", "2c", "2d", "2e", "2f",
            "30", "31", "32", "33", "34", "35", "36", "37", "38", "39", "3a", "3b", "3c", "3d", "3e", "3f",
            "40", "41", "42", "43", "44", "45", "46", "47", "48", "49", "4a", "4b", "4c", "4d", "4e", "4f",
            "50", "51", "52", "53", "54", "55", "56", "57", "58", "59", "5a", "5b", "5c", "5d", "5e", "5f",
            "60", "61", "62", "63", "64", "65", "66", "67", "68", "69", "6a", "6b", "6c", "6d", "6e", "6f",
            "70", "71", "72", "73", "74", "75", "76", "77", "78", "79", "7a", "7b", "7c", "7d", "7e", "7f",
            "80", "81", "82", "83", "84", "85", "86", "87", "88", "89", "8a", "8b", "8c", "8d", "8e", "8f",
            "90", "91", "92", "93", "94", "95", "96", "97", "98", "99", "9a", "9b", "9c", "9d", "9e", "9f",
            "a0", "a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "aa", "ab", "ac", "ad", "ae", "af",
            "b0", "b1", "b2", "b3", "b4", "b5", "b6", "b7", "b8", "b9", "ba", "bb", "bc", "bd", "be", "bf",
            "c0", "c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9", "ca", "cb", "cc", "cd", "ce", "cf",
            "d0", "d1", "d2", "d3", "d4", "d5", "d6", "d7", "d8", "d9", "da", "db", "dc", "dd", "de", "df",
            "e0", "e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8", "e9", "ea", "eb", "ec", "ed", "ee", "ef",
            "f0", "f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "fa", "fb", "fc", "fd", "fe", "ff"
    };

    private static final class Cache {
        public final MessageDigest messageDigest;
        public final byte[] cacheArray;

        private Cache(MessageDigest messageDigest, byte[] cacheArray) {
            this.messageDigest = messageDigest;
            this.cacheArray = cacheArray;
        }
    }

    private static final ThreadLocal<Cache> cache = ThreadLocal.withInitial(() -> {
        try {
            return new Cache(MessageDigest.getInstance("SHA-256"), new byte[8 * 1024]);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    });

    public static MessageDigest getMessageDigest() {
        MessageDigest md = cache.get().messageDigest;
        md.reset();
        return md;
    }

    public static String hash(InputStream input) throws IOException {
        Cache cache = MessageDigestUtils.cache.get();
        byte[] buffer = cache.cacheArray;
        MessageDigest md = cache.messageDigest;
        md.reset();


        int read;
        do {
            read = input.read(buffer);
            if (read > 0) {
                md.update(buffer, 0, read);
            }
        } while (read >= 0);

        byte[] digest = md.digest();
        assert digest != null;

        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            builder.append(byte2str[b & 0xFF]);
        }
        return builder.toString();
    }

    public static String hash(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return hash(input);
        }
    }

    public static boolean checkSHA256Hash(String hash) {
        if (hash.length() != 64) {
            return false;
        }
        for (int i = 0; i < hash.length(); i++) {
            char ch = hash.charAt(i);
            if ((ch < '0' || ch > '9') && (ch < 'a' || ch > 'f')) {
                return false;
            }
        }

        return true;
    }
}
