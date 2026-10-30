package org.monitoring.catchholebackend.domain.analysis.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.GeneralEvidenceSourceRepository;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class GeneralEvidenceProjectionTest {
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test void mixedQuotesKeepOnlyExactUnicodeRangesWithoutChangingRawEvidence() throws Exception {
        String source = "😀“…활, 활이요!” 끝";
        var evidence = json.readTree("""
                [{"quote":"“…활, 활이요!”","start_offset":1,"end_offset":11},
                 {"quote":"“...활, 활이요!”","start_offset":null,"end_offset":null},
                 {"quote":"끝","startOffset":12,"endOffset":13},
                 {"quote":"끝","startOffset":-1,"endOffset":13},
                 {"quote":"끝","startOffset":12},
                 {"quote":"끝","startOffset":12,"start_offset":0,"endOffset":13},
                 {"quote":"끝","startOffset":12.0,"endOffset":13},
                 {"quote":"다른 인용","startOffset":12,"endOffset":13}]
                """);
        var before = evidence.deepCopy();
        var visible = GeneralEvidenceProjection.verifiedSpans(source, evidence);
        assertThat(visible).hasSize(2);
        assertThat(visible.get(0).path("quote").asText()).isEqualTo("“…활, 활이요!”");
        assertThat(visible.get(1).path("quote").asText()).isEqualTo("끝");
        assertThat(evidence).isEqualTo(before);
    }

    @Test void missingPositionsHideEvenAnExactOrRepeatedQuoteWithoutReadingStorage() throws Exception {
        var storage = mock(ObjectStorageService.class);
        var sources = mock(GeneralEvidenceSourceRepository.class);
        var projection = new GeneralEvidenceProjection(storage, sources);
        var candidate = mock(SettingCandidate.class);
        var work = mock(Work.class);
        when(work.getId()).thenReturn(UUID.randomUUID());
        when(candidate.getWork()).thenReturn(work);
        when(candidate.getAnalysisJob()).thenReturn(mock(AnalysisJob.class));
        var evidence = json.readTree("[{\"quote\":\"반복\",\"startOffset\":null,\"endOffset\":null}]");
        when(candidate.getEvidenceSpans()).thenReturn(evidence);
        assertThat(GeneralEvidenceProjection.verifiedSpans("반복 반복", evidence)).isEmpty();
        assertThat(projection.forCandidate(candidate)).isEmpty();
        verifyNoInteractions(storage, sources);
    }

    @Test void oneRequestReadsEachManifestAndSourceOnceWithoutInitializingJob() throws Exception {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        var storage = mock(ObjectStorageService.class);
        var sources = mock(GeneralEvidenceSourceRepository.class);
        var projection = new GeneralEvidenceProjection(storage, sources);
        UUID workId = UUID.randomUUID(), jobId = UUID.randomUUID();
        var work = mock(Work.class);
        var job = mock(AnalysisJob.class);
        var candidate = mock(SettingCandidate.class);
        when(work.getId()).thenReturn(workId);
        when(job.getId()).thenReturn(jobId);
        when(candidate.getWork()).thenReturn(work);
        when(candidate.getAnalysisJob()).thenReturn(job);
        when(candidate.getEvidenceSpans()).thenReturn(json.readTree("[{\"quote\":\"정상\",\"startOffset\":0,\"endOffset\":2}]"));
        when(sources.find(jobId)).thenReturn(new GeneralEvidenceSourceRepository.Source(workId, "frozen/key", null, "a".repeat(64)));
        when(storage.getText("frozen/key")).thenReturn("정상");
        when(storage.contentHash("정상")).thenReturn("a".repeat(64));
        assertThat(projection.forCandidate(candidate)).hasSize(1);
        assertThat(projection.forCandidate(candidate)).hasSize(1);
        verify(sources).find(jobId);
        verify(storage).getText("frozen/key");
        verify(job, never()).getWork();
        verify(job, never()).getSourceContentS3Key();
    }
}
