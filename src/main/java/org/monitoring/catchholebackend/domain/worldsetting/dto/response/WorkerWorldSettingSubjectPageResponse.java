package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "Worker 세계관 설정 대상명 페이지")
public record WorkerWorldSettingSubjectPageResponse(
        List<Subject> subjects,
        int page,
        boolean hasNext
) {

    public record Subject(
            UUID worldSettingId,
            String subjectName
    ) {
    }
}
