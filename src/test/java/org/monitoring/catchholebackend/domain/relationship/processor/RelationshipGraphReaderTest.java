package org.monitoring.catchholebackend.domain.relationship.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.relationship.entity.CharacterRelationship;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipCandidate;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipEvidence;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipCandidateRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipEvidenceRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipRepository;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipAssertion;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipValidity;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("가족 관계 그래프의 현재 근거와 복구 순환 검증")
class RelationshipGraphReaderTest {

    private static final String TOKEN = "a".repeat(64);
    private final RelationshipRepository relationshipRepository = mock(RelationshipRepository.class);
    private final RelationshipCandidateRepository candidateRepository = mock(RelationshipCandidateRepository.class);
    private final RelationshipEvidenceRepository evidenceRepository = mock(RelationshipEvidenceRepository.class);
    private final RelationshipSourceResolver sourceResolver = mock(RelationshipSourceResolver.class);
    private final RelationshipGraphReader reader = new RelationshipGraphReader(
            relationshipRepository, candidateRepository, evidenceRepository, sourceResolver);
    private final List<CharacterRelationship> relationships = new ArrayList<>();
    private final List<RelationshipCandidate> candidates = new ArrayList<>();
    private final List<RelationshipEvidence> evidence = new ArrayList<>();
    private final Map<UUID, RelationshipSourceContext> sources = new LinkedHashMap<>();
    private Work work;

    @BeforeEach
    void setUp() {
        work = Work.create(null, "관계 그래프 테스트", WorkGenre.FANTASY, null);
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        when(relationshipRepository.findAllByWorkId(work.getId())).thenReturn(relationships);
        when(candidateRepository.findAllByConfirmedRelationshipIdIn(any())).thenAnswer(invocation -> {
            Collection<UUID> ids = invocation.getArgument(0);
            return candidates.stream().filter(candidate -> ids.contains(candidate.getConfirmedRelationship().getId())).toList();
        });
        when(evidenceRepository.findAllByCandidateIdIn(any())).thenAnswer(invocation -> {
            Collection<UUID> ids = invocation.getArgument(0);
            return evidence.stream().filter(item -> ids.contains(item.getCandidate().getId())).toList();
        });
        when(sourceResolver.resolve(eq(work.getId()), any())).thenAnswer(invocation -> Map.copyOf(sources));
    }

    @Test
    @DisplayName("같은 후보의 독립 근거 하나가 사라져도 남은 근거를 유지하고 모두 없으면 재검토한다")
    void keepsIndependentRemainingEvidenceWithoutDiscardingHistory() {
        WorkCharacter parent = character("부모");
        WorkCharacter child = character("자녀");
        CharacterRelationship relation = relationship(parent, child, RelationshipKind.PARENT_OF);
        RelationshipCandidate candidate = candidates.getFirst();
        RelationshipEvidence second = support(candidate, child);
        UUID firstFactId = evidence.getFirst().getSourceFact().getId();

        sources.remove(firstFactId);
        RelationshipGraphReader.Graph partial = reader.readGraph(work.getId());

        assertThat(partial.validity().get(relation.getId())).isEqualTo(RelationshipValidity.VALID);
        assertThat(partial.evidence().get(relation.getId())).hasSize(2);

        sources.remove(second.getSourceFact().getId());
        RelationshipGraphReader.Graph unsupported = reader.readGraph(work.getId());
        assertThat(unsupported.validity().get(relation.getId())).isEqualTo(RelationshipValidity.REVIEW_REQUIRED);
        assertThat(unsupported.evidence().get(relation.getId())).hasSize(2);
        assertThat(relation.isWithdrawn()).isFalse();
    }

    @Test
    @DisplayName("출처 Fact가 남아 있어도 현재 slot 토큰이 바뀌면 관계를 재검토한다")
    void changedSlotTokenIsNotCurrentSupport() {
        CharacterRelationship relation = relationship(character("부모"), character("자녀"), RelationshipKind.PARENT_OF);
        RelationshipEvidence item = evidence.getFirst();
        RelationshipSourceContext previous = sources.get(item.getSourceFact().getId());
        sources.put(previous.factId(), new RelationshipSourceContext(previous.factId(), previous.characterId(),
                previous.factKey(), null, "b".repeat(64), false, false));

        RelationshipGraphReader.Graph graph = reader.readGraph(work.getId());

        assertThat(graph.validity().get(relation.getId())).isEqualTo(RelationshipValidity.REVIEW_REQUIRED);
        assertThat(RelationshipGraphReader.isCurrent(item, graph.sources())).isFalse();
    }

    @Test
    @DisplayName("보관은 근거를 지우지 않고 복구할 때 남은 현재 근거를 재검사한다")
    void archiveAndRestoreRecheckCurrentSupport() {
        WorkCharacter parent = character("부모");
        WorkCharacter child = character("자녀");
        CharacterRelationship relation = relationship(parent, child, RelationshipKind.PARENT_OF);

        child.archive();
        assertThat(reader.readGraph(work.getId()).validity().get(relation.getId()))
                .isEqualTo(RelationshipValidity.ARCHIVED_ENDPOINT);
        child.restore();
        assertThat(reader.readGraph(work.getId()).validity().get(relation.getId())).isEqualTo(RelationshipValidity.VALID);

        child.archive();
        sources.clear();
        child.restore();
        RelationshipGraphReader.Graph restoredWithoutSupport = reader.readGraph(work.getId());
        assertThat(restoredWithoutSupport.validity().get(relation.getId())).isEqualTo(RelationshipValidity.REVIEW_REQUIRED);
        assertThat(restoredWithoutSupport.evidence().get(relation.getId())).hasSize(1);
    }

