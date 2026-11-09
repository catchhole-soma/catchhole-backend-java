package org.monitoring.catchholebackend.domain.analysis.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.dto.request.AnalysisJobCreateRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.AnalysisStartCheckRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisStartCheckResponse;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisReviewContinuation;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisStartCheckMapper;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisReviewContinuationRepository;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.relationship.service.RelationshipHoldReviewGate;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.hash.Sha256Hasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisStartCheckServiceImpl implements AnalysisStartCheckService {
    private final WorkRepository works;
    private final AnalysisCandidateReviewGate candidates;
    private final RelationshipHoldReviewGate relationships;
    private final AnalysisRunStateService runStates;
    private final AnalysisReviewContinuationRepository continuations;
    private final AnalysisJobRepository jobs;
    private final Sha256Hasher hasher;
    private final AnalysisStartCheckMapper mapper;

    @Override
    public AnalysisStartCheckResponse checkAnalysisStart(Long memberId, UUID workId, AnalysisStartCheckRequest request) {
        works.getOwnedWork(workId, memberId);
        return capture(workId, request.episodeNumbers()).response();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyAnalysisStartDecision(Long memberId, UUID workId, List<Integer> episodeNumbers,
            AnalysisJobCreateRequest request, UUID requestId) {
        var check = capture(workId, episodeNumbers);
        if (check.response().activeEarlierAnalysisCount() > 0) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_PREVIOUS_ANALYSIS_IN_PROGRESS);
        }
        if (check.response().manualReviewCount() > 0) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_MANUAL_REVIEW_REQUIRED);
        }
        boolean acknowledged = Boolean.TRUE.equals(request.closeAmbiguousCandidates());
        // 후보가 줄었어도 다른 범위/오래된 확인으로 새 분석을 만들지 않는다.
        if (acknowledged && !check.response().revision().equals(request.reviewGateRevision())) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_REVIEW_GATE_CHANGED);
        }
        // Relationship HOLDs are retained for later episode comparison, not closed at this gate.
        if (check.candidates().ambiguous().isEmpty()) return;
        if (!acknowledged) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_REVIEW_ACKNOWLEDGEMENT_REQUIRED);
        }
        // 이미 고정된 입력을 가진 미완료 실행이 있으면 그 참고 후보를 종료하지 않는다.
        runStates.assertSettingMutationAllowed(workId);
        candidates.closeAmbiguous(check.candidates(), requestId);
        var response = check.response();
        continuations.save(AnalysisReviewContinuation.record(requestId, workId, memberId,
                response.revision(), check.episodeNumbers().getFirst(), response.ambiguousCharacterCount(),
                response.ambiguousWorldCount(), 0));
    }

    private Check capture(UUID workId, List<Integer> numbers) {
        List<Integer> sortedNumbers = numbers.stream().distinct().sorted().toList();
        int first = sortedNumbers.getFirst();
        int last = sortedNumbers.getLast();
        var candidateSnapshot = candidates.snapshot(workId, first);
        // [1, 2]처럼 앞 회차를 요청에 끼워 넣어 1화 직접 검토를 우회하지 못하게 한다.
        var manualSnapshot = last == first ? candidateSnapshot : candidates.snapshot(workId, last);
        long activeEarlier = jobs.countActiveEarlierExtractions(workId, last);
        var relationshipSnapshot = relationships.snapshot(workId, first);
        StringBuilder material = new StringBuilder("ANALYSIS_REVIEW_CONTINUATION_V1\n")
                .append(workId).append('\n').append(sortedNumbers).append('\n').append(activeEarlier).append('\n');
        appendEntries(material, "manual", manualSnapshot.manualRequired());
        appendEntries(material, "ambiguous", candidateSnapshot.ambiguous());
        appendEntries(material, "recovery", candidateSnapshot.recoveryRequired());
        relationshipSnapshot.entries().stream()
                .sorted(Comparator.comparing(e -> e.analysisId() + ":" + e.observationRef()))
                .forEach(e -> material.append("relationship:").append(e.analysisId()).append(':')
                        .append(e.observationRef()).append(':').append(e.revision()).append('\n'));
        var response = mapper.toResponse(activeEarlier, manualSnapshot.manualRequired().size(),
                candidateSnapshot, relationshipSnapshot, hasher.hash(material.toString()));
        return new Check(sortedNumbers, candidateSnapshot, relationshipSnapshot, response);
    }

    private void appendEntries(StringBuilder material, String kind, List<AnalysisCandidateReviewGate.Entry> entries) {
        entries.stream().sorted(Comparator.comparing(e -> e.domain() + ":" + e.id()))
                .forEach(e -> material.append(kind).append(':').append(e.domain()).append(':').append(e.id())
                        .append(':').append(e.sourceJobId()).append(':').append(e.updatedAt())
                        .append(':').append(e.revision()).append('\n'));
    }

    private record Check(List<Integer> episodeNumbers, AnalysisCandidateReviewGate.Snapshot candidates,
            RelationshipHoldReviewGate.Snapshot relationships, AnalysisStartCheckResponse response) {}
}
