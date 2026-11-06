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

    // The name observed when this node was recorded. It is a matching key and analysis-time
    // history, so no path (link, merge, profile rename) rewrites it.
    @Column(nullable = false, length = 100)
    private String name;

    // The first episode in which this node's own name is known.
    @Column(nullable = false)
    private int firstEpisodeNo;

    // GH382: an unlinked alias node redirects to its profile's node. A plain id, not an
    // association: reads resolve it in memory and never lazily traverse the chain.
    private UUID mergedIntoNodeId;
    private Integer mergedAtEpisodeNo;

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
        if (this.characterId == null && mergedIntoNodeId == null) {
            this.characterId = characterId;
            this.characterLinkedEpisodeNo = episodeNo;
        }
    }

    public boolean isMerged() {
        return mergedIntoNodeId != null;
    }

    /**
     * Redirects this unlinked node to the profile node. Edges, events and the stored name stay
     * unchanged. Returns false when this node is linked or already redirected elsewhere.
     */
    public boolean mergeInto(UUID canonicalId, int episodeNo) {
        if (id.equals(canonicalId)) throw new IllegalStateException("A node cannot merge into itself.");
        if (characterId != null) return false;
        if (mergedIntoNodeId != null) return mergedIntoNodeId.equals(canonicalId);
        mergedIntoNodeId = canonicalId;
        mergedAtEpisodeNo = episodeNo;
        return true;
    }

    /**
     * GH391 one-time cleanup: redirects a node linked to the same profile as the canonical node.
     * The profile link moves to the canonical node only (V118 requires a redirected row to be
     * unlinked); the stored name, first episode, edges' history and the row itself stay.
     * Returns false when this node is already redirected or is not linked.
     */
    public boolean consolidateInto(UUID canonicalId, int episodeNo) {
        if (id.equals(canonicalId)) throw new IllegalStateException("A node cannot merge into itself.");
        if (characterId == null || mergedIntoNodeId != null) return false;
        characterId = null;
        characterLinkedEpisodeNo = null;
        mergedIntoNodeId = canonicalId;
        mergedAtEpisodeNo = episodeNo;
        return true;
    }
}