    @Test
    @DisplayName("철회된 관계는 근거와 인물이 복구되어도 다시 활성화하지 않는다")
    void withdrawalRemainsAuthoritativeAcrossArchiveRestore() {
        WorkCharacter parent = character("부모");
        WorkCharacter child = character("자녀");
        CharacterRelationship relation = relationship(parent, child, RelationshipKind.PARENT_OF);
        relation.withdraw();
        child.archive();
        assertThat(reader.readGraph(work.getId()).validity().get(relation.getId())).isEqualTo(RelationshipValidity.WITHDRAWN);

        child.restore();
        RelationshipGraphReader.Graph graph = reader.readGraph(work.getId());
        assertThat(graph.validity().get(relation.getId())).isEqualTo(RelationshipValidity.WITHDRAWN);
        assertThat(reader.wouldCreateCycle(graph, child.getId(), parent.getId())).isFalse();
    }

    @Test
    @DisplayName("보관 복구로 생긴 순환만 재검토하고 연결된 비순환 관계는 유지한다")
    void restoredCycleIsHeldWithoutHidingAdjacentAcyclicEdges() {
        WorkCharacter a = character("A");
        WorkCharacter b = character("B");
        WorkCharacter c = character("C");
        WorkCharacter d = character("D");
        CharacterRelationship ab = relationship(a, b, RelationshipKind.PARENT_OF);
        CharacterRelationship bc = relationship(b, c, RelationshipKind.PARENT_OF);
        CharacterRelationship db = relationship(d, b, RelationshipKind.PARENT_OF);
        b.archive();
        CharacterRelationship ca = relationship(c, a, RelationshipKind.PARENT_OF);
        assertThat(reader.readGraph(work.getId()).validity().get(ca.getId())).isEqualTo(RelationshipValidity.VALID);

        b.restore();
        RelationshipGraphReader.Graph graph = reader.readGraph(work.getId());

        assertThat(graph.validity()).containsEntry(ab.getId(), RelationshipValidity.REVIEW_REQUIRED)
                .containsEntry(bc.getId(), RelationshipValidity.REVIEW_REQUIRED)
                .containsEntry(ca.getId(), RelationshipValidity.REVIEW_REQUIRED)
                .containsEntry(db.getId(), RelationshipValidity.VALID);
        // D → B → C → A의 근거는 유효하다. 화면에서 숨긴 순환을 제외하고 A → D를 확정하면 안 된다.
        assertThat(reader.wouldCreateCycle(graph, a.getId(), d.getId())).isTrue();
    }

    @Test
    @DisplayName("배우자·형제와 근거 없는 부모 관계는 조상 경로를 만들지 않는다")
    void ignoresNonParentAndUnsupportedEdgesDuringCycleCheck() {
        WorkCharacter a = character("A");
        WorkCharacter b = character("B");
        WorkCharacter c = character("C");
        relationship(a, b, RelationshipKind.SPOUSE);
        relationship(b, c, RelationshipKind.SIBLING);
        relationship(c, a, RelationshipKind.PARENT_OF);
        sources.remove(evidence.getLast().getSourceFact().getId());

        RelationshipGraphReader.Graph graph = reader.readGraph(work.getId());

        assertThat(reader.wouldCreateCycle(graph, c.getId(), a.getId())).isFalse();
        assertThat(reader.wouldCreateCycle(graph, a.getId(), c.getId())).isFalse();
    }

    private WorkCharacter character(String name) {
        WorkCharacter character = WorkCharacter.create(work, name, null, null, null,
                null, null, null, null, null, null);
        ReflectionTestUtils.setField(character, "id", UUID.randomUUID());
        return character;
    }

    private CharacterRelationship relationship(WorkCharacter subject, WorkCharacter object, RelationshipKind kind) {
        CharacterRelationship relation = CharacterRelationship.create(work, subject, object, kind,
                RelationshipSubtype.UNSPECIFIED, RelationshipTemporalScope.CURRENT, null, TOKEN);
        ReflectionTestUtils.setField(relation, "id", UUID.randomUUID());
        relationships.add(relation);
        RelationshipCandidate candidate = RelationshipCandidate.create(work, UUID.randomUUID(), TOKEN,
                subject, object, null, kind, RelationshipSubtype.UNSPECIFIED,
                RelationshipTemporalScope.CURRENT, null, RelationshipAssertion.ASSERTED, null);
        ReflectionTestUtils.setField(candidate, "id", UUID.randomUUID());
        candidate.confirm(relation);
        candidates.add(candidate);
        support(candidate, subject);
        return relation;
    }

    private RelationshipEvidence support(RelationshipCandidate candidate, WorkCharacter character) {
        CharacterFact fact = CharacterFact.createManual(character, CharacterFactType.PROFILE,
                "profile.family", "명시된 가족 설정", null);
        ReflectionTestUtils.setField(fact, "id", UUID.randomUUID());
        RelationshipEvidence item = RelationshipEvidence.create(candidate, fact, TOKEN);
        evidence.add(item);
        sources.put(fact.getId(), new RelationshipSourceContext(fact.getId(), character.getId(),
                fact.getFactKey(), null, TOKEN, false, false));
        return item;
    }
}
