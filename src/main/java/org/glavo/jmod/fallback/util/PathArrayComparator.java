package org.glavo.jmod.fallback.util;

import java.nio.file.Path;
import java.util.Comparator;

public enum PathArrayComparator implements Comparator<String[]> {
    INSTANCE;

    public static final Comparator<Path> PATH_COMPARATOR = Comparator.comparing(path -> path.toString().split("[/\\\\]"), INSTANCE);

    @Override
    public int compare(String[] x, String[] y) {
        final int xLength = x.length;
        final int yLength = y.length;

        int length = Math.min(xLength, yLength);
        for (int i = 0; i < length; i++) {
            int v = x[i].compareTo(y[i]);
            if (v != 0) {
                return v;
            }
        }

        return xLength - yLength;
    }
}
