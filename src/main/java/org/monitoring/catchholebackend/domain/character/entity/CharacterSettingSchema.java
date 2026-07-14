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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

/**
 * AI가 추출한 attributeName을 canonical schema key로 해석하기 위한 Registry 정책입니다.
 * 캐릭터의 실제 능력치나 작품 설정값은 저장하지 않습니다.
 */
@Getter
@Entity
@Table(
        name = "character_setting_schemas",
        indexes = @Index(
                name = "idx_character_setting_schemas_active_lookup",
                columnList = "work_id,enabled,schema_key"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CharacterSettingSchema extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // null이면 모든 작품에 적용하는 전역 schema이고, 값이 있으면 해당 작품에 key를 추가하는 schema입니다.
    // 작품 schema가 전역 schema를 override하거나 같은 key를 병합하는 정책은 현재 지원하지 않습니다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "work_id",
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_character_setting_schemas_work")
    )
    private Work work;

    // SettingCandidate.attributeName을 정규화한 뒤 CharacterFact.factKey로 사용할 canonical key입니다.
    // 예: "age", "stats.strength", "items.item"
    @Column(name = "schema_key", nullable = false, length = 100, updatable = false)
    private String schemaKey;

    // exact key와 alias로 매칭되지 않은 동적 속성을 수용하는 nullable pattern입니다. 예: "status.*"
    // exact → alias → pattern 매칭은 NVM-234에서 구현합니다.
    @Column(name = "attribute_pattern", length = 100)
    private String attributePattern;

    // Worker prompt나 화면에서 사람이 schema를 식별할 때 사용하는 이름입니다.
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    // 이 schema로 확정된 값이 CharacterFact에 저장될 때 사용할 상위 설정 유형입니다.
    @Enumerated(EnumType.STRING)
    @Column(name = "fact_type", nullable = false, length = 30)
    private CharacterFactType factType;

    // Worker가 추출해야 하는 값 타입이며, 실제 값 검증은 NVM-234에서 구현합니다.
    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 30)
    private SettingValueType valueType;

    // 값이 기준값, 보정값, 파생값 중 무엇을 의미하는지 나타냅니다.
    @Enumerated(EnumType.STRING)
    @Column(name = "value_semantics", nullable = false, length = 30)
    private CharacterSettingValueSemantics valueSemantics;

    // 같은 canonical key의 값을 snapshot에 반영할 방식입니다. 실제 병합은 NVM-233에서 구현합니다.
    @Enumerated(EnumType.STRING)
    @Column(name = "merge_policy", nullable = false, length = 30)
    private CharacterSettingMergePolicy mergePolicy;

    // schemaKey와 같은 값으로 정규화할 수 있는 문자열 alias 배열입니다.
    // DB 제약으로 JSON 배열만 허용하며, 비어 있으면 []을 저장합니다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "aliases_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode aliasesJson;

    // 공통 seed와 POC seed의 관리 출처를 구분합니다. Worker 포함 여부를 결정하는 값은 아닙니다.
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    private CharacterSettingSchemaSource source;

    // true인 전역 schema와 현재 작품 schema만 Worker claim payload에 포함합니다.
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    private CharacterSettingSchema(
            Work work,
            String schemaKey,
            String attributePattern,
            String displayName,
            CharacterFactType factType,
            SettingValueType valueType,
            CharacterSettingValueSemantics valueSemantics,
            CharacterSettingMergePolicy mergePolicy,
            JsonNode aliasesJson,
            CharacterSettingSchemaSource source,
            boolean enabled
    ) {
        this.work = work;
        this.schemaKey = schemaKey;
        this.attributePattern = attributePattern;
        this.displayName = displayName;
        this.factType = factType;
        this.valueType = valueType;
        this.valueSemantics = valueSemantics;
        this.mergePolicy = mergePolicy;
        this.aliasesJson = aliasesJson;
        this.source = source;
        this.enabled = enabled;
    }

    public static CharacterSettingSchema create(
            Work work,
            String schemaKey,
            String attributePattern,
            String displayName,
            CharacterFactType factType,
            SettingValueType valueType,
            CharacterSettingValueSemantics valueSemantics,
            CharacterSettingMergePolicy mergePolicy,
            JsonNode aliasesJson,
            CharacterSettingSchemaSource source,
            boolean enabled
    ) {
        return new CharacterSettingSchema(
                work,
                schemaKey,
                attributePattern,
                displayName,
                factType,
                valueType,
                valueSemantics,
                mergePolicy,
                aliasesJson,
                source,
                enabled
        );
    }
}
