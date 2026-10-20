package org.monitoring.catchholebackend.domain.relationship.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

/** 사용자가 동일 인물이라고 명시적으로 묶은 미등록 언급. 실제 캐릭터와 관계·근거는 만들지 않는다. */
@Getter
@Entity
@Table(name = "relationship_mention_groups", uniqueConstraints = @UniqueConstraint(
        name = "uk_relationship_mention_groups_request", columnNames = {"work_id", "request_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RelationshipMentionGroup extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_relationship_mention_groups_work"))
    private Work work;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false)
    private long version;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "disbanded_from_version")
    private Long disbandedFromVersion;

    public static RelationshipMentionGroup create(Work work, UUID requestId, String requestHash, String name) {
        var group = new RelationshipMentionGroup();
        group.work = work;
        group.requestId = requestId;
        group.requestHash = requestHash;
        group.name = name;
        return group;
    }

    public void recordMembershipChange() {
        version++;
    }

    public void disband() {
        active = false;
        disbandedFromVersion = version++;
    }
}
