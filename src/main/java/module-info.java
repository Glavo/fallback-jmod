module org.glavo.jmod.fallback {
    requires jdk.jlink;

    exports org.glavo.jmod.fallback;
    exports org.glavo.jmod.fallback.jlink;

    provides jdk.tools.jlink.plugin.Plugin
            with org.glavo.jmod.fallback.jlink.FallbackJmodPlugin;
}