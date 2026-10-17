package org.monitoring.catchholebackend.domain.relationship.dto.request;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipAssertion;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;

/** 생성과 정정이 공유하는, 사용자가 검토할 관계 명제와 현재 설정 근거. */
public interface RelationshipDraft {
    UUID subjectCharacterId();
    UUID objectCharacterId();
    String objectMention();
    RelationshipKind kind();
    RelationshipSubtype subtype();
    RelationshipTemporalScope temporalScope();
    String timeLabel();
    RelationshipAssertion assertion();
    List<RelationshipEvidenceRequest> evidence();
}
