package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.dto.request.AnalysisStartCheckRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.AnalysisJobCreateRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisStartCheckResponse;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisCandidateDomain;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisStartCheckMapper;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisReviewContinuationRepository;
import org.monitoring.catchholebackend.domain.relationship.service.RelationshipHoldReviewGate;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.hash.Sha256Hasher;

@DisplayName("분석 시작 확인 값의 리팩터링 전후 호환")
class AnalysisStartCheckServiceImplTest {
    @Test
    @DisplayName("관계 HOLD만 남으면 종료 동의 없이 다음 회차를 시작하고 HOLD를 유지한다")
    void relationshipHoldsDoNotRequireClosure() {
        UUID workId = id(1);
        AnalysisCandidateReviewGate candidates = mock(AnalysisCandidateReviewGate.class);
        RelationshipHoldReviewGate relationships = mock(RelationshipHoldReviewGate.class);
        when(candidates.snapshot(workId, 4)).thenReturn(new AnalysisCandidateReviewGate.Snapshot(
                workId, 4, List.of(), List.of(), List.of()));
        when(relationships.snapshot(workId, 4)).thenReturn(new RelationshipHoldReviewGate.Snapshot(
                workId, 4, List.of(new RelationshipHoldReviewGate.Entry(id(42), "O1", 3, "relation"))));
        var service = new AnalysisStartCheckServiceImpl(mock(WorkRepository.class), candidates, relationships,
                mock(AnalysisRunStateService.class), mock(AnalysisReviewContinuationRepository.class),
                mock(AnalysisJobRepository.class), new Sha256Hasher(), new AnalysisStartCheckMapper());

        service.applyAnalysisStartDecision(1L, workId, List.of(4),
                new AnalysisJobCreateRequest(AnalysisJobType.SETTING_EXTRACTION, id(5), null), id(6));

        verify(relationships, never()).closeHolds(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(candidates, never()).closeAmbiguous(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("설정 후보 종료에 동의해도 관계 HOLD는 후속 분석에 남긴다")
    void settingClosureDoesNotCloseRelationshipHolds() {
        UUID workId = id(1);
        AnalysisCandidateReviewGate candidates = mock(AnalysisCandidateReviewGate.class);
        RelationshipHoldReviewGate relationships = mock(RelationshipHoldReviewGate.class);
        var snapshot = new AnalysisCandidateReviewGate.Snapshot(workId, 4, List.of(),
                List.of(entry(AnalysisCandidateDomain.CHARACTER, 21, "character-hold")), List.of());
        when(candidates.snapshot(workId, 4)).thenReturn(snapshot);
        when(relationships.snapshot(workId, 4)).thenReturn(new RelationshipHoldReviewGate.Snapshot(
                workId, 4, List.of(new RelationshipHoldReviewGate.Entry(id(42), "O1", 3, "relation"))));
        var service = new AnalysisStartCheckServiceImpl(mock(WorkRepository.class), candidates, relationships,
                mock(AnalysisRunStateService.class), mock(AnalysisReviewContinuationRepository.class),
                mock(AnalysisJobRepository.class), new Sha256Hasher(), new AnalysisStartCheckMapper());
        var revision = service.checkAnalysisStart(1L, workId, new AnalysisStartCheckRequest(List.of(4))).revision();

        service.applyAnalysisStartDecision(1L, workId, List.of(4),
                new AnalysisJobCreateRequest(AnalysisJobType.SETTING_EXTRACTION, id(5), null,
                        null, null, true, revision), id(6));

        verify(candidates).closeAmbiguous(snapshot, id(6));
        verify(relationships, never()).closeHolds(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("기존 확인 값과 전체 응답을 보존하고 회차 입력 순서·중복에 영향을 받지 않는다")
    void preservesExistingRevisionAndResponseAcrossExtraction() {
        UUID workId = id(1);
        AnalysisCandidateReviewGate candidates = mock(AnalysisCandidateReviewGate.class);
        RelationshipHoldReviewGate relationships = mock(RelationshipHoldReviewGate.class);
        AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
        var first = new AnalysisCandidateReviewGate.Snapshot(workId, 3, List.of(), List.of(
                entry(AnalysisCandidateDomain.WORLD, 22, "보류|world"),
                entry(AnalysisCandidateDomain.CHARACTER, 21, "보류|character")),
                List.of(entry(AnalysisCandidateDomain.WORLD, 31, "실패|recover")));
        var last = new AnalysisCandidateReviewGate.Snapshot(workId, 4, List.of(
                entry(AnalysisCandidateDomain.WORLD, 12, "수동|second"),
                entry(AnalysisCandidateDomain.CHARACTER, 11, "수동|first")), List.of(), List.of());
        when(candidates.snapshot(workId, 3)).thenReturn(first);
        when(candidates.snapshot(workId, 4)).thenReturn(last);
        when(jobs.countActiveEarlierExtractions(workId, 4)).thenReturn(2L);
        when(relationships.snapshot(workId, 3)).thenReturn(new RelationshipHoldReviewGate.Snapshot(workId, 3, List.of(
                new RelationshipHoldReviewGate.Entry(id(42), "O1", 2, "relation-two"),
                new RelationshipHoldReviewGate.Entry(id(41), "O2", 1, "relation-one"))));
        var service = new AnalysisStartCheckServiceImpl(mock(WorkRepository.class), candidates, relationships,
                mock(AnalysisRunStateService.class), mock(AnalysisReviewContinuationRepository.class), jobs,
                new Sha256Hasher(), new AnalysisStartCheckMapper());

        var response = service.checkAnalysisStart(1L, workId, new AnalysisStartCheckRequest(List.of(4, 3, 4)));

        // Golden value from the pre-refactor UTF-8 material, including ordering and delimiters.
        assertThat(response).isEqualTo(new AnalysisStartCheckResponse(2, 2, 1, 1, 2,
                "bbc2a365571d5b3f467d701c3cc5e26d1cb73aec3ff79586c580aea9f8a99003"));
        assertThat(service.checkAnalysisStart(1L, workId, new AnalysisStartCheckRequest(List.of(3, 4)))).isEqualTo(response);
    }

    private static AnalysisCandidateReviewGate.Entry entry(AnalysisCandidateDomain domain, int number, String revision) {
        return new AnalysisCandidateReviewGate.Entry(domain, id(number), id(99), LocalDateTime.of(2026, 11, 3, 12, 30, 45), revision);
    }

    private static UUID id(int value) { return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value)); }
}
