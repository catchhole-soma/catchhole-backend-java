package org.monitoring.catchholebackend.domain.worldsetting.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingNameNormalizer;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonReviewReason;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingConsolidationStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

/** 여러 원본 후보를 하나의 canonical 속성으로 정리한 권위 있는 비교 결과다. */
@Getter
@Entity
@Table(
        name = "world_setting_comparison_decisions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_world_setting_comparison_decisions_ref",
                columnNames = {"comparison_batch_id", "decision_ref"}
        ),
        indexes = {
                @Index(
                        name = "idx_world_setting_comparison_decisions_batch",
                        columnList = "comparison_batch_id,decision_ref,id"
                ),
                @Index(
                        name = "idx_world_setting_comparison_decisions_target",
                        columnList = "target_world_setting_id"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorldSettingComparisonDecision extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "comparison_batch_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_world_setting_comparison_decisions_batch")
    )
    private WorldSettingComparisonBatch comparisonBatch;

    @Column(name = "decision_ref", nullable = false, updatable = false, length = 20)
    private String decisionRef;

    @Column(name = "canonical_subject_name", nullable = false, updatable = false, length = 100)
    private String canonicalSubjectName;

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    @JoinColumn(
            name = "target_world_setting_id",
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_world_setting_comparison_decisions_target")
    )
    private WorldSetting targetWorldSetting;

    @Column(name = "matched_scope_name", updatable = false, length = 100)
    private String matchedScopeName;

    @Column(name = "matched_property_name", updatable = false, length = 100)
    private String matchedPropertyName;

    @Enumerated(EnumType.STRING)
    @Column(name = "consolidation_status", nullable = false, updatable = false, length = 20)
    private WorldSettingConsolidationStatus consolidationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "suggested_operation", nullable = false, updatable = false, length = 30)
    private WorldSettingSuggestedOperation suggestedOperation;

    @Enumerated(EnumType.STRING)
    @Column(name = "comparison_review_reason", updatable = false, length = 40)
    private WorldSettingComparisonReviewReason comparisonReviewReason;

    @Column(name = "proposed_scope_name", updatable = false, length = 100)
    private String proposedScopeName;

    @Column(name = "proposed_setting_name", nullable = false, updatable = false, length = 100)
    private String proposedSettingName;

    @Column(name = "before_value", updatable = false, columnDefinition = "text")
    private String beforeValue;

    @Column(name = "proposed_value", nullable = false, updatable = false, columnDefinition = "text")
    private String proposedValue;

    @Column(name = "comparison_reason", nullable = false, updatable = false, columnDefinition = "text")
    private String comparisonReason;

    @Column(name = "base_world_setting_version", updatable = false)
    private Long baseWorldSettingVersion;

    // AI가 함께 범위로 이동할 root 설정의 비교 시점 이름·값 snapshot이다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "existing_root_property_move_snapshots",
            nullable = false,
            updatable = false,
            columnDefinition = "jsonb"
    )
    private JsonNode existingRootPropertyMoveSnapshotsJson;

    @Column(name = "root_property_moves_applied_world_setting_version")
    private Long rootPropertyMovesAppliedWorldSettingVersion;

    @Column(name = "root_property_moves_disabled", nullable = false)
    private boolean rootPropertyMovesDisabled;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_comparison_json", updatable = false, columnDefinition = "jsonb")
    private JsonNode rawComparisonJson;

    private WorldSettingComparisonDecision(
            WorldSettingComparisonBatch comparisonBatch,
            String decisionRef,
            String canonicalSubjectName,
            WorldSetting targetWorldSetting,
            String matchedScopeName,
            String matchedPropertyName,
            WorldSettingConsolidationStatus consolidationStatus,
            WorldSettingSuggestedOperation suggestedOperation,
            WorldSettingComparisonReviewReason comparisonReviewReason,
            String proposedScopeName,
            String proposedSettingName,
            String beforeValue,
            String proposedValue,
            String comparisonReason,
            List<ExistingRootPropertyMoveSnapshot> existingRootPropertyMoveSnapshots,
            JsonNode rawComparisonJson
    ) {
        this.comparisonBatch = Objects.requireNonNull(comparisonBatch);
        this.decisionRef = required(decisionRef, 20);
        this.canonicalSubjectName = required(canonicalSubjectName, 100);
        this.targetWorldSetting = targetWorldSetting;
        this.matchedScopeName = optional(matchedScopeName, 100);
        this.matchedPropertyName = optional(matchedPropertyName, 100);
        this.consolidationStatus = Objects.requireNonNull(consolidationStatus);
        this.suggestedOperation = Objects.requireNonNull(suggestedOperation);
        this.comparisonReviewReason = comparisonReviewReason;
        this.proposedScopeName = optional(proposedScopeName, 100);
        this.proposedSettingName = required(proposedSettingName, 100);
        this.beforeValue = optionalValue(beforeValue);
        this.proposedValue = requiredValue(proposedValue);
        this.comparisonReason = requiredValue(comparisonReason);
        this.baseWorldSettingVersion = targetWorldSetting == null
                ? null
                : targetWorldSetting.getVersion();
        this.existingRootPropertyMoveSnapshotsJson = toSnapshotJson(
                existingRootPropertyMoveSnapshots
        );
        this.rawComparisonJson = rawComparisonJson;
    }

    public static WorldSettingComparisonDecision create(
            WorldSettingComparisonBatch comparisonBatch,
            String decisionRef,
            String canonicalSubjectName,
            WorldSetting targetWorldSetting,
            String matchedScopeName,
            String matchedPropertyName,
            WorldSettingConsolidationStatus consolidationStatus,
            WorldSettingSuggestedOperation suggestedOperation,
            WorldSettingComparisonReviewReason comparisonReviewReason,
            String proposedScopeName,
            String proposedSettingName,
            String beforeValue,
            String proposedValue,
            String comparisonReason,
            JsonNode rawComparisonJson
    ) {
        return create(
                comparisonBatch,
                decisionRef,
                canonicalSubjectName,
                targetWorldSetting,
                matchedScopeName,
                matchedPropertyName,
                consolidationStatus,
                suggestedOperation,
                comparisonReviewReason,
                proposedScopeName,
                proposedSettingName,
                beforeValue,
                proposedValue,
                comparisonReason,
                List.of(),
                rawComparisonJson
        );
    }

    public static WorldSettingComparisonDecision create(
            WorldSettingComparisonBatch comparisonBatch,
            String decisionRef,
            String canonicalSubjectName,
            WorldSetting targetWorldSetting,
            String matchedScopeName,
            String matchedPropertyName,
            WorldSettingConsolidationStatus consolidationStatus,
            WorldSettingSuggestedOperation suggestedOperation,
            WorldSettingComparisonReviewReason comparisonReviewReason,
            String proposedScopeName,
            String proposedSettingName,
            String beforeValue,
            String proposedValue,
            String comparisonReason,
            List<ExistingRootPropertyMoveSnapshot> existingRootPropertyMoveSnapshots,
            JsonNode rawComparisonJson
    ) {
        return new WorldSettingComparisonDecision(
                comparisonBatch,
                decisionRef,
                canonicalSubjectName,
                targetWorldSetting,
                matchedScopeName,
                matchedPropertyName,
                consolidationStatus,
                suggestedOperation,
                comparisonReviewReason,
                proposedScopeName,
                proposedSettingName,
                beforeValue,
                proposedValue,
                comparisonReason,
                existingRootPropertyMoveSnapshots,
                rawComparisonJson
        );
    }

    public List<ExistingRootPropertyMoveSnapshot> getExistingRootPropertyMoveSnapshots() {
        if (existingRootPropertyMoveSnapshotsJson == null
                || !existingRootPropertyMoveSnapshotsJson.isArray()) {
            return List.of();
        }
        List<ExistingRootPropertyMoveSnapshot> snapshots = new ArrayList<>();
        existingRootPropertyMoveSnapshotsJson.forEach(item -> snapshots.add(
                new ExistingRootPropertyMoveSnapshot(
                        required(item.path("settingName").asText(null), 100),
                        requiredValue(item.path("beforeValue").asText(null))
                )
        ));
        return List.copyOf(snapshots);
    }

    public List<String> getExistingRootPropertyNamesToMove() {
        return getExistingRootPropertyMoveSnapshots().stream()
                .map(ExistingRootPropertyMoveSnapshot::settingName)
                .toList();
    }

    public List<String> getEligibleExistingRootPropertyNamesToMove() {
        return rootPropertyMovesDisabled
                ? List.of()
                : getExistingRootPropertyNamesToMove();
    }

    public void disableRootPropertyMoves() {
        if (!getExistingRootPropertyMoveSnapshots().isEmpty()) {
            rootPropertyMovesDisabled = true;
        }
    }

    public void markRootPropertyMovesApplied(long worldSettingVersion) {
        if (worldSettingVersion < 0
                || rootPropertyMovesDisabled
                || getExistingRootPropertyMoveSnapshots().isEmpty()) {
            throw new IllegalArgumentException("Root property move application is invalid.");
        }
        if (rootPropertyMovesAppliedWorldSettingVersion == null) {
            rootPropertyMovesAppliedWorldSettingVersion = worldSettingVersion;
            return;
        }
        if (rootPropertyMovesAppliedWorldSettingVersion != worldSettingVersion) {
            throw new IllegalStateException("Root property moves were already applied.");
        }
    }

    private static JsonNode toSnapshotJson(
            List<ExistingRootPropertyMoveSnapshot> snapshots
    ) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        if (snapshots == null) {
            return result;
        }
        Set<String> settingNames = new HashSet<>();
        for (ExistingRootPropertyMoveSnapshot snapshot : snapshots) {
            String settingName = required(snapshot.settingName(), 100);
            String beforeValue = requiredValue(snapshot.beforeValue());
            if (!settingNames.add(WorldSettingNameNormalizer.duplicateKey(settingName))) {
                throw new IllegalArgumentException("Root property move snapshot is duplicated.");
            }
            result.addObject()
                    .put("settingName", settingName)
                    .put("beforeValue", beforeValue);
        }
        return result;
    }

    private static String required(String value, int maxLength) {
        String normalized = optional(value, maxLength);
        if (normalized == null) {
            throw new IllegalArgumentException("Required comparison decision value is blank.");
        }
        return normalized;
    }

    private static String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = WorldSettingNameNormalizer.displayName(value);
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException("Comparison decision name is too long.");
        }
        return normalized;
    }

    private static String requiredValue(String value) {
        String normalized = optionalValue(value);
        if (normalized == null) {
            throw new IllegalArgumentException("Required comparison decision text is blank.");
        }
        return normalized;
    }

    private static String optionalValue(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ExistingRootPropertyMoveSnapshot(String settingName, String beforeValue) {
    }
}
