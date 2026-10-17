package org.monitoring.catchholebackend.domain.relationship.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.processor.CharacterFactSourceResolver;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipEvidenceResponse;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipCandidate;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipEvidence;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipSourceContext;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipAssertion;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("관계 근거 응답의 현재성 및 원문 조회 가능 여부")
class RelationshipMapperTest {

    private static final String SOURCE_TOKEN = "a".repeat(64);
    private final RelationshipMapper mapper = new RelationshipMapper(new CharacterFactSourceResolver());
    private Work work;
    private WorkCharacter character;
    private SettingCandidate settingCandidate;
    private CharacterFact fact;
    private RelationshipEvidence evidence;

    @BeforeEach
    void setUp() {
        work = Work.create(null, "관계 근거 테스트", WorkGenre.FANTASY, null);
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        character = WorkCharacter.create(work, "아리아", null, null, null,
                null, null, null, null, null, null);
        ReflectionTestUtils.setField(character, "id", UUID.randomUUID());
        var spans = JsonNodeFactory.instance.arrayNode();
        spans.addObject().put("quote", "아리아는 벨의 어머니다.");
        settingCandidate = SettingCandidate.create(work, null, null, null, SettingEntityType.CHARACTER,
                "아리아", "가족", "벨의 어머니", SettingValueType.STRING, null, spans, null, null);
        fact = CharacterFact.create(character, settingCandidate, CharacterFactType.PROFILE, "profile.family",
                "벨의 어머니", "벨의 어머니", null, null, null, null, null, null);
        ReflectionTestUtils.setField(fact, "id", UUID.randomUUID());
        var candidate = RelationshipCandidate.create(work, UUID.randomUUID(), "b".repeat(64), character,
                null, "벨", RelationshipKind.PARENT_OF, RelationshipSubtype.UNSPECIFIED,
                RelationshipTemporalScope.CURRENT, null, RelationshipAssertion.ASSERTED, null);
        ReflectionTestUtils.setField(candidate, "id", UUID.randomUUID());
        evidence = RelationshipEvidence.create(candidate, fact, SOURCE_TOKEN);
    }

    @Test
    @DisplayName("현재 설정 토큰이 달라도 남아 있는 원문 근거 조회 경로를 제공한다")
    void retainsEvidencePathForChangedCurrentSetting() {
        RelationshipEvidenceResponse response = mapper.toResponse(evidence,
                Map.of(fact.getId(), context("c".repeat(64), true)));

        assertThat(response.current()).isFalse();
        assertThat(response.hasEvidence()).isTrue();
        assertThat(response.evidencePath()).isEqualTo(evidencePath());
    }

    @Test
    @DisplayName("현재 출처에서 빠진 Fact도 원문이 보존되면 근거를 조회할 수 있다")
    void retainsEvidencePathAfterSourceLeavesCurrentSetting() {
        RelationshipEvidenceResponse response = mapper.toResponse(evidence, Map.of());

        assertThat(response.current()).isFalse();
        assertThat(response.hasEvidence()).isTrue();
        assertThat(response.evidencePath()).isEqualTo(evidencePath());
    }

    @Test
    @DisplayName("원문 파기 후 현재 설정 연결은 유지해도 원문 조회 경로는 제거한다")
    void removesEvidencePathWhenSourceEvidenceIsPurged() {
        settingCandidate.purgeSourceEvidence();

        RelationshipEvidenceResponse response = mapper.toResponse(evidence,
                Map.of(fact.getId(), context(SOURCE_TOKEN, false)));

        assertThat(response.current()).isTrue();
        assertThat(response.hasEvidence()).isFalse();
        assertThat(response.evidencePath()).isNull();
    }

    @Test
    @DisplayName("보관 캐릭터는 기존 Fact API에서 조회할 수 없으므로 근거 경로를 제공하지 않는다")
    void hidesUnavailableEvidencePathForArchivedCharacter() {
        character.archive();

        RelationshipEvidenceResponse response = mapper.toResponse(evidence, Map.of());

        assertThat(response.current()).isFalse();
        assertThat(response.hasEvidence()).isFalse();
        assertThat(response.evidencePath()).isNull();
    }

    private RelationshipSourceContext context(String token, boolean hasEvidence) {
        return new RelationshipSourceContext(fact.getId(), character.getId(), fact.getFactKey(),
                JsonNodeFactory.instance.objectNode().put("factValue", fact.getFactValue()), token,
                hasEvidence, false);
    }

    private String evidencePath() {
        return "/api/v1/works/" + work.getId() + "/character-facts/" + fact.getId() + "/evidence";
    }
}
