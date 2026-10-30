package org.monitoring.catchholebackend.domain.character.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.processor.GeneralEvidenceProjection;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.processor.CharacterFactSourceResolver;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSettingDisplayNameResolver;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;

@DisplayName("공개 캐릭터 원문 근거 투영")
class PublicCharacterEvidenceProjectionTest {
    private final ObjectMapper json = new ObjectMapper();
    private final GeneralEvidenceProjection projection = mock(GeneralEvidenceProjection.class);
    private final CharacterFactSourceResolver sources = mock(CharacterFactSourceResolver.class);

    @Test
    @DisplayName("상세 원문은 유지하고 검증에서 제외된 인용을 fallback하지 않는다")
    void evidenceResponseUsesVerifiedSpansOnly() {
        var candidate = mock(SettingCandidate.class);
        var fact = mock(CharacterFact.class);
        when(fact.getSettingCandidate()).thenReturn(candidate);
        var verified = json.createArrayNode().add(json.createObjectNode()
                .put("quote", "갈색 눈").put("startOffset", 2).put("endOffset", 6));
        when(projection.forCandidate(candidate, "😀 갈색 눈")).thenReturn(verified);
        var mapper = new CharacterFactEvidenceMapper(sources, projection);
        var normal = mapper.toResponse(fact, "😀 갈색 눈");
        assertThat(normal.evidenceSpans()).hasSize(1);
        assertThat(normal.evidenceSpans().getFirst().quote()).isEqualTo("갈색 눈");
        when(projection.forCandidate(candidate, "교체된 원문")).thenReturn(json.createArrayNode());
        var changed = mapper.toResponse(fact, "교체된 원문");
        assertThat(changed.content()).isEqualTo("교체된 원문");
        assertThat(changed.evidenceSpans()).isEmpty();
    }

    @Test
    @DisplayName("설정 검색 상세의 인용문 목록도 정제 결과만 사용하고 설정값을 유지한다")
    void factDetailQuotesUseProjection() {
        var candidate = mock(SettingCandidate.class);
        var fact = mock(CharacterFact.class);
        var character = mock(WorkCharacter.class);
        when(fact.getSettingCandidate()).thenReturn(candidate);
        when(fact.getWorkCharacter()).thenReturn(character);
        when(fact.getFactType()).thenReturn(CharacterFactType.PROFILE);
        when(fact.getFactValue()).thenReturn("갈색");
        when(projection.forCandidate(candidate)).thenReturn(json.createArrayNode());
        var mapper = new CharacterFactMapper(mock(CharacterSettingDisplayNameResolver.class), sources, projection);
        var response = mapper.toDetailResponse(fact, List.of(), true);
        assertThat(response.factValue()).isEqualTo("갈색");
        assertThat(response.evidenceQuotes()).isEmpty();
    }
}
