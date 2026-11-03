package org.monitoring.catchholebackend.domain.character.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.processor.GeneralEvidenceProjection;
import org.monitoring.catchholebackend.domain.analysis.repository.GeneralEvidenceSourceRepository;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.repository.StoredCharacterEvidenceRepository;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;

@DisplayName("캐릭터 비교 후보의 근거는 저장된 검증 결과를 사용한다")
class CharacterComparisonEvidenceProjectionTest {
    @Test @DisplayName("S3 원문을 읽지 않고 검증된 인용을 사용하며 파기된 근거는 비운다")
    void usesStoredEvidenceIndependentlyOfS3() throws Exception {
        var storage = mock(ObjectStorageService.class);
        var sources = mock(GeneralEvidenceSourceRepository.class);
        var stored = mock(StoredCharacterEvidenceRepository.class);
        var candidate = mock(SettingCandidate.class);
        var spans = new ObjectMapper().readTree("[{\"quote\":\"정상\",\"startOffset\":0,\"endOffset\":2}]");
        when(candidate.getEvidenceSpans()).thenReturn(spans);
        when(stored.find(candidate)).thenReturn(new StoredCharacterEvidenceRepository.Evidence(false,true,spans,true,spans));
        var mapper = new CharacterFactComparisonWorkerMapper(new GeneralEvidenceProjection(storage,sources,stored));
        assertThat(mapper.toCandidateEvidenceSpans(candidate)).singleElement().satisfies(span -> assertThat(span.quote()).isEqualTo("정상"));
        when(stored.find(candidate)).thenReturn(StoredCharacterEvidenceRepository.Evidence.purged());
        assertThat(mapper.toCandidateEvidenceSpans(candidate)).isEmpty();
        verifyNoInteractions(storage,sources);
    }

    @Test @DisplayName("위치 없는 근거는 저장소나 검증 기록을 조회하지 않는다")
    void missingOffsetsNeedNoLookup() throws Exception {
        var storage = mock(ObjectStorageService.class);
        var sources = mock(GeneralEvidenceSourceRepository.class);
        var stored = mock(StoredCharacterEvidenceRepository.class);
        var candidate = mock(SettingCandidate.class);
        when(candidate.getEvidenceSpans()).thenReturn(new ObjectMapper().readTree("[{\"quote\":\"위치 없음\"}]"));
        assertThat(new GeneralEvidenceProjection(storage,sources,stored).forProcessingCandidate(candidate)).isEmpty();
        verifyNoInteractions(storage,sources,stored);
    }
}
