package org.monitoring.catchholebackend.domain.worldsetting.entity;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.worldsetting.exception.WorldSettingErrorCode;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingNameNormalizer;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingConsolidationStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
import org.monitoring.catchholebackend.global.exception.AppException;

@Getter
@Entity
@Table(
        name = "world_setting_candidates",
        indexes = {
                @Index(name = "idx_world_setting_candidates_job_review", columnList = "analysis_job_id,review_status"),
                @Index(
                        name = "idx_world_setting_candidates_work_review_category",
                        columnList = "work_id,review_status,category,created_at,id"
                ),
                @Index(name = "idx_world_setting_candidates_source_episode", columnList = "source_episode_id"),
                @Index(name = "idx_world_setting_candidates_target", columnList = "target_world_setting_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorldSettingCandidate extends BaseEntity {

    private static final int NAME_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "work_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_world_setting_candidates_work")
    )
    private Work work;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "source_episode_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_world_setting_candidates_source_episode")
    )
    private Episode sourceEpisode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "analysis_job_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_world_setting_candidates_analysis_job")
    )
    private AnalysisJob analysisJob;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 40)
    private WorldSettingCategory category;

    @Column(name = "subject_name", nullable = false, length = NAME_MAX_LENGTH)
    private String subjectName;

    // 대상 아래의 선택적 한 단계 범위다. null이면 루트 설정을 뜻한다.
    @Column(name = "scope_name", length = NAME_MAX_LENGTH)
    private String scopeName;

    @Column(name = "setting_name", nullable = false, length = NAME_MAX_LENGTH)
    private String settingName;

    @Column(name = "extracted_value", nullable = false, columnDefinition = "text")
    private String extractedValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_spans", nullable = false, columnDefinition = "jsonb")
    private JsonNode evidenceSpans;

    @Column(name = "extraction_confidence", precision = 5, scale = 4)
    private BigDecimal extractionConfidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_extraction_json", columnDefinition = "jsonb")
    private JsonNode rawExtractionJson;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "target_world_setting_id",
            foreignKey = @ForeignKey(name = "fk_world_setting_candidates_target")
    )
    private WorldSetting targetWorldSetting;

    // 여러 1차 추출값을 2차 LLM이 안전하게 하나로 정리했는지 나타낸다.
    @Enumerated(EnumType.STRING)
    @Column(name = "consolidation_status", nullable = false, length = 20)
    private WorldSettingConsolidationStatus consolidationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "suggested_operation", length = 30)
    private WorldSettingOperation suggestedOperation;

    @Column(name = "proposed_setting_name", length = NAME_MAX_LENGTH)
    private String proposedSettingName;

    @Column(name = "proposed_scope_name", length = NAME_MAX_LENGTH)
    private String proposedScopeName;

    @Column(name = "before_value", columnDefinition = "text")
    private String beforeValue;

    @Column(name = "proposed_value", columnDefinition = "text")
    private String proposedValue;

    @Column(name = "comparison_reason", columnDefinition = "text")
    private String comparisonReason;

    @Column(name = "base_world_setting_version")
    private Long baseWorldSettingVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_comparison_json", columnDefinition = "jsonb")
    private JsonNode rawComparisonJson;

    @Column(name = "compared_at")
    private LocalDateTime comparedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "comparison_status", nullable = false, length = 30)
    private WorldSettingComparisonStatus comparisonStatus;

    @Column(name = "comparison_error_message", columnDefinition = "text")
    private String comparisonErrorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 30)
    private WorldSettingReviewStatus reviewStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_operation", length = 30)
    private WorldSettingOperation finalOperation;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_category", length = 40)
    private WorldSettingCategory finalCategory;

    @Column(name = "final_subject_name", length = NAME_MAX_LENGTH)
    private String finalSubjectName;

    @Column(name = "final_scope_name", length = NAME_MAX_LENGTH)
    private String finalScopeName;

    @Column(name = "final_setting_name", length = NAME_MAX_LENGTH)
    private String finalSettingName;

    @Column(name = "final_value", columnDefinition = "text")
    private String finalValue;

    @Column(name = "review_note", columnDefinition = "text")
    private String reviewNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "reviewed_by",
            foreignKey = @ForeignKey(name = "fk_world_setting_candidates_reviewer")
    )
    private Member reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "applied_world_setting_version")
    private Long appliedWorldSettingVersion;

    private WorldSettingCandidate(
            Work work,
            Episode sourceEpisode,
            AnalysisJob analysisJob,
            WorldSettingCategory category,
            String subjectName,
            String scopeName,
            String settingName,
            String extractedValue,
            JsonNode evidenceSpans,
            BigDecimal extractionConfidence,
            JsonNode rawExtractionJson
    ) {
        this.work = Objects.requireNonNull(work);
        this.sourceEpisode = Objects.requireNonNull(sourceEpisode);
        this.analysisJob = Objects.requireNonNull(analysisJob);
        this.category = Objects.requireNonNull(category);
        this.subjectName = requiredName(subjectName);
        this.scopeName = optionalName(scopeName);
        this.settingName = requiredName(settingName);
        this.extractedValue = requiredValue(extractedValue);
        this.evidenceSpans = requiredEvidenceSpans(evidenceSpans);
        this.extractionConfidence = validConfidence(extractionConfidence);
        this.rawExtractionJson = rawExtractionJson;
        this.consolidationStatus = WorldSettingConsolidationStatus.SINGLE;
        this.comparisonStatus = WorldSettingComparisonStatus.PENDING;
        this.reviewStatus = WorldSettingReviewStatus.PENDING_REVIEW;
    }

    public static WorldSettingCandidate create(
            Work work,
            Episode sourceEpisode,
            AnalysisJob analysisJob,
            WorldSettingCategory category,
            String subjectName,
            String settingName,
            String extractedValue,
            JsonNode evidenceSpans,
            BigDecimal extractionConfidence,
            JsonNode rawExtractionJson
    ) {
        return create(
                work,
                sourceEpisode,
                analysisJob,
                category,
                subjectName,
                null,
                settingName,
                extractedValue,
                evidenceSpans,
                extractionConfidence,
                rawExtractionJson
        );
    }

    public static WorldSettingCandidate create(
            Work work,
            Episode sourceEpisode,
            AnalysisJob analysisJob,
            WorldSettingCategory category,
            String subjectName,
            String scopeName,
            String settingName,
            String extractedValue,
            JsonNode evidenceSpans,
            BigDecimal extractionConfidence,
            JsonNode rawExtractionJson
    ) {
        return new WorldSettingCandidate(
                work,
                sourceEpisode,
                analysisJob,
                category,
                subjectName,
                scopeName,
                settingName,
                extractedValue,
                evidenceSpans,
                extractionConfidence,
                rawExtractionJson
        );
    }

    public void startComparison() {
        validatePendingReview();
        if (comparisonStatus != WorldSettingComparisonStatus.PENDING) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
        comparisonStatus = WorldSettingComparisonStatus.PROCESSING;
        comparisonErrorMessage = null;
    }

    public void completeComparison(
            WorldSetting targetWorldSetting,
            WorldSettingOperation suggestedOperation,
            String proposedSettingName,
            String beforeValue,
            String proposedValue,
            String comparisonReason,
            JsonNode rawComparisonJson,
            LocalDateTime comparedAt
    ) {
        completeComparison(
                targetWorldSetting,
                WorldSettingConsolidationStatus.SINGLE,
                suggestedOperation,
                null,
                proposedSettingName,
                beforeValue,
                proposedValue,
                comparisonReason,
                rawComparisonJson,
                comparedAt
        );
    }

    public void completeComparison(
            WorldSetting targetWorldSetting,
            WorldSettingConsolidationStatus consolidationStatus,
            WorldSettingOperation suggestedOperation,
            String proposedSettingName,
            String beforeValue,
            String proposedValue,
            String comparisonReason,
            JsonNode rawComparisonJson,
            LocalDateTime comparedAt
    ) {
        completeComparison(
                targetWorldSetting,
                consolidationStatus,
                suggestedOperation,
                null,
                proposedSettingName,
                beforeValue,
                proposedValue,
                comparisonReason,
                rawComparisonJson,
                comparedAt
        );
    }

    public void completeComparison(
            WorldSetting targetWorldSetting,
            WorldSettingConsolidationStatus consolidationStatus,
            WorldSettingOperation suggestedOperation,
            String proposedScopeName,
            String proposedSettingName,
            String beforeValue,
            String proposedValue,
            String comparisonReason,
            JsonNode rawComparisonJson,
            LocalDateTime comparedAt
    ) {
        validatePendingReview();
        validateProcessingComparison();
        this.targetWorldSetting = targetWorldSetting;
        this.consolidationStatus = Objects.requireNonNull(consolidationStatus);
        this.suggestedOperation = Objects.requireNonNull(suggestedOperation);
        this.proposedScopeName = optionalName(proposedScopeName);
        this.proposedSettingName = requiredName(proposedSettingName);
        this.beforeValue = optionalValue(beforeValue);
        this.proposedValue = requiredValue(proposedValue);
        this.comparisonReason = optionalValue(comparisonReason);
        this.baseWorldSettingVersion = targetWorldSetting == null ? null : targetWorldSetting.getVersion();
        this.rawComparisonJson = rawComparisonJson;
        this.comparedAt = Objects.requireNonNull(comparedAt);
        this.comparisonStatus = WorldSettingComparisonStatus.COMPLETED;
        this.comparisonErrorMessage = null;
    }

    public void failComparison(String errorMessage) {
        validatePendingReview();
        validateProcessingComparison();
        comparisonStatus = WorldSettingComparisonStatus.FAILED;
        comparisonErrorMessage = requiredValue(errorMessage);
    }

    public void requestRecomparison() {
        validatePendingReview();
        if (comparisonStatus == WorldSettingComparisonStatus.PROCESSING) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
        clearComparisonProposal();
        comparisonStatus = WorldSettingComparisonStatus.PENDING;
    }

    public void recoverExpiredComparison() {
        validatePendingReview();
        if (comparisonStatus == WorldSettingComparisonStatus.PROCESSING) {
            comparisonStatus = WorldSettingComparisonStatus.PENDING;
            comparisonErrorMessage = null;
        }
    }

    public void markRecomparisonRequired() {
        markRecomparisonRequired("확정본이 변경되어 재비교가 필요합니다.");
    }

    public void markRecomparisonRequired(String reason) {
        validatePendingReview();
        comparisonStatus = WorldSettingComparisonStatus.RECOMPARISON_REQUIRED;
        comparisonErrorMessage = requiredValue(reason);
    }

    public void updateDecisionDraft(
            WorldSettingOperation operation,
            WorldSettingCategory category,
            String subjectName,
            String scopeName,
            String settingName,
            String value,
            String reviewNote
    ) {
        validatePendingReview();
        if (comparisonStatus != WorldSettingComparisonStatus.COMPLETED) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_NOT_READY);
        }
        finalOperation = Objects.requireNonNull(operation);
        finalCategory = Objects.requireNonNull(category);
        finalSubjectName = requiredName(subjectName);
        finalScopeName = optionalName(scopeName);
        finalSettingName = requiredName(settingName);
        finalValue = requiredValue(value);
        this.reviewNote = optionalValue(reviewNote);
        reviewedBy = null;
        reviewedAt = null;
        appliedWorldSettingVersion = null;
    }

    public WorldSettingOperation getEffectiveOperation() {
        return finalOperation == null ? suggestedOperation : finalOperation;
    }

    public WorldSettingCategory getEffectiveCategory() {
        if (finalCategory != null) {
            return finalCategory;
        }
        return targetWorldSetting == null ? category : targetWorldSetting.getCategory();
    }

    public String getEffectiveSubjectName() {
        if (finalSubjectName != null) {
            return finalSubjectName;
        }
        return targetWorldSetting == null ? subjectName : targetWorldSetting.getSubjectName();
    }

    public boolean confirm(
            WorldSettingOperation operation,
            WorldSettingCategory category,
            String subjectName,
            String settingName,
            String value,
            String reviewNote,
            Member reviewer,
            WorldSetting appliedWorldSetting
    ) {
        return confirm(
                operation,
                category,
                subjectName,
                null,
                settingName,
                value,
                reviewNote,
                reviewer,
                appliedWorldSetting
        );
    }

    public boolean confirm(
            WorldSettingOperation operation,
            WorldSettingCategory category,
            String subjectName,
            String scopeName,
            String settingName,
            String value,
            String reviewNote,
            Member reviewer,
            WorldSetting appliedWorldSetting
    ) {
        if (reviewStatus == WorldSettingReviewStatus.CONFIRMED) {
            if (matchesFinalDecision(operation, category, subjectName, scopeName, settingName, value)) {
                return false;
            }
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT);
        }
        if (reviewStatus == WorldSettingReviewStatus.DISMISSED) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT);
        }
        if (comparisonStatus != WorldSettingComparisonStatus.COMPLETED) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_NOT_READY);
        }
        if (operation == null || operation == WorldSettingOperation.EXCLUDE) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_OPERATION_INVALID);
        }

        finalOperation = operation;
        finalCategory = Objects.requireNonNull(category);
        finalSubjectName = requiredName(subjectName);
        finalScopeName = optionalName(scopeName);
        finalSettingName = requiredName(settingName);
        finalValue = requiredValue(value);
        this.reviewNote = optionalValue(reviewNote);
        reviewedBy = Objects.requireNonNull(reviewer);
        reviewedAt = LocalDateTime.now();
        targetWorldSetting = Objects.requireNonNull(appliedWorldSetting);
        appliedWorldSettingVersion = appliedWorldSetting.getVersion();
        reviewStatus = WorldSettingReviewStatus.CONFIRMED;
        return true;
    }

    public boolean dismiss(String reviewNote, Member reviewer) {
        if (reviewStatus == WorldSettingReviewStatus.DISMISSED) {
            return false;
        }
        if (reviewStatus == WorldSettingReviewStatus.CONFIRMED) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT);
        }
        if (comparisonStatus == WorldSettingComparisonStatus.PROCESSING) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }

        finalOperation = WorldSettingOperation.EXCLUDE;
        finalCategory = category;
        finalSubjectName = subjectName;
        finalScopeName = proposedSettingName == null ? scopeName : proposedScopeName;
        finalSettingName = proposedSettingName == null ? settingName : proposedSettingName;
        finalValue = proposedValue == null ? extractedValue : proposedValue;
        this.reviewNote = optionalValue(reviewNote);
        reviewedBy = Objects.requireNonNull(reviewer);
        reviewedAt = LocalDateTime.now();
        appliedWorldSettingVersion = null;
        reviewStatus = WorldSettingReviewStatus.DISMISSED;
        return true;
    }

    public boolean isPendingReview() {
        return reviewStatus == WorldSettingReviewStatus.PENDING_REVIEW;
    }

    private boolean matchesFinalDecision(
            WorldSettingOperation operation,
            WorldSettingCategory category,
            String subjectName,
            String scopeName,
            String settingName,
            String value
    ) {
        return finalOperation == operation
                && finalCategory == category
                && Objects.equals(finalSubjectName, requiredName(subjectName))
                && Objects.equals(finalScopeName, optionalName(scopeName))
                && Objects.equals(finalSettingName, requiredName(settingName))
                && Objects.equals(finalValue, requiredValue(value));
    }

    private void validatePendingReview() {
        if (!isPendingReview()) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_NOT_EDITABLE);
        }
    }

    private void validateProcessingComparison() {
        if (comparisonStatus != WorldSettingComparisonStatus.PROCESSING) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
        }
    }

    private void clearComparisonProposal() {
        targetWorldSetting = null;
        suggestedOperation = null;
        proposedScopeName = null;
        proposedSettingName = null;
        beforeValue = null;
        proposedValue = null;
        comparisonReason = null;
        baseWorldSettingVersion = null;
        rawComparisonJson = null;
        comparedAt = null;
        comparisonErrorMessage = null;
    }

    @PrePersist
    @PreUpdate
    private void validateEvidenceSpans() {
        requiredEvidenceSpans(evidenceSpans);
        validConfidence(extractionConfidence);
    }

    private static String requiredName(String value) {
        String normalized = WorldSettingNameNormalizer.displayName(value);
        if (normalized == null || normalized.isEmpty() || normalized.length() > NAME_MAX_LENGTH) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_INPUT_INVALID);
        }
        return normalized;
    }

    private static String optionalName(String value) {
        return value == null ? null : requiredName(value);
    }

    private static String requiredValue(String value) {
        String normalized = WorldSettingNameNormalizer.displayName(value);
        if (normalized == null || normalized.isEmpty()) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_INPUT_INVALID);
        }
        return normalized;
    }

    private static String optionalValue(String value) {
        String normalized = WorldSettingNameNormalizer.displayName(value);
        return normalized == null || normalized.isEmpty() ? null : normalized;
    }

    private static JsonNode requiredEvidenceSpans(JsonNode value) {
        if (value == null || !value.isArray()) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_INPUT_INVALID);
        }
        return value;
    }

    private static BigDecimal validConfidence(BigDecimal value) {
        if (value != null && (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0)) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_INPUT_INVALID);
        }
        return value;
    }
}
