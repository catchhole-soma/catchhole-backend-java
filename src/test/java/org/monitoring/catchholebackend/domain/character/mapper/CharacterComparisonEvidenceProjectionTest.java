package org.monitoring.catchholebackend.domain.character.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.processor.GeneralEvidenceProjection;
import org.monitoring.catchholebackend.domain.analysis.repository.GeneralEvidenceSourceRepository;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class CharacterComparisonEvidenceProjectionTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final GeneralEvidenceSourceRepository sources = mock(GeneralEvidenceSourceRepository.class);
    private final GeneralEvidenceProjection projection = new GeneralEvidenceProjection(storage, sources);
    private final CharacterFactComparisonWorkerMapper mapper = new CharacterFactComparisonWorkerMapper(projection);
    private final SettingCandidate candidate = mock(SettingCandidate.class);
    private final UUID workId = UUID.randomUUID(), jobId = UUID.randomUUID();
    private final String hash = "a".repeat(64);

    @BeforeEach void source() throws Exception {
        var work = mock(Work.class);
        var job = mock(AnalysisJob.class);
        when(work.getId()).thenReturn(workId);
        when(job.getId()).thenReturn(jobId);
        when(candidate.getWork()).thenReturn(work);
        when(candidate.getAnalysisJob()).thenReturn(job);
        when(candidate.getEvidenceSpans()).thenReturn(json.readTree("""
                [{"quote":"정상","startOffset":0,"endOffset":2}]
                """));
        when(sources.find(jobId)).thenReturn(new GeneralEvidenceSourceRepository.Source(workId, "frozen/key", null, hash));
        when(storage.getText("frozen/key")).thenReturn("정상");
        when(storage.contentHash("정상")).thenReturn(hash);
    }

    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @ParameterizedTest
    @CsvSource({"storage,false", "missing,false", "hash,false", "storage,true", "missing,true", "hash,true"})
    void processingFailsEvenAfterThePublicPreviewHidTheSameSourceFailure(String failure, boolean singleton) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        if (failure.equals("storage")) when(storage.getText("frozen/key")).thenThrow(new IllegalStateException("private storage details"));
        if (failure.equals("missing")) when(storage.getText("frozen/key")).thenReturn(null);
        if (failure.equals("hash")) when(storage.contentHash("정상")).thenReturn("b".repeat(64));
        assertThat(projection.forCandidate(candidate)).isEmpty();

        assertThatThrownBy(() -> {
            if (singleton) mapper.toCandidatePayload(candidate, "아라", CharacterFactType.PROFILE, "profile.외모");
            else mapper.toCandidateEvidenceSpans(candidate);
        }).isInstanceOfSatisfying(AppException.class, failureResult -> {
            assertThat(failureResult.getResultCode()).isEqualTo(failure.equals("hash")
                    ? AnalysisJobErrorCode.ANALYSIS_CURRENT_EVIDENCE_INVALID
                    : AnalysisJobErrorCode.ANALYSIS_CURRENT_EVIDENCE_SOURCE_UNAVAILABLE);
            assertThat(failureResult.getMessage()).doesNotContain("private storage details", "frozen/key");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[{\"quote\":\"정상\"}]", "[{\"quote\":\"정상\",\"startOffset\":null,\"endOffset\":null}]", "[{\"quote\":\"정상\",\"startOffset\":0}]"})
    void unavailableOffsetsSkipSourceReading(String evidence) throws Exception {
        when(candidate.getEvidenceSpans()).thenReturn(json.readTree(evidence));
        assertThat(mapper.toCandidateEvidenceSpans(candidate)).isEmpty();
        verifyNoInteractions(storage, sources);
    }

    @Test void mixedQuotesKeepValidEvidenceAndCacheOnlyVerifiedProcessingContent() throws Exception {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        var raw = json.readTree("""
                [{"quote":"정상","startOffset":0,"endOffset":2},
                 {"quote":"변경","startOffset":0,"endOffset":2},
                 {"quote":"정상","startOffset":-1,"endOffset":2},
                 {"quote":"정상","startOffset":0,"endOffset":20},
                 {"quote":"정상","startOffset":null,"endOffset":null}]
                """);
        var before = raw.deepCopy();
        when(candidate.getEvidenceSpans()).thenReturn(raw);
        assertThat(mapper.toCandidateEvidenceSpans(candidate)).singleElement().satisfies(span -> assertThat(span.quote()).isEqualTo("정상"));
        assertThat(mapper.toCandidatePayload(candidate, "아라", CharacterFactType.PROFILE, "profile.외모").evidenceSpans()).hasSize(1);
        assertThat(raw).isEqualTo(before);
        verify(storage).getText("frozen/key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"other-work", "missing-row", "missing-key", "missing-hash", "invalid-hash"})
    void invalidSourceBindingIsNotAnEmptySuccessfulProcessingInput(String failure) {
        var source = switch (failure) {
            case "other-work" -> new GeneralEvidenceSourceRepository.Source(UUID.randomUUID(), "frozen/key", null, hash);
            case "missing-key" -> new GeneralEvidenceSourceRepository.Source(workId, null, null, hash);
            case "missing-hash" -> new GeneralEvidenceSourceRepository.Source(workId, "frozen/key", null, null);
            case "invalid-hash" -> new GeneralEvidenceSourceRepository.Source(workId, "frozen/key", null, "invalid");
            default -> null;
        };
        when(sources.find(jobId)).thenReturn(source);
        assertThatThrownBy(() -> mapper.toCandidateEvidenceSpans(candidate))
                .isInstanceOfSatisfying(AppException.class, error -> assertThat(error.getResultCode())
                        .isEqualTo(AnalysisJobErrorCode.ANALYSIS_CURRENT_EVIDENCE_INVALID));
        verifyNoInteractions(storage);
    }

    @Test void legacyWithoutAManifestKeepsEvidenceUnavailable() {
        when(sources.find(jobId)).thenReturn(new GeneralEvidenceSourceRepository.Source(workId, null, null, null));
        assertThat(mapper.toCandidateEvidenceSpans(candidate)).isEmpty();
        verifyNoInteractions(storage);
    }
}
