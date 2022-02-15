package org.glavo.jmod.fallback;

public class Exit extends RuntimeException {
    public Exit() {
    }

    public Exit(String message) {
        super(message);
    }

    public Exit(String message, Throwable cause) {
        super(message, cause);
    }

    public Exit(Throwable cause) {
        super(cause);
    }

    public Exit(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
