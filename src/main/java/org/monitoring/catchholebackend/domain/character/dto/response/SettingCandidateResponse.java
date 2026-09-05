package org.monitoring.catchholebackend.domain.character.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateKind;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;

@Schema(description = "설정 후보 응답")
public record SettingCandidateResponse(
        @Schema(description = "설정 후보 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d333")
        UUID id,

        @Schema(description = "작품 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d444")
        UUID workId,

        @Schema(description = "후보가 추출된 회차 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d222", nullable = true)
        UUID episodeId,

        @Schema(description = "후보가 추출된 회차 번호", example = "3", nullable = true)
        Integer episodeNo,

        @Schema(description = "원문 근거 청크 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d111", nullable = true)
        UUID sourceChunkId,

        @Schema(description = "후보를 만든 분석 작업 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d555", nullable = true)
        UUID analysisJobId,

        @Schema(description = "후보 종류", example = "SETTING")
        SettingCandidateKind candidateKind,

        @Schema(description = "설정 대상 유형", example = "CHARACTER")
        SettingEntityType entityType,

        @Schema(description = "설정 대상 이름", example = "아리아")
        String entityName,

        @Schema(description = "원문에 실제 등장한 설정 대상 표현", example = "프넬린의 두 번째 딸 아이나르", nullable = true)
        String rawEntityMention,

        @Schema(description = "직접 또는 같은 이름으로 연결 완료한 characters.id", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d666", nullable = true)
        UUID matchedCharacterId,

        @Schema(description = "캐릭터 연결 상태. 같은 이름 후보의 자동 연결 여부를 포함", example = "UNRESOLVED")
        SettingCandidateMatchStatus matchStatus,

        @Schema(description = "설정 속성명. 캐릭터 발견 후보는 null입니다.", example = "level", nullable = true)
        String attributeName,

        @Schema(
                description = "현재 활성 schema 기준 설정 속성명 편집 가능 여부",
                example = "false",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        boolean attributeNameEditable,

        @Schema(
                description = "편집 가능한 동적 설정명의 서버 지정 prefix. 고정 또는 해석 불가 후보는 null입니다.",
                example = "skill.",
                nullable = true
        )
        String attributeNamePrefix,

        @Schema(description = "목록/검색 표시용 설정 값", example = "23", nullable = true)
        String attributeValue,

        @Schema(description = "설정 값 타입. 캐릭터 발견 후보는 null입니다.", example = "NUMBER", nullable = true)
        SettingValueType valueType,

        @Schema(
                description = "구조화된 설정 값 JSON. 캐릭터 발견 후보는 null입니다.",
                nullable = true,
                implementation = JsonNode.class
        )
        Object valueJson,

        @Schema(
                description = "현재 활성 schema와 구조화 값을 기준으로 파생한 값 검증 결과",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        SettingCandidateValueValidationResponse valueValidation,

        @Schema(description = "원문 근거 span JSON", nullable = true, implementation = JsonNode.class)
        Object evidenceSpans,

        @Schema(description = "AI 추출 신뢰도", example = "0.9500", nullable = true)
        BigDecimal confidence,

        @Schema(description = "후보 검토 상태", example = "PENDING_REVIEW")
        SettingCandidateReviewStatus reviewStatus,

        @Schema(description = "AI Worker 원본 응답 JSON", nullable = true, implementation = JsonNode.class)
        Object rawAiResultJson,

        @Schema(description = "캐릭터 현재 설정과의 2차 비교 상태")
        CharacterFactComparisonStatus comparisonStatus,

        @Schema(description = "AI가 제안한 현재 설정 반영 방식", nullable = true)
        CharacterFactOperation suggestedOperation,

        @Schema(description = "후보가 서술하는 시간 범위", nullable = true)
        CharacterFactTemporalScope temporalScope,

        @Schema(description = "현재 snapshot에서 비교한 canonical Fact 유형", nullable = true)
        CharacterFactType comparisonTargetFactType,

        @Schema(description = "현재 snapshot에서 비교한 canonical Fact key", nullable = true)
        String comparisonTargetFactKey,

        @Schema(
                description = "2차 묶음 비교가 해소한 canonical Fact key. target이 없는 REMOVE/HISTORY_ONLY에도 남습니다.",
                nullable = true
        )
        String resolvedCanonicalFactKey,

        @Schema(
                description = "현재 snapshot에 적용하도록 제안된 최종 구조화 값",
                nullable = true,
                implementation = JsonNode.class
        )
        Object proposedValueJson,

        @Schema(description = "현재 snapshot에 적용하도록 제안된 최종 표시값", nullable = true)
        String proposedFactValue,

        @Schema(description = "확정 시 적용될 snapshot 변경 목록")
        List<SettingCandidateSnapshotChangeResponse> snapshotChanges,

        @Schema(description = "AI 비교 판단 이유", nullable = true)
        String comparisonReason,

        @Schema(description = "마지막 비교 실패 또는 재비교 사유", nullable = true)
        String comparisonErrorMessage,

        @Schema(description = "기계 판독용 비교 실패 코드", nullable = true)
        AnalysisFailureCode comparisonFailureCode,

        @Schema(description = "비교 문맥을 만든 당시 캐릭터 snapshot version", nullable = true)
        Long comparisonBaseSnapshotVersion,

        @Schema(description = "생성 시각", example = "2026-06-14T10:29:00")
        LocalDateTime createdAt,

        @Schema(description = "수정 시각", example = "2026-06-14T10:29:00")
        LocalDateTime updatedAt
) {
}
