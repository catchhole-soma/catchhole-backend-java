package org.monitoring.catchholebackend.domain.character.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("설정 후보 확정 반영 Mapper 테스트")
class SettingCandidatePromotionMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SettingCandidatePromotionMapper mapper = new SettingCandidatePromotionMapper();

    @Test
    @DisplayName("후보를 WorkCharacter 생성 Entity로 변환한다")
    void toWorkCharacterMapsCandidateToCharacter() {
        UUID episodeId = UUID.randomUUID();
        SettingCandidate candidate = candidate(episode(episodeId, 3), "  level  ", "  10  ");

        WorkCharacter character = mapper.toWorkCharacter(candidate);

        assertThat(character.getWork()).isSameAs(candidate.getWork());
        assertThat(character.getName()).isEqualTo("아리아");
        assertThat(character.getFirstAppearanceEpisodeId()).isEqualTo(episodeId);
    }

    @Test
    @DisplayName("후보를 CharacterFact 생성 Entity로 변환한다")
    void toCharacterFactMapsCandidateToFact() {
        SettingCandidate candidate = candidate(
                episode(UUID.randomUUID(), 3),
                "  skills.은월참  ",
                "  은월참  ",
                SettingValueType.JSON,
                objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("name", "은월참"))
        );
        WorkCharacter character = mapper.toWorkCharacter(candidate);
        String factKey = mapper.toFactKey(candidate);
        CharacterFactType factType = mapper.toFactType(factKey);

        CharacterFact fact = mapper.toCharacterFact(candidate, character, factType, factKey);

        assertThat(fact.getWorkCharacter()).isSameAs(character);
        assertThat(fact.getFactType()).isEqualTo(CharacterFactType.SKILL);
        assertThat(fact.getFactKey()).isEqualTo("skills.은월참");
        assertThat(fact.getFactValue()).isEqualTo("은월참");
        assertThat(fact.getNormalizedValue()).isEqualTo("은월참");
        assertThat(fact.getValueJson()).isEqualTo(candidate.getValueJson());
        assertThat(fact.getSourceEpisode()).isSameAs(candidate.getEpisode());
        assertThat(fact.getSourceChunkId()).isEqualTo(candidate.getSourceChunkId());
        assertThat(fact.getConfidence()).isEqualByComparingTo("0.8000");
        assertThat(fact.getEffectiveFromEpisodeNo()).isEqualTo(3);
    }

    @Test
    @DisplayName("지원하지 않는 attributeName은 거절한다")
    void toCharacterFactRejectsUnsupportedAttributeName() {
        SettingCandidate candidate = candidate(episode(UUID.randomUUID(), 3), "profile", "북부 기사단");
        String factKey = mapper.toFactKey(candidate);

        assertThatThrownBy(() -> mapper.toFactType(factKey))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_FACT_TYPE_UNSUPPORTED));
    }

    private SettingCandidate candidate(Episode episode, String attributeName, String attributeValue) {
        return candidate(
                episode,
                attributeName,
                attributeValue,
                SettingValueType.NUMBER,
                objectMapper.createObjectNode().put("value", attributeValue)
        );
    }

    private SettingCandidate candidate(
            Episode episode,
            String attributeName,
            String attributeValue,
            SettingValueType valueType,
            JsonNode valueJson
    ) {
        return SettingCandidate.create(
                work(),
                episode,
                UUID.randomUUID(),
                null,
                SettingEntityType.CHARACTER,
                "  아리아  ",
                attributeName,
                attributeValue,
                valueType,
                valueJson,
                objectMapper.createArrayNode(),
                new BigDecimal("0.8000"),
                objectMapper.createObjectNode().put("raw_value", attributeValue)
        );
    }

    private Episode episode(UUID id, int episodeNo) {
        Episode episode = Episode.create(
                work(),
                null,
                episodeNo,
                episodeNo + "화",
                "content-key",
                "version",
                "hash",
                100
        );
        ReflectionTestUtils.setField(episode, "id", id);
        return episode;
    }

    private Work work() {
        Member member = Member.register("writer@example.com", "encoded-password", "01012345678", "작가");
        return Work.create(member, "내 작품", "판타지", "내 설명");
    }
}
