package org.monitoring.catchholebackend.domain.relationship.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

import java.util.UUID;

@Getter
@Entity
@Table(name = "relationship_graph_nodes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RelationshipGraphNode extends BaseEntity {
    @Id private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID workId;

    private UUID characterId;
    private Integer characterLinkedEpisodeNo;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int firstEpisodeNo;

    public static RelationshipGraphNode create(
            UUID id, UUID workId, UUID characterId, String name, int episodeNo) {
        var value = new RelationshipGraphNode();
        value.id = id;
        value.workId = workId;
        value.characterId = characterId;
        value.characterLinkedEpisodeNo = characterId == null ? null : episodeNo;
        value.name = name;
        value.firstEpisodeNo = episodeNo;
        return value;
    }

    public void observeAt(int episodeNo) {
        firstEpisodeNo = Math.min(firstEpisodeNo, episodeNo);
    }

    public void link(UUID characterId, int episodeNo) {
        if (this.characterId == null) {
            this.characterId = characterId;
            this.characterLinkedEpisodeNo = episodeNo;
        }
    }
}
