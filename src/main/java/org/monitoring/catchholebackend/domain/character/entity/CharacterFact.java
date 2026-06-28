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
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Getter
@Entity
@Table(
        name = "character_facts",
        indexes = {
                @Index(name = "idx_character_facts_character_current", columnList = "character_id,is_current"),
                @Index(name = "idx_character_facts_character_key", columnList = "character_id,fact_type,fact_key"),
                @Index(name = "idx_character_facts_source_episode", columnList = "source_episode_id"),
                @Index(name = "idx_character_facts_job", columnList = "extracted_by_job_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CharacterFact extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "character_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_character_facts_character")
    )
    private WorkCharacter workCharacter;

    @Enumerated(EnumType.STRING)
    @Column(name = "fact_type", nullable = false, length = 30)
    private CharacterFactType factType;

    // 예: "age", "level", "strength", "skill.흑염.level", "item.검은단검.quantity"
    @Column(name = "fact_key", nullable = false, length = 150)
    private String factKey;

    // 원문에서 추출되거나 사용자가 확정한 표시값입니다. 예: "17", "12", "35", "OWNED"
    @Column(name = "fact_value", columnDefinition = "text")
    private String factValue;

    // 비교용 정규화 값입니다. 예: 숫자 문자열을 "12"로 통일하거나 보유 상태를 "OWNED"/"LOST"로 통일합니다.
    @Column(name = "normalized_value", columnDefinition = "text")
    private String normalizedValue;

    // 복합 설정 값입니다. 예: {"name": "검은단검", "quantity": 1, "state": "OWNED"}
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value_json", columnDefinition = "jsonb")
    private JsonNode valueJson;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "source_episode_id",
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_character_facts_source_episode")
    )
    private Episode sourceEpisode;

    // 청킹 엔티티가 생기기 전까지 원문 근거 청크 UUID만 보관합니다.
    @Column(name = "source_chunk_id")
    private UUID sourceChunkId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "extracted_by_job_id",
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_character_facts_analysis_job")
    )
    private AnalysisJob extractedByJob;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "is_current", nullable = false)
    private boolean isCurrent;

    @Column(name = "effective_from_episode_no")
    private Integer effectiveFromEpisodeNo;

    private CharacterFact(
            WorkCharacter workCharacter,
            CharacterFactType factType,
            String factKey,
            String factValue,
            String normalizedValue,
            JsonNode valueJson,
            Episode sourceEpisode,
            UUID sourceChunkId,
            AnalysisJob extractedByJob,
            BigDecimal confidence,
            Integer effectiveFromEpisodeNo
    ) {
        this.workCharacter = workCharacter;
        this.factType = factType;
        this.factKey = factKey;
        this.factValue = factValue;
        this.normalizedValue = normalizedValue;
        this.valueJson = valueJson;
        this.sourceEpisode = sourceEpisode;
        this.sourceChunkId = sourceChunkId;
        this.extractedByJob = extractedByJob;
        this.confidence = confidence;
        this.isCurrent = false;
        this.effectiveFromEpisodeNo = effectiveFromEpisodeNo;
    }

    public static CharacterFact create(
            WorkCharacter workCharacter,
            CharacterFactType factType,
            String factKey,
            String factValue,
            String normalizedValue,
            JsonNode valueJson,
            Episode sourceEpisode,
            UUID sourceChunkId,
            AnalysisJob extractedByJob,
            BigDecimal confidence,
            Integer effectiveFromEpisodeNo
    ) {
        return new CharacterFact(
                workCharacter,
                factType,
                factKey,
                factValue,
                normalizedValue,
                valueJson,
                sourceEpisode,
                sourceChunkId,
                extractedByJob,
                confidence,
                effectiveFromEpisodeNo
        );
    }

    public void markCurrent() {
        this.isCurrent = true;
    }

    public void markHistorical() {
        this.isCurrent = false;
    }
}
