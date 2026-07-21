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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "analysis_job_id",
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_setting_candidates_analysis_job")
    )
    private AnalysisJob analysisJob;

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
    @Column(name = "attribute_name", nullable = false, length = 100)
    private String attributeName;

    // 목록/검색 표시용 요약값입니다. 예: "12", "화염검", "근력 80 / 민첩 65"
    @Column(name = "attribute_value", columnDefinition = "text")
    private String attributeValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 30)
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

    private SettingCandidate(
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
        this.work = work;
        this.episode = episode;
        this.sourceChunkId = sourceChunkId;
        this.analysisJob = analysisJob;
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

    public boolean confirm() {
        return transitionReviewStatus(SettingCandidateReviewStatus.CONFIRMED);
    }

    public boolean dismiss() {
        return transitionReviewStatus(SettingCandidateReviewStatus.DISMISSED);
    }

    public void updateReviewContent(
            String attributeName,
            String attributeValue,
            SettingValueType valueType,
            JsonNode valueJson,
            JsonNode evidenceSpans
    ) {
        validateEditable();

        this.attributeName = attributeName;
        this.attributeValue = attributeValue;
        this.valueType = valueType;
        this.valueJson = valueJson;
        this.evidenceSpans = evidenceSpans;
    }

    public void matchExistingCharacter(WorkCharacter character) {
        // 사용자 연결 수정과 아직 검토 대기 중인 형제 후보 자동 연결에 사용한다.
        validateEditable();

        applyCharacterMatch(character);
    }

    public void matchPromotedCharacter(WorkCharacter character) {
        // confirm()이 먼저 CONFIRMED로 바꾼 현재 후보에 promotion이 결정한 최종 캐릭터를 기록한다.
        // 사용자 편집 경로가 확정 후보를 다시 연결하지 못하도록 UNRESOLVED + 미연결 상태까지 함께 검증한다.
        if (reviewStatus != SettingCandidateReviewStatus.CONFIRMED
                || matchStatus != SettingCandidateMatchStatus.UNRESOLVED
                || matchedCharacterId != null) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT);
        }
        applyCharacterMatch(character);
    }

    private void applyCharacterMatch(WorkCharacter character) {
        this.entityName = character.getName();
        this.matchedCharacterId = character.getId();
        this.matchStatus = SettingCandidateMatchStatus.MATCHED;
    }

    public void markAsNewCharacter(String entityName) {
        // 사용자가 기존 매칭을 취소하고 신규 캐릭터로 판단한 상태다. 실제 생성은 confirm까지 미룬다.
        validateEditable();

        this.entityName = entityName;
        this.matchedCharacterId = null;
        this.matchStatus = SettingCandidateMatchStatus.UNRESOLVED;
    }

    public boolean isPendingReview() {
        return reviewStatus == SettingCandidateReviewStatus.PENDING_REVIEW;
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
}
