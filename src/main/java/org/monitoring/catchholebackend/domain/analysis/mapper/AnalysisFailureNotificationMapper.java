package org.monitoring.catchholebackend.domain.analysis.mapper;

import org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotification;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.springframework.stereotype.Component;

@Component
public class AnalysisFailureNotificationMapper {
    public String toMessage(AnalysisFailureNotification event, String environment) {
        AnalysisFailureCode code;
        try { code = AnalysisFailureCode.valueOf(event.failureCode()); }
        catch (IllegalArgumentException | NullPointerException ex) { code = AnalysisFailureCode.UNEXPECTED_ERROR; }
        String kind = switch (event.failureKind()) {
            case "CHARACTER_COMPARISON" -> "캐릭터 비교 실패";
            case "WORLD_COMPARISON" -> "세계관 비교 실패";
            default -> "회차 분석 실패";
        };
        String safeEnvironment = "prod".equals(environment) ? "운영" : "local".equals(environment) ? "로컬" : "기타";
        return """
                🚨 [%s] %s
                사유: %s (%s)
                회차: %s · 실패 항목: %d개
                회원 ID: %s
                작품 ID: %s
                회차 ID: %s
                분석 Job ID: %s
                발생 시각: %s (서버 시간)
                알림 ID: %s
                """.formatted(safeEnvironment, kind, code.getPublicMessage(), code.name(),
                event.episodeNo() == null ? "그룹/회차 미지정" : event.episodeNo() + "화", event.failureCount(),
                event.memberId(), event.workId(), event.episodeId(), event.jobId(), event.occurredAt(), event.id());
    }
}
