package org.monitoring.catchholebackend.domain.character.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("캐릭터 Fact 비교 Worker Mapper 테스트")
class CharacterFactComparisonWorkerMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CharacterFactComparisonWorkerMapper mapper = new CharacterFactComparisonWorkerMapper();

    @Test
    @DisplayName("복수 근거의 camelCase와 snake_case offset을 저장 순서대로 변환한다")
    void toEvidenceSpansMapsBothOffsetNamingConventionsInOrder() {
        var evidenceSpans = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                        .put("quote", "회복 효과로 신체가 빠르게 재생된다.")
                        .put("startOffset", 10)
                        .put("endOffset", 29))
                .add(objectMapper.createObjectNode()
                        .put("quote", "통증이 줄어들었다.")
                        .put("start_offset", 40)
                        .put("end_offset", 51));

        var result = mapper.toEvidenceSpans(evidenceSpans);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).quote()).isEqualTo("회복 효과로 신체가 빠르게 재생된다.");
        assertThat(result.get(0).startOffset()).isEqualTo(10);
        assertThat(result.get(0).endOffset()).isEqualTo(29);
        assertThat(result.get(1).quote()).isEqualTo("통증이 줄어들었다.");
        assertThat(result.get(1).startOffset()).isEqualTo(40);
        assertThat(result.get(1).endOffset()).isEqualTo(51);
    }
}
