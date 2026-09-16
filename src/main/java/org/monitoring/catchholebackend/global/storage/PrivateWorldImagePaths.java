package org.monitoring.catchholebackend.global.storage;

import java.util.UUID;

public final class PrivateWorldImagePaths {
    private PrivateWorldImagePaths() {}
    public static String prefix(UUID workId, UUID imageId) {
        return "works/" + workId + "/private-world-images/" + imageId + "/";
    }
    public static String key(UUID workId, UUID imageId, boolean thumbnail) {
        return prefix(workId, imageId) + (thumbnail ? "thumbnail" : "image") + ".enc";
    }
    public static String apiPath(UUID workId, UUID imageId, boolean thumbnail) {
        return "/api/v1/works/" + workId + "/private-world-images/" + imageId + "/"
                + (thumbnail ? "thumbnail" : "image");
    }
}
