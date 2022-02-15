package org.glavo.jmod.fallback.util;

import java.text.MessageFormat;
import java.util.ResourceBundle;

public class Messages {
    private static final ResourceBundle resourceBundle = ResourceBundle.getBundle("org.glavo.jmod.fallback.i18n");

    public static String getMessage(String key) {
        return resourceBundle.getString(key);
    }

    public static String getMessage(String key, Object... args) {
        return MessageFormat.format(resourceBundle.getString(key), args);
    }
}
