package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record WorkerAnalysisSettingBookPayload(UUID settingBookId, String originalFilename, UUID revisionId,
        int revisionNo, String contentS3Key, @Schema(nullable = true) String contentS3Version,
        String contentHash, int charCount,
        org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind referenceTimeKind,
        @Schema(nullable = true) Integer referenceEpisodeNo) {
    public WorkerAnalysisSettingBookPayload(UUID settingBookId, String originalFilename, UUID revisionId, int revisionNo,
            String contentS3Key, String contentS3Version, String contentHash, int charCount) {
        this(settingBookId, originalFilename, revisionId, revisionNo, contentS3Key, contentS3Version, contentHash, charCount,
                org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind.UNSPECIFIED, null);
    }
}
