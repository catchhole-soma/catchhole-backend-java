package org.monitoring.catchholebackend.domain.relationship.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.relationship.entity.CharacterRelationship;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipCandidate;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipEvidence;
import org.monitoring.catchholebackend.domain.relationship.type.FamilyTreeRole;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipAssertion;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipValidity;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("가계도 끝점 호칭의 현재 근거·대상 일치·한쪽 명시·상충 경계")
class FamilyTreeRoleResolverTest {
    private static final String TOKEN = "a".repeat(64);
    private final WorkCharacterRepository characters = mock(WorkCharacterRepository.class);
    private final FamilyTreeRoleResolver resolver = new FamilyTreeRoleResolver(new ExplicitFamilyRelationshipParser(), characters);
    private final List<WorkCharacter> active = new ArrayList<>();
    private final Map<UUID, RelationshipSourceContext> sources = new HashMap<>();
    private final List<RelationshipEvidence> evidence = new ArrayList<>();
    private Work work;
    private WorkCharacter parent;
    private WorkCharacter child;
    private CharacterRelationship relation;

    @BeforeEach
    void setup() {
        work = Work.create(null, "호칭 테스트", WorkGenre.FANTASY, null);
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        parent = character("얀델");
        child = character("비요른");
        relation = CharacterRelationship.create(work, parent, child, RelationshipKind.PARENT_OF,
                RelationshipSubtype.UNSPECIFIED, RelationshipTemporalScope.CURRENT, null, "b".repeat(64));
        ReflectionTestUtils.setField(relation, "id", UUID.randomUUID());
        when(characters.findAllByWorkIdAndStatusOrderByCreatedAtDesc(work.getId(), CharacterStatus.ACTIVE)).thenReturn(active);
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', value = {
            "CHILD|얀델의 아들|PARENT|SON", "CHILD|아버지: 얀델|FATHER|CHILD",
            "CHILD|얀델의 딸|PARENT|DAUGHTER", "CHILD|어머니: 얀델|MOTHER|CHILD",
            "PARENT|아들: 비요른|PARENT|SON", "PARENT|비요른의 어머니|MOTHER|CHILD",
            "PARENT|자녀: 비요른|PARENT|CHILD", "CHILD|부모: 얀델|PARENT|CHILD"
    })
    @DisplayName("명시된 호칭의 끝점만 구체화하고 반대편 성별을 추론하지 않는다")
    void resolvesOnlyExplicitEndpoint(String owner, String text, FamilyTreeRole subjectRole, FamilyTreeRole objectRole) {
        support(owner.equals("CHILD") ? child : parent, text, false);
        assertRoles(registeredRoles(), subjectRole, objectRole);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {"넷째 아들|SON", "두 번째 딸|DAUGHTER", "세 번째 자녀|CHILD"})
    @DisplayName("서수가 있는 현재 가족 근거에서도 자녀 호칭을 가계도 끝점에 표시한다")
    void resolvesExplicitChildRoleWithBirthOrder(String role, FamilyTreeRole childRole) {
        support(child, parent.getName() + "의 " + role, false);

        assertRoles(registeredRoles(), FamilyTreeRole.PARENT, childRole);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "CHILD|두 번째 딸|PARENT|DAUGHTER",
            "PARENT|어머니|MOTHER|CHILD"
    })
    @DisplayName("등록 관계의 현재 소유자 이름으로 끝나는 명제에서도 명시된 양 끝점 호칭을 유지한다")
    void resolvesRegisteredRoleWithExactOwnerSuffix(String ownerSide, String role,
            FamilyTreeRole parentRole, FamilyTreeRole childRole) {
        var owner = ownerSide.equals("CHILD") ? child : parent;
        var other = ownerSide.equals("CHILD") ? parent : child;
        support(owner, other.getName() + "의 " + role + " " + owner.getName(), false);

        assertRoles(registeredRoles(), parentRole, childRole);
        verify(characters).findAllByWorkIdAndStatusOrderByCreatedAtDesc(work.getId(), CharacterStatus.ACTIVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"여솔", "담온"})
    @DisplayName("등록 관계에서 축약명이나 타인 이름으로 끝나는 근거의 호칭을 소유자에게 옮기지 않는다")
    void rejectsRegisteredRoleWithWrongOwnerSuffix(String suffix) {
        ReflectionTestUtils.setField(child, "name", "여솔 아라온");
        support(child, parent.getName() + "의 딸 " + suffix, false);

        assertRoles(registeredRoles(), FamilyTreeRole.PARENT, FamilyTreeRole.CHILD);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {"넷째 아들|SON", "두 번째 딸|DAUGHTER"})
    @DisplayName("부모 프로필이 없는 서수 관계도 명시된 아들·딸 호칭을 유지한다")
    void resolvesBirthOrderRoleWithoutRegisteredParent(String role, FamilyTreeRole childRole) {
        var candidate = candidate(child, null, "현우", RelationshipKind.CHILD_OF);
        candidate.markRuleGenerated();
        var support = evidence(candidate, child, "현우의 " + role);
        var mentions = new RelationshipMentionReader.Snapshot(List.of(candidate), 1,
                Map.of(candidate.getId(), List.of(support)), sources);
        var emptyGraph = new RelationshipGraphReader.Graph(List.of(), Map.of(), Map.of(), Map.of());

        assertRoles(resolver.resolveRoles(work.getId(), emptyGraph, mentions).get("mention:" + candidate.getId()),
                FamilyTreeRole.PARENT, childRole);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {"아들|SON", "두 번째 딸|DAUGHTER"})
    @DisplayName("미등록 부모 관계도 소유자의 정확한 이름으로 끝나면 자녀 호칭을 보존한다")
    void resolvesMentionRoleWithExactOwnerSuffix(String role, FamilyTreeRole childRole) {
        var candidate = candidate(child, null, "담온", RelationshipKind.CHILD_OF);
        candidate.markRuleGenerated();
        var support = evidence(candidate, child, "담온의 " + role + " " + child.getName());
        var mentions = new RelationshipMentionReader.Snapshot(List.of(candidate), 1,
                Map.of(candidate.getId(), List.of(support)), sources);
        var emptyGraph = new RelationshipGraphReader.Graph(List.of(), Map.of(), Map.of(), Map.of());

        assertRoles(resolver.resolveRoles(work.getId(), emptyGraph, mentions).get("mention:" + candidate.getId()),
                FamilyTreeRole.PARENT, childRole);
        verify(characters).findAllByWorkIdAndStatusOrderByCreatedAtDesc(work.getId(), CharacterStatus.ACTIVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"여솔", "담온"})
    @DisplayName("미등록 부모 관계에서도 축약명이나 타인 이름의 호칭을 연결된 소유자에게 붙이지 않는다")
    void rejectsMentionRoleWithWrongOwnerSuffix(String suffix) {
        ReflectionTestUtils.setField(child, "name", "여솔 아라온");
        var candidate = candidate(child, null, "라온", RelationshipKind.CHILD_OF);
        candidate.markRuleGenerated();
        var support = evidence(candidate, child, "라온의 딸 " + suffix);
        var mentions = new RelationshipMentionReader.Snapshot(List.of(candidate), 1,
                Map.of(candidate.getId(), List.of(support)), sources);
        var emptyGraph = new RelationshipGraphReader.Graph(List.of(), Map.of(), Map.of(), Map.of());

        assertRoles(resolver.resolveRoles(work.getId(), emptyGraph, mentions).get("mention:" + candidate.getId()),
                FamilyTreeRole.PARENT, FamilyTreeRole.CHILD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"넷째 아들이 아니다", "두 번째 딸로 추정된다", "넷째 아들인 줄 알았으나 아니었다"})
    @DisplayName("서수를 읽더라도 부정·추측·정정한 근거로 구체적인 끝점 호칭을 붙이지 않는다")
    void refusesBirthOrderRolesWithoutAssertedCurrentSupport(String suffix) {
        support(child, parent.getName() + "의 " + suffix, false);

        assertRoles(registeredRoles(), FamilyTreeRole.PARENT, FamilyTreeRole.CHILD);
    }

    @Test
    @DisplayName("현재 독립 근거에서 부모와 자녀 호칭이 각각 명시되면 양쪽을 함께 표시한다")
    void combinesIndependentEndpointEvidence() {
        support(child, "아버지: 얀델", false);
        support(parent, "아들: 비요른", false);
        assertRoles(registeredRoles(), FamilyTreeRole.FATHER, FamilyTreeRole.SON);
    }

    @Test
    @DisplayName("같은 끝점의 호칭이 충돌하면 그 끝점만 중립 호칭으로 되돌린다")
    void conflictingRolesFallBackPerEndpoint() {
        support(child, "아버지: 얀델; 어머니: 얀델", false);
        support(parent, "딸: 비요른", false);
        assertRoles(registeredRoles(), FamilyTreeRole.PARENT, FamilyTreeRole.DAUGHTER);
        support(parent, "아들: 비요른", false);
        assertRoles(registeredRoles(), FamilyTreeRole.PARENT, FamilyTreeRole.CHILD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "STALE", "BLOCKED", "OLD_INPUT"})
    @DisplayName("관계가 유효해도 소실·오래된·자동지원 차단 근거의 호칭은 사용하지 않는다")
    void ignoresUnsupportedEvidenceEvenForValidRelationship(String invalidation) {
        support(parent, "자녀: 비요른", false);
        var specific = support(child, "아버지: 얀델", invalidation.equals("BLOCKED") || invalidation.equals("OLD_INPUT"));
        var previous = sources.get(specific.getSourceFact().getId());
        switch (invalidation) {
            case "MISSING" -> sources.remove(previous.factId());
            case "STALE" -> sources.put(previous.factId(), new RelationshipSourceContext(previous.factId(), previous.characterId(),
                    previous.factKey(), previous.currentValue(), "c".repeat(64), false, false));
            case "BLOCKED" -> specific.getCandidate().holdAutomatically("CONFLICTING");
            case "OLD_INPUT" -> work.markRelationshipInputChanged();
            default -> throw new IllegalArgumentException(invalidation);
        }
        assertRoles(registeredRoles(), FamilyTreeRole.PARENT, FamilyTreeRole.CHILD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"딸: 비요른", "아버지: 다른 사람", "친아버지: 얀델", "과거 아버지: 얀델",
            "아버지: 얀델 (추정)", "얀델의 아들이 아니다", "얀델의 아들이며 용사다"})
    @DisplayName("수동 확정 관계와 대상·방향·세부유형·시점·단정이 다른 문장은 호칭을 제공하지 않는다")
    void refusesMismatchedCurrentSettings(String text) {
        support(child, text, false);
        assertRoles(registeredRoles(), FamilyTreeRole.PARENT, FamilyTreeRole.CHILD);
    }

    @Test
    @DisplayName("근거 소유자가 양 끝점 밖이거나 상대 이름이 동명이인이면 호칭을 옮기지 않는다")
    void refusesUnrelatedOwnerAndAmbiguousTarget() {
        support(character("다른 인물"), "아버지: 얀델", false);
        assertRoles(registeredRoles(), FamilyTreeRole.PARENT, FamilyTreeRole.CHILD);
        support(child, "아버지: 얀델", false);
        character("얀델");
        assertRoles(registeredRoles(), FamilyTreeRole.PARENT, FamilyTreeRole.CHILD);
    }

    @Test
    @DisplayName("호칭이 없는 근거에 프로필 성별을 결합하지 않는다")
    void doesNotInferRolesFromProfileGender() {
        ReflectionTestUtils.setField(parent, "profileJson", JsonNodeFactory.instance.objectNode().put("gender", "남성"));
        ReflectionTestUtils.setField(child, "profileJson", JsonNodeFactory.instance.objectNode().put("gender", "여성"));
        support(child, "부모: 얀델", false);
        assertRoles(registeredRoles(), FamilyTreeRole.PARENT, FamilyTreeRole.CHILD);
    }

    @Test
    @DisplayName("미등록 상대도 현재 명제에 명시된 자녀 호칭을 보존하며 수정한 방향과 불일치하면 중립으로 되돌린다")
    void resolvesMentionWithoutRegisteredParentAndRejectsEditedMismatch() {
        var candidate = candidate(child, null, "얀델", RelationshipKind.CHILD_OF);
        candidate.markRuleGenerated();
        var support = evidence(candidate, child, "얀델의 아들");
        var mentions = new RelationshipMentionReader.Snapshot(List.of(candidate), 1,
                Map.of(candidate.getId(), List.of(support)), sources);
        var emptyGraph = new RelationshipGraphReader.Graph(List.of(), Map.of(), Map.of(), Map.of());
        assertRoles(resolver.resolveRoles(work.getId(), emptyGraph, mentions).get("mention:" + candidate.getId()),
                FamilyTreeRole.PARENT, FamilyTreeRole.SON);
        candidate.revise(child, null, "얀델", RelationshipKind.PARENT_OF, RelationshipSubtype.UNSPECIFIED,
                RelationshipTemporalScope.CURRENT, null, RelationshipAssertion.ASSERTED);
        assertRoles(resolver.resolveRoles(work.getId(), emptyGraph, mentions).get("mention:" + candidate.getId()),
                FamilyTreeRole.PARENT, FamilyTreeRole.CHILD);
    }

    @Test
    @DisplayName("부모 관계가 아닌 간선에는 부모·자녀 호칭을 추가하지 않는다")
    void doesNotAttachParentRolesToOtherKinds() {
        var spouse = CharacterRelationship.create(work, parent, child, RelationshipKind.SPOUSE,
                RelationshipSubtype.UNSPECIFIED, RelationshipTemporalScope.CURRENT, null, "d".repeat(64));
        ReflectionTestUtils.setField(spouse, "id", UUID.randomUUID());
        var graph = new RelationshipGraphReader.Graph(List.of(spouse), Map.of(), Map.of(), Map.of(spouse.getId(), RelationshipValidity.VALID));
        assertThat(resolver.resolveRoles(work.getId(), graph, new RelationshipMentionReader.Snapshot(List.of(), 0))).isEmpty();
    }

    private FamilyTreeRoleResolver.EndpointRoles registeredRoles() {
        var graph = new RelationshipGraphReader.Graph(List.of(relation), Map.of(relation.getId(), evidence), sources,
                Map.of(relation.getId(), RelationshipValidity.VALID));
        return resolver.resolveRoles(work.getId(), graph, new RelationshipMentionReader.Snapshot(List.of(), 0)).get(relation.getId().toString());
    }

    private void assertRoles(FamilyTreeRoleResolver.EndpointRoles roles, FamilyTreeRole subjectRole, FamilyTreeRole objectRole) {
        assertThat(roles).isEqualTo(new FamilyTreeRoleResolver.EndpointRoles(subjectRole, objectRole));
    }

    private WorkCharacter character(String name) {
        var character = WorkCharacter.create(work, name, null, null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(character, "id", UUID.randomUUID());
        active.add(character);
        return character;
    }

    private RelationshipCandidate candidate(WorkCharacter subject, WorkCharacter object, String mention, RelationshipKind kind) {
        var candidate = RelationshipCandidate.create(work, UUID.randomUUID(), "b".repeat(64), subject, object, mention, kind,
                RelationshipSubtype.UNSPECIFIED, RelationshipTemporalScope.CURRENT, null, RelationshipAssertion.ASSERTED, null);
        ReflectionTestUtils.setField(candidate, "id", UUID.randomUUID());
        return candidate;
    }

    private RelationshipEvidence support(WorkCharacter owner, String text, boolean automatic) {
        var candidate = candidate(parent, child, null, RelationshipKind.PARENT_OF);
        if (automatic) {
            candidate.confirmAutomatically(relation);
            candidate.validateAutomaticSupport(work.getRelationshipInputVersion());
        } else candidate.confirm(relation);
        var support = evidence(candidate, owner, text);
        evidence.add(support);
        return support;
    }

    private RelationshipEvidence evidence(RelationshipCandidate candidate, WorkCharacter owner, String text) {
        var value = JsonNodeFactory.instance.objectNode().put("value", text);
        String key = "profile.family";
        var fact = CharacterFact.createManual(owner, CharacterFactType.PROFILE, key, text, value);
        ReflectionTestUtils.setField(fact, "id", UUID.randomUUID());
        var current = JsonNodeFactory.instance.objectNode().put("factValue", text).set("valueJson", value);
        sources.put(fact.getId(), new RelationshipSourceContext(fact.getId(), owner.getId(), key, current, TOKEN, false, true));
        return RelationshipEvidence.create(candidate, fact, TOKEN);
    }
}
