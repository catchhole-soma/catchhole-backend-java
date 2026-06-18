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
import org.monitoring.catchholebackend.domain.character.type.CharacterReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Getter
@Entity
@Table(
        name = "characters",
        indexes = {
                @Index(name = "idx_characters_work_name", columnList = "work_id,name"),
                @Index(name = "idx_characters_work_status", columnList = "work_id,status")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkCharacter extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "work_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_characters_work")
    )
    private Work work;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "role_label", length = 50)
    private String roleLabel;

    @Column(name = "current_age")
    private Integer currentAge;

    @Column(name = "current_level")
    private Integer currentLevel;

    // 예: {"gender": "남성", "species": "인간", "affiliation": "북부 기사단", "description": "주인공"}
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile_json", columnDefinition = "jsonb")
    private JsonNode profileJson;

    // 예: {"strength": 80, "agility": 65, "mana": 120, "source": "3화 기준"}
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stats_json", columnDefinition = "jsonb")
    private JsonNode statsJson;

    // 예: [{"name": "화염검술", "level": 3, "effect": "화염 속성 공격력 증가"}]
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skills_json", columnDefinition = "jsonb")
    private JsonNode skillsJson;

    // 예: [{"name": "화염검", "type": "weapon", "equipped": true, "quantity": 1}]
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items_json", columnDefinition = "jsonb")
    private JsonNode itemsJson;

    // 예: [{"episodeNumber": 5, "status": "부상", "active": true, "description": "왼팔 골절"}]
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "statuses_json", columnDefinition = "jsonb")
    private JsonNode statusesJson;

    @Column(name = "first_appearance_episode_id")
    private UUID firstAppearanceEpisodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 30)
    private CharacterReviewStatus reviewStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private CharacterStatus status;

    private WorkCharacter(
            Work work,
            String name,
            String roleLabel,
            Integer currentAge,
            Integer currentLevel,
            JsonNode profileJson,
            JsonNode statsJson,
            JsonNode skillsJson,
            JsonNode itemsJson,
            JsonNode statusesJson,
            UUID firstAppearanceEpisodeId
    ) {
        this.work = work;
        this.name = name;
        this.roleLabel = roleLabel;
        this.currentAge = currentAge;
        this.currentLevel = currentLevel;
        this.profileJson = profileJson;
        this.statsJson = statsJson;
        this.skillsJson = skillsJson;
        this.itemsJson = itemsJson;
        this.statusesJson = statusesJson;
        this.firstAppearanceEpisodeId = firstAppearanceEpisodeId;
        this.reviewStatus = CharacterReviewStatus.PENDING_REVIEW;
        this.status = CharacterStatus.ACTIVE;
    }

    public static WorkCharacter create(
            Work work,
            String name,
            String roleLabel,
            Integer currentAge,
            Integer currentLevel,
            JsonNode profileJson,
            JsonNode statsJson,
            JsonNode skillsJson,
            JsonNode itemsJson,
            JsonNode statusesJson,
            UUID firstAppearanceEpisodeId
    ) {
        return new WorkCharacter(
                work,
                name,
                roleLabel,
                currentAge,
                currentLevel,
                profileJson,
                statsJson,
                skillsJson,
                itemsJson,
                statusesJson,
                firstAppearanceEpisodeId
        );
    }

    public void confirm() {
        this.reviewStatus = CharacterReviewStatus.CONFIRMED;
    }

    public void dismiss() {
        this.reviewStatus = CharacterReviewStatus.DISMISSED;
    }

    public void archive() {
        this.status = CharacterStatus.ARCHIVED;
    }
}
