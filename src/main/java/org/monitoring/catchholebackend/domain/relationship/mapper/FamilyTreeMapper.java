package org.monitoring.catchholebackend.domain.relationship.mapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.relationship.dto.response.FamilyTreeEdgeResponse;
import org.monitoring.catchholebackend.domain.relationship.dto.response.FamilyTreeNodeResponse;
import org.monitoring.catchholebackend.domain.relationship.dto.response.FamilyTreeResponse;
import org.monitoring.catchholebackend.domain.relationship.processor.FamilyTreeProjector;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.WorldSettingImageResponse;
import org.springframework.stereotype.Component;

@Component
public class FamilyTreeMapper {
    public FamilyTreeResponse toResponse(FamilyTreeProjector.Projection projection, Map<UUID, WorldSettingImageResponse> images) {
        return new FamilyTreeResponse(projection.nodes().stream()
                .map(node -> new FamilyTreeNodeResponse(node.id(), node.name(), node.nodeType(), node.characterId(), node.candidateId(),
                        node.characterId() == null ? null : images.get(node.characterId()),
                        node.mentionGroupId(), node.mentionGroupVersion(), node.candidateIds())).toList(),
                projection.edges().stream().map(edge -> new FamilyTreeEdgeResponse(edge.id(), edge.version(),
                        edge.subjectCharacterId(), edge.objectCharacterId(), edge.kind(),
                        edge.subtype(), edge.temporalScope(), edge.timeLabel(), edge.sourceType(),
                        edge.relationshipId(), edge.candidateId(), edge.subjectNodeId(), edge.objectNodeId(),
                        edge.subjectRole(), edge.objectRole(), edge.generationGap())).toList(),
                projection.totalNodeCount(), projection.totalEdgeCount(), projection.pendingCandidateCount(),
                projection.truncated(), projection.limit(), projection.includeHistorical(), projection.focusCharacterId(), revision(projection, images), projection.optionalLinkCount());
    }

    /** Images affect the response revision, but never family identity, scope, order or geometry. */
    private String revision(FamilyTreeProjector.Projection projection, Map<UUID, WorldSettingImageResponse> images) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            add(digest, "family-tree-images-v1", projection.revision());
            for (var node : projection.nodes()) {
                if (node.characterId() == null) continue;
                var image = images.get(node.characterId());
                add(digest, node.id());
                if (image == null) add(digest, (Object) null);
                else add(digest, image.source(), image.version(), image.catalogId(), image.name(),
                        image.thumbnailUrl(), image.imageUrl(), image.privateImageId(), image.vaultId());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private void add(MessageDigest digest, Object... values) {
        for (Object value : values) {
            byte[] bytes = value == null ? null : value.toString().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
            if (bytes != null) digest.update(bytes);
        }
    }
}
