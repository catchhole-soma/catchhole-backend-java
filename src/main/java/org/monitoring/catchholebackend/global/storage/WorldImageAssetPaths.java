package org.monitoring.catchholebackend.global.storage;

public final class WorldImageAssetPaths {
    private WorldImageAssetPaths() {}

    public static String publicPath(String sha) {
        return "/api/v1/world-image-assets/" + sha + ".webp";
    }

    public static String storageKey(String sha) {
        return "world-image-catalog/v1/" + sha + ".webp";
    }
}
