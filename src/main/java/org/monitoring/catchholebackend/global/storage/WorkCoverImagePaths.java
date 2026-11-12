package org.monitoring.catchholebackend.global.storage;

import java.util.UUID;

public final class WorkCoverImagePaths {
    private WorkCoverImagePaths() {}
    public static String prefix(UUID workId, UUID imageId) {
        return "works/" + workId + "/cover-images/" + imageId + "/";
    }
    public static String key(UUID workId, UUID imageId) { return prefix(workId, imageId) + "cover.png"; }
}
