package org.glavo.jmod.fallback.util;

import java.util.Objects;

public final class Pair<A, B> {
    public final A value1;
    public final B value2;

    public Pair(A value1, B value2) {
        this.value1 = value1;
        this.value2 = value2;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Pair)) {
            return false;
        }
        Pair<?, ?> pair = (Pair<?, ?>) o;
        return Objects.equals(value1, pair.value1) && Objects.equals(value2, pair.value2);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value1, value2);
    }

    @Override
    public String toString() {
        return "Pair(" + value1 + "," + value2 + ")";
    }
}
