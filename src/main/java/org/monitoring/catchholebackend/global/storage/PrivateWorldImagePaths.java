package org.monitoring.catchholebackend.global.storage;

import java.util.UUID;

public final class PrivateWorldImagePaths {
    private PrivateWorldImagePaths() {}
    public static String prefix(UUID workId, UUID imageId) {
        return "works/" + workId + "/private-world-images/" + imageId + "/";
    }
    public static String prefix(UUID workId, UUID imageId, UUID attemptId) {
        // 새 업로드는 구버전 imageId 경로와도 겹치지 않아 늦은 삭제가 새 파일에 닿지 않는다.
        return attemptId == null ? prefix(workId, imageId)
                : "works/" + workId + "/private-world-images/uploads/" + attemptId + "/";
    }
    public static String key(UUID workId, UUID imageId, UUID attemptId, boolean thumbnail) {
        return prefix(workId, imageId, attemptId) + (thumbnail ? "thumbnail" : "image") + ".enc";
    }
    public static String key(UUID workId, UUID imageId, boolean thumbnail) {
        return prefix(workId, imageId) + (thumbnail ? "thumbnail" : "image") + ".enc";
    }
    public static String apiPath(UUID workId, UUID imageId, boolean thumbnail) {
        return "/api/v1/works/" + workId + "/private-world-images/" + imageId + "/"
                + (thumbnail ? "thumbnail" : "image");
    }
}
