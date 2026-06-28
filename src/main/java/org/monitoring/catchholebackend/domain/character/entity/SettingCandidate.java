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
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

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

    // 예: "level", "stats", "skills", "items", "current_status"
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
                attributeName,
                attributeValue,
                valueType,
                valueJson,
                evidenceSpans,
                confidence,
                rawAiResultJson
        );
    }

    public void confirm() {
        this.reviewStatus = SettingCandidateReviewStatus.CONFIRMED;
    }

    public void dismiss() {
        this.reviewStatus = SettingCandidateReviewStatus.DISMISSED;
    }

    public void updateReviewContent(
            String entityName,
            String attributeName,
            String attributeValue,
            SettingValueType valueType,
            JsonNode valueJson,
            JsonNode evidenceSpans
    ) {
        this.entityName = entityName;
        this.attributeName = attributeName;
        this.attributeValue = attributeValue;
        this.valueType = valueType;
        this.valueJson = valueJson;
        this.evidenceSpans = evidenceSpans;
    }

    public boolean isPendingReview() {
        return reviewStatus == SettingCandidateReviewStatus.PENDING_REVIEW;
    }
}
