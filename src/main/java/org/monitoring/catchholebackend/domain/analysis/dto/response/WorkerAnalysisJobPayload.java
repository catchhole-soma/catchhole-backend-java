package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;

@Schema(description = "AI Worker 분석 작업 payload")
public record WorkerAnalysisJobPayload(
        @Schema(description = "분석 작업 ID")
        UUID analysisJobId,

        @Schema(description = "분석 작업 유형")
        AnalysisJobType jobType,

        @Schema(description = "작품 ID")
        UUID workId,

        @Schema(description = "작품 제목")
        String workTitle,

        @Schema(description = "업로드 배치 ID. 후보 단독 재비교 Job은 null일 수 있습니다.", nullable = true)
        UUID batchId,

        @Schema(description = "Worker가 사용할 모델명", nullable = true)
        String modelName,

        @Schema(description = "현재 처리 단계", nullable = true)
        String currentStep,

        @Schema(description = "Worker 소유권 lease token")
        UUID leaseToken,

        @Schema(description = "Worker lease 만료 시각")
        LocalDateTime leaseExpiresAt,

        @Schema(description = "현재 Job claim 시도 횟수")
        int claimAttemptCount,

        @Schema(description = "완료된 내부 처리 checkpoint", nullable = true)
        AnalysisJobCheckpointStage checkpointStage,

        @Schema(description = "재비교 Job의 세계관 후보 ID", nullable = true)
        UUID worldSettingCandidateId,

        @Schema(description = "재비교 Job의 캐릭터 설정 후보 ID", nullable = true)
        UUID settingCandidateId,

        @Schema(description = "캐릭터 설정 attribute 해석 schema 목록")
        List<WorkerAnalysisCharacterSettingSchemaPayload> characterSettingSchemas,

        @Schema(description = "캐릭터명 매칭에 사용할 기존 캐릭터 목록")
        List<WorkerAnalysisKnownCharacterPayload> knownCharacters,

        @Schema(description = "분석 대상 단일 회차. 출처 회차가 없는 후보 단독 재비교 Job은 null일 수 있습니다.", nullable = true)
        WorkerAnalysisEpisodePayload episode
) {
}
