package org.monitoring.catchholebackend.domain.character.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateKind;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
import org.monitoring.catchholebackend.global.exception.AppException;

@Getter
@Entity
@Table(
        name = "setting_candidates",
        indexes = {
                @Index(name = "idx_setting_candidates_work_entity_review", columnList = "work_id,entity_name,review_status"),
                @Index(name = "idx_setting_candidates_work_attribute", columnList = "work_id,attribute_name")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettingCandidate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "work_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_setting_candidates_work")
    )
    private Work work;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "episode_id",
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_setting_candidates_episode")
    )
    private Episode episode;

    // 청킹 엔티티가 생기기 전까지 원문 근거 청크 UUID만 보관합니다.
    @Column(name = "source_chunk_id")
    private UUID sourceChunkId;

    // 원고 교체 뒤에도 분석 당시 evidence offset의 기준 원문을 읽기 위한 S3 key입니다.
    @Column(name = "source_content_s3_key", length = 512)
    private String sourceContentS3Key;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "analysis_job_id",
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_setting_candidates_analysis_job")
    )
    private AnalysisJob analysisJob;

    @Enumerated(EnumType.STRING)
    @Column(name = "candidate_kind", nullable = false, length = 30)
    private SettingCandidateKind candidateKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 30)
    private SettingEntityType entityType;

    // 예: "김철수". 캐릭터 외 설정 대상이 늘어나면 아이템명/장소명도 들어갈 수 있습니다.
    @Column(name = "entity_name", nullable = false, length = 100)
    private String entityName;

    // 예: "나", "홍길동의 두 번째 딸 홍둘째"처럼 원문에 실제 등장한 표현입니다.
    @Column(name = "raw_entity_mention", length = 100)
    private String rawEntityMention;

    // 기존 characters.id와 확실히 매칭된 경우에만 채웁니다.
    @Column(name = "matched_character_id")
    private UUID matchedCharacterId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "matched_character_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_setting_candidates_matched_character")
    )
    private WorkCharacter matchedCharacter;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", nullable = false, length = 30)
    private SettingCandidateMatchStatus matchStatus;

    // 예: "level", "stats.strength", "skill.은월참", "item.화염검", "status.악령_깃들임"
    // 캐릭터 발견 후보는 설정값을 만들지 않으므로 attributeName을 비워 둡니다.
    @Column(name = "attribute_name", length = 100)
    private String attributeName;

    // 목록/검색 표시용 요약값입니다. 예: "12", "화염검", "근력 80 / 민첩 65"
    @Column(name = "attribute_value", columnDefinition = "text")
    private String attributeValue;

    @Enumerated(EnumType.STRING)
    // 캐릭터 발견 후보에는 값 자체가 없으므로 valueType을 비워 둡니다.
    @Column(name = "value_type", length = 30)
    private SettingValueType valueType;

    // 실제 구조화 값입니다. 예: {"name": "화염검", "type": "weapon", "equipped": true}
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value_json", columnDefinition = "jsonb")
    private JsonNode valueJson;

    // 원문 근거입니다. 예: [{"quote": "철수는 화염검을 뽑았다.", "startOffset": 1204, "endOffset": 1221}]
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_spans", columnDefinition = "jsonb")
    private JsonNode evidenceSpans;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 30)
    private SettingCandidateReviewStatus reviewStatus;

    // AI Worker 원본 응답 보관용입니다. 서비스 로직은 가능하면 valueJson/evidenceSpans를 사용합니다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_ai_result_json", columnDefinition = "jsonb")
    private JsonNode rawAiResultJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "comparison_status", nullable = false, length = 40)
    private CharacterFactComparisonStatus comparisonStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "suggested_operation", length = 30)
    private CharacterFactOperation suggestedOperation;

    @Enumerated(EnumType.STRING)
    @Column(name = "temporal_scope", length = 30)
    private CharacterFactTemporalScope temporalScope;

    @Enumerated(EnumType.STRING)
    @Column(name = "comparison_target_fact_type", length = 30)
    private CharacterFactType comparisonTargetFactType;

    @Column(name = "comparison_target_fact_key", length = 150)
    private String comparisonTargetFactKey;

    @Column(name = "proposed_fact_value", columnDefinition = "text")
    private String proposedFactValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_value_json", columnDefinition = "jsonb")
    private JsonNode proposedValueJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "removed_snapshot_entries_json", columnDefinition = "jsonb")
    private JsonNode removedSnapshotEntriesJson;

    @Column(name = "comparison_reason", columnDefinition = "text")
    private String comparisonReason;

    @Column(name = "comparison_base_snapshot_version")
    private Long comparisonBaseSnapshotVersion;

    @Column(name = "comparison_context_hash", length = 64)
    private String comparisonContextHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_comparison_json", columnDefinition = "jsonb")
    private JsonNode rawComparisonJson;

    @Column(name = "compared_at")
    private LocalDateTime comparedAt;

    @Column(name = "comparison_error_message", columnDefinition = "text")
    private String comparisonErrorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "comparison_failure_code", length = 60)
    private AnalysisFailureCode comparisonFailureCode;

    private SettingCandidate(
            Work work,
            Episode episode,
            UUID sourceChunkId,
            AnalysisJob analysisJob,
            SettingCandidateKind candidateKind,
            SettingEntityType entityType,
            String entityName,
            String rawEntityMention,
            UUID matchedCharacterId,
            SettingCandidateMatchStatus matchStatus,
            String attributeName,
            String attributeValue,
            SettingValueType valueType,
            JsonNode valueJson,
            JsonNode evidenceSpans,
            BigDecimal confidence,
            JsonNode rawAiResultJson
    ) {
        this.work = work;
        this.episode = episode;
        this.sourceChunkId = sourceChunkId;
        this.analysisJob = analysisJob;
        this.candidateKind = candidateKind;
        this.entityType = entityType;
        this.entityName = entityName;
        this.rawEntityMention = rawEntityMention;
        this.matchedCharacterId = matchedCharacterId;
        this.matchStatus = matchStatus == null ? SettingCandidateMatchStatus.UNRESOLVED : matchStatus;
        this.attributeName = attributeName;
        this.attributeValue = attributeValue;
        this.valueType = valueType;
        this.valueJson = valueJson;
        this.evidenceSpans = evidenceSpans;
        this.confidence = confidence;
        this.reviewStatus = SettingCandidateReviewStatus.PENDING_REVIEW;
        this.rawAiResultJson = rawAiResultJson;
        this.comparisonStatus = initialComparisonStatus(candidateKind, this.matchStatus, matchedCharacterId);
    }

    public static SettingCandidate create(
            Work work,
            Episode episode,
            UUID sourceChunkId,
            AnalysisJob analysisJob,
            SettingEntityType entityType,
            String entityName,
            String attributeName,
            String attributeValue,
            SettingValueType valueType,
            JsonNode valueJson,
            JsonNode evidenceSpans,
            BigDecimal confidence,
            JsonNode rawAiResultJson
    ) {
        return new SettingCandidate(
                work,
                episode,
                sourceChunkId,
                analysisJob,
                SettingCandidateKind.SETTING,
                entityType,
                entityName,
                null,
                null,
                SettingCandidateMatchStatus.UNRESOLVED,
                attributeName,
                attributeValue,
                valueType,
                valueJson,
                evidenceSpans,
                confidence,
                rawAiResultJson
        );
    }

    public static SettingCandidate create(
            Work work,
            Episode episode,
            UUID sourceChunkId,
            AnalysisJob analysisJob,
            SettingEntityType entityType,
            String entityName,
            String rawEntityMention,
            UUID matchedCharacterId,
            SettingCandidateMatchStatus matchStatus,
            String attributeName,
            String attributeValue,
            SettingValueType valueType,
            JsonNode valueJson,
            JsonNode evidenceSpans,
            BigDecimal confidence,
            JsonNode rawAiResultJson
    ) {
        return new SettingCandidate(
                work,
                episode,
                sourceChunkId,
                analysisJob,
                SettingCandidateKind.SETTING,
                entityType,
                entityName,
                rawEntityMention,
                matchedCharacterId,
                matchStatus,
                attributeName,
                attributeValue,
                valueType,
                valueJson,
                evidenceSpans,
                confidence,
                rawAiResultJson
        );
    }

    public static SettingCandidate createCharacterDiscovery(
            Work work,
            Episode episode,
            UUID sourceChunkId,
            AnalysisJob analysisJob,
            String entityName,
            String rawEntityMention,
            UUID matchedCharacterId,
            SettingCandidateMatchStatus matchStatus,
            JsonNode evidenceSpans,
            BigDecimal confidence,
            JsonNode rawAiResultJson
    ) {
        return new SettingCandidate(
                work,
                episode,
                sourceChunkId,
                analysisJob,
                SettingCandidateKind.CHARACTER_DISCOVERY,
                SettingEntityType.CHARACTER,
                entityName,
                rawEntityMention,
                matchedCharacterId,
                matchStatus,
                null,
                null,
                null,
                null,
                evidenceSpans,
                confidence,
                rawAiResultJson
        );
    }

    public boolean confirm() {
        return transitionReviewStatus(SettingCandidateReviewStatus.CONFIRMED);
    }

    public boolean dismiss() {
        // Worker가 비교 결과를 쓰는 도중 사용자 무시가 끼어들면 마지막 flush가 제안을 되살릴 수 있다.
        if (comparisonStatus == CharacterFactComparisonStatus.PROCESSING) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
        boolean dismissed = transitionReviewStatus(SettingCandidateReviewStatus.DISMISSED);
        if (dismissed) {
            clearComparisonProposal();
            comparisonStatus = CharacterFactComparisonStatus.NOT_REQUIRED;
        }
        return dismissed;
    }

    public void updateReviewContent(
            String attributeName,
            String attributeValue,
            JsonNode valueJson
    ) {
        validateReviewContentEditable();

        this.attributeName = attributeName;
        this.attributeValue = attributeValue;
        this.valueJson = valueJson;
        requestComparisonAfterCandidateChange();
    }

    public void matchExistingCharacter(WorkCharacter character) {
        // 분석 또는 사용자가 기존 캐릭터를 선택한 연결은 신규 생성 연결과 구분한다.
        validateEditable();

        // 같은 캐릭터를 다시 선택한 경우 비교 문맥은 달라지지 않는다. 자동 연결 표시는
        // 사용자 확인 연결로 정리하되, 완료된 비교 제안을 지우거나 새 LLM Job을 만들지 않는다.
        if (Objects.equals(matchedCharacterId, character.getId())) {
            applyCharacterMatch(character, SettingCandidateMatchStatus.MATCHED);
            return;
        }
        applyCharacterMatch(character, SettingCandidateMatchStatus.MATCHED);
        requestComparisonAfterCandidateChange();
    }

    public void autoMatchSameNameCharacter(WorkCharacter character) {
        // 이번 확정에서 새로 생성한 캐릭터의 같은 이름 형제 후보에 신규 연결 이력을 남긴다.
        validateEditable();

        applyCharacterMatch(character, SettingCandidateMatchStatus.AUTO_MATCHED_BY_NAME);
        requestComparisonAfterCandidateChange();
    }

    public void matchPromotedExistingCharacter(WorkCharacter character) {
        applyPromotedCharacterMatch(character, SettingCandidateMatchStatus.MATCHED);
    }

    public void matchPromotedNewCharacter(WorkCharacter character) {
        applyPromotedCharacterMatch(character, SettingCandidateMatchStatus.AUTO_MATCHED_BY_NAME);
    }

    private void applyPromotedCharacterMatch(
            WorkCharacter character,
            SettingCandidateMatchStatus targetMatchStatus
    ) {
        // confirm()이 먼저 확정한 UNRESOLVED 후보만 promotion 결과로 연결할 수 있다.
        if (reviewStatus != SettingCandidateReviewStatus.CONFIRMED
                || matchStatus != SettingCandidateMatchStatus.UNRESOLVED
                || matchedCharacterId != null) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT);
        }
        applyCharacterMatch(character, targetMatchStatus);
        requestComparisonAfterCandidateChange();
    }

    private void applyCharacterMatch(
            WorkCharacter character,
            SettingCandidateMatchStatus targetMatchStatus
    ) {
        this.entityName = character.getName();
        this.matchedCharacterId = character.getId();
        this.matchStatus = targetMatchStatus;
    }

    public void markAsNewCharacter(String entityName) {
        // 사용자가 기존 매칭을 취소하고 신규 캐릭터로 판단한 상태다. 실제 생성은 confirm까지 미룬다.
        validateEditable();

        this.entityName = entityName;
        this.matchedCharacterId = null;
        this.matchStatus = SettingCandidateMatchStatus.UNRESOLVED;
        markWaitingForCharacterMatch();
    }

    public void startComparison() {
        validatePendingReview(CharacterErrorCode.SETTING_CANDIDATE_NOT_EDITABLE);
        if (isCharacterDiscovery()
                || comparisonStatus != CharacterFactComparisonStatus.PENDING
                || matchedCharacterId == null) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
        comparisonStatus = CharacterFactComparisonStatus.PROCESSING;
        comparisonErrorMessage = null;
        comparisonFailureCode = null;
    }

    public void quarantineInvalidComparison() {
        validateReviewContentEditable();
        if (comparisonStatus != CharacterFactComparisonStatus.PENDING) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
        clearComparisonProposal();
        comparisonStatus = CharacterFactComparisonStatus.NOT_REQUIRED;
    }

    public void recordComparisonContext(long snapshotVersion, String contextHash) {
        if (comparisonStatus != CharacterFactComparisonStatus.PROCESSING) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
        comparisonBaseSnapshotVersion = snapshotVersion;
        comparisonContextHash = Objects.requireNonNull(contextHash);
    }

    public void completeComparison(
            CharacterFactOperation operation,
            CharacterFactType targetFactType,
            String targetFactKey,
            String proposedFactValue,
            JsonNode proposedValueJson,
            JsonNode removedSnapshotEntriesJson,
            CharacterFactTemporalScope temporalScope,
            String comparisonReason,
            JsonNode rawComparisonJson,
            LocalDateTime comparedAt
    ) {
        validatePendingReview(CharacterErrorCode.SETTING_CANDIDATE_NOT_EDITABLE);
        if (comparisonStatus != CharacterFactComparisonStatus.PROCESSING) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
        this.suggestedOperation = Objects.requireNonNull(operation);
        this.comparisonTargetFactType = targetFactType;
        this.comparisonTargetFactKey = normalizeNullable(targetFactKey);
        this.proposedFactValue = normalizeNullable(proposedFactValue);
        this.proposedValueJson = proposedValueJson;
        this.removedSnapshotEntriesJson = removedSnapshotEntriesJson;
        this.temporalScope = Objects.requireNonNull(temporalScope);
        this.comparisonReason = normalizeNullable(comparisonReason);
        this.rawComparisonJson = rawComparisonJson;
        this.comparedAt = Objects.requireNonNull(comparedAt);
        this.comparisonStatus = CharacterFactComparisonStatus.COMPLETED;
        this.comparisonErrorMessage = null;
        this.comparisonFailureCode = null;
    }

    public void failComparison(String errorMessage) {
        failComparison(AnalysisFailureCode.UNEXPECTED_ERROR, errorMessage);
    }

    public void failComparison(AnalysisFailureCode failureCode, String errorMessage) {
        validatePendingReview(CharacterErrorCode.SETTING_CANDIDATE_NOT_EDITABLE);
        if (comparisonStatus != CharacterFactComparisonStatus.PENDING
                && comparisonStatus != CharacterFactComparisonStatus.PROCESSING) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
        comparisonStatus = CharacterFactComparisonStatus.FAILED;
        comparisonFailureCode = AnalysisFailureCode.orUnexpected(failureCode);
        comparisonErrorMessage = Objects.requireNonNull(errorMessage).trim();
    }

    public void recoverExpiredComparison() {
        if (isPendingReview() && comparisonStatus == CharacterFactComparisonStatus.PROCESSING) {
            comparisonStatus = CharacterFactComparisonStatus.PENDING;
            comparisonErrorMessage = null;
            comparisonFailureCode = null;
        }
    }

    public void markRecomparisonRequired(String reason) {
        validatePendingReview(CharacterErrorCode.SETTING_CANDIDATE_NOT_EDITABLE);
        clearComparisonProposal();
        comparisonStatus = CharacterFactComparisonStatus.RECOMPARISON_REQUIRED;
        comparisonErrorMessage = normalizeNullable(reason);
    }

    public void requestComparison() {
        validatePendingReview(CharacterErrorCode.SETTING_CANDIDATE_NOT_EDITABLE);
        clearComparisonProposal();
        comparisonStatus = matchedCharacterId == null
                ? CharacterFactComparisonStatus.WAITING_FOR_CHARACTER_MATCH
                : CharacterFactComparisonStatus.PENDING;
    }

    public boolean isComparisonCompleted() {
        return comparisonStatus == CharacterFactComparisonStatus.COMPLETED;
    }

    public boolean isPendingReview() {
        return reviewStatus == SettingCandidateReviewStatus.PENDING_REVIEW;
    }

    public boolean isCharacterDiscovery() {
        return candidateKind == SettingCandidateKind.CHARACTER_DISCOVERY;
    }

    public void validateReviewContentEditable() {
        validateEditable();
        if (isCharacterDiscovery()) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_CONTENT_NOT_EDITABLE);
        }
    }

    public void validateEditable() {
        validatePendingReview(CharacterErrorCode.SETTING_CANDIDATE_NOT_EDITABLE);
    }

    private boolean transitionReviewStatus(SettingCandidateReviewStatus targetStatus) {
        // 같은 action 재요청은 성공 가능한 no-op으로 두고, 호출자가 부수효과를 생략하도록 false를 반환한다.
        if (reviewStatus == targetStatus) {
            return false;
        }

        // 실제 상태 변경은 PENDING_REVIEW에서 CONFIRMED 또는 DISMISSED로 갈 때만 허용한다.
        validateReviewStatusTransition(targetStatus);
        this.reviewStatus = targetStatus;
        return true;
    }

    private void validateReviewStatusTransition(SettingCandidateReviewStatus targetStatus) {
        if (!isPendingReview()) {
            String message = String.format(
                    "현재 검토 상태가 %s(%s)인 설정 후보는 %s(%s)로 전환할 수 없습니다.",
                    reviewStatus,
                    reviewStatus.getToKorean(),
                    targetStatus,
                    targetStatus.getToKorean()
            );
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT, message);
        }
    }

    private void validatePendingReview(CharacterErrorCode errorCode) {
        if (!isPendingReview()) {
            throw new AppException(errorCode);
        }
    }

    private void requestComparisonAfterCandidateChange() {
        if (isCharacterDiscovery() || !isPendingReview()) {
            comparisonStatus = CharacterFactComparisonStatus.NOT_REQUIRED;
            return;
        }
        clearComparisonProposal();
        comparisonStatus = matchedCharacterId == null
                ? CharacterFactComparisonStatus.WAITING_FOR_CHARACTER_MATCH
                : CharacterFactComparisonStatus.PENDING;
    }

    private void markWaitingForCharacterMatch() {
        if (!isCharacterDiscovery() && isPendingReview()) {
            clearComparisonProposal();
            comparisonStatus = CharacterFactComparisonStatus.WAITING_FOR_CHARACTER_MATCH;
        }
    }

    private void clearComparisonProposal() {
        suggestedOperation = null;
        temporalScope = null;
        comparisonTargetFactType = null;
        comparisonTargetFactKey = null;
        proposedFactValue = null;
        proposedValueJson = null;
        removedSnapshotEntriesJson = null;
        comparisonReason = null;
        comparisonBaseSnapshotVersion = null;
        comparisonContextHash = null;
        rawComparisonJson = null;
        comparedAt = null;
        comparisonErrorMessage = null;
        comparisonFailureCode = null;
    }

    private static CharacterFactComparisonStatus initialComparisonStatus(
            SettingCandidateKind candidateKind,
            SettingCandidateMatchStatus matchStatus,
            UUID matchedCharacterId
    ) {
        if (candidateKind != SettingCandidateKind.SETTING) {
            return CharacterFactComparisonStatus.NOT_REQUIRED;
        }
        return matchedCharacterId != null
                && (matchStatus == SettingCandidateMatchStatus.MATCHED
                || matchStatus == SettingCandidateMatchStatus.AUTO_MATCHED_BY_NAME)
                ? CharacterFactComparisonStatus.PENDING
                : CharacterFactComparisonStatus.WAITING_FOR_CHARACTER_MATCH;
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
