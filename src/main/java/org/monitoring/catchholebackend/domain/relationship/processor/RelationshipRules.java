package org.monitoring.catchholebackend.domain.relationship.processor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipDraft;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipMentionGroupCreateRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipEvidenceRequest;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipCandidate;
import org.monitoring.catchholebackend.domain.relationship.exception.RelationshipErrorCode;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;

@Component
public class RelationshipRules {

    public void validateDraft(RelationshipDraft draft) {
        if (draft.subjectCharacterId().equals(draft.objectCharacterId())) {
            throw new AppException(RelationshipErrorCode.RELATIONSHIP_INVALID_RELATIONSHIP);
        }
        if (draft.objectCharacterId() == null && clean(draft.objectMention()) == null) {
            throw new AppException(RelationshipErrorCode.RELATIONSHIP_INVALID_RELATIONSHIP);
        }
        Set<RelationshipSubtype> allowed = switch (draft.kind()) {
            case PARENT_OF, CHILD_OF -> Set.of(RelationshipSubtype.UNSPECIFIED,
                    RelationshipSubtype.BIOLOGICAL, RelationshipSubtype.ADOPTIVE, RelationshipSubtype.STEP);
            case SIBLING -> Set.of(RelationshipSubtype.UNSPECIFIED, RelationshipSubtype.FULL,
                    RelationshipSubtype.HALF, RelationshipSubtype.BIOLOGICAL, RelationshipSubtype.ADOPTIVE, RelationshipSubtype.STEP);
            case ANCESTOR_OF, DESCENDANT_OF -> Set.of(RelationshipSubtype.UNSPECIFIED, RelationshipSubtype.BIOLOGICAL);
            case SPOUSE -> Set.of(RelationshipSubtype.UNSPECIFIED);
        };
        if (!allowed.contains(draft.subtype())) {
            throw new AppException(RelationshipErrorCode.RELATIONSHIP_INVALID_RELATIONSHIP);
        }
        if (draft.generationGap() != null && (!draft.kind().isAncestry() || draft.generationGap() < 1)) {
            throw new AppException(RelationshipErrorCode.RELATIONSHIP_INVALID_RELATIONSHIP);
        }
        Set<UUID> factIds = new HashSet<>();
        for (RelationshipEvidenceRequest evidence : draft.evidence()) {
            if (!factIds.add(evidence.sourceFactId())) {
                throw new AppException(RelationshipErrorCode.RELATIONSHIP_INVALID_SOURCE);
            }
        }
    }

    public CanonicalRelationship canonicalize(RelationshipCandidate candidate) {
        UUID subject = candidate.getSubjectCharacter().getId();
        UUID object = candidate.getObjectCharacter().getId();
        RelationshipKind kind = candidate.getKind();
        if (kind.isInverseLineage()) {
            UUID swap = subject;
            subject = object;
            object = swap;
            kind = kind.canonicalKind();
        } else if ((kind == RelationshipKind.SPOUSE || kind == RelationshipKind.SIBLING)
                && subject.toString().compareTo(object.toString()) > 0) {
            UUID swap = subject;
            subject = object;
            object = swap;
        }
        String key = kind.isAncestry()
                ? hashParts(subject, object, kind, candidate.getSubtype(), candidate.getTemporalScope(),
                        candidate.getTimeLabel(), candidate.getGenerationGap())
                : hashParts(subject, object, kind, candidate.getSubtype(), candidate.getTemporalScope(), candidate.getTimeLabel());
        return new CanonicalRelationship(subject, object, kind, key);
    }

    public String requestHash(RelationshipDraft draft, UUID replacedId, Long expectedVersion) {
        List<Object> parts = new ArrayList<>();
        parts.add(replacedId);
        parts.add(expectedVersion);
        parts.add(draft.subjectCharacterId());
        parts.add(draft.objectCharacterId());
        parts.add(clean(draft.objectMention()));
        parts.add(draft.kind());
        parts.add(draft.subtype());
        parts.add(draft.temporalScope());
        parts.add(clean(draft.timeLabel()));
        parts.add(draft.assertion());
        draft.evidence().stream().sorted(java.util.Comparator.comparing(e -> e.sourceFactId().toString()))
                .forEach(e -> {
                    parts.add(e.sourceFactId());
                    parts.add(e.sourceToken());
                });
        if (draft.kind().isAncestry() || draft.generationGap() != null) parts.add(draft.generationGap());
        return hashParts(parts.toArray());
    }

    public String mentionGroupRequestHash(RelationshipMentionGroupCreateRequest request) {
        List<Object> parts = new ArrayList<>();
        parts.add("mention-group-v1");
        parts.add(clean(request.name()));
        request.candidates().stream().sorted(java.util.Comparator.comparing(item -> item.candidateId().toString()))
                .forEach(item -> {
                    parts.add(item.candidateId());
                    parts.add(item.expectedVersion());
                });
        return hashParts(parts.toArray());
    }

    public static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String hashParts(Object... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object part : parts) {
                String text = part == null ? "-1:" : part.toString().length() + ":" + part;
                digest.update(text.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("관계 식별값을 생성할 수 없습니다.", exception);
        }
    }

    public record CanonicalRelationship(UUID subjectId, UUID objectId, RelationshipKind kind, String key) {}
}
