package org.monitoring.catchholebackend.domain.upload.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisSourceResponse;

@Schema(description = "분석 당시 불변 설정집 원문. 설정집을 수정하거나 보관한 뒤에도 같은 원문을 조회합니다.")
public record SettingBookRevisionResponse(UUID settingBookId, String originalFilename, UUID revisionId,
        int revisionNo, String contentHash, int charCount, String content, AnalysisSourceResponse source,
        org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind referenceTimeKind,
        @Schema(nullable = true) Integer referenceEpisodeNo) {
    public SettingBookRevisionResponse(UUID settingBookId, String originalFilename, UUID revisionId,
            int revisionNo, String contentHash, int charCount, String content, AnalysisSourceResponse source) {
        this(settingBookId, originalFilename, revisionId, revisionNo, contentHash, charCount, content, source,
                org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind.UNSPECIFIED, null);
    }
}
