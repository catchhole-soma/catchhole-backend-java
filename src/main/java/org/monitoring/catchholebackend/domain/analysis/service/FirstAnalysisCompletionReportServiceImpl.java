package org.monitoring.catchholebackend.domain.analysis.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.dto.response.FirstAnalysisCompletionReportResponse;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.exception.MemberErrorCode;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 첫 분석 완료는 Worker 완료 경로에서 기록하지 않고 조회 때 남아 있는 Job으로 판정한다.
// 완료 트랜잭션(Work → Job)에 회원 쓰기를 더하면 분석 생성(회원 → Work)과 잠금 순서가 엇갈리기 때문이다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FirstAnalysisCompletionReportServiceImpl implements FirstAnalysisCompletionReportService {
    private final MemberRepository members;
    private final AnalysisJobRepository jobs;

    @Override
    public FirstAnalysisCompletionReportResponse getFirstAnalysisCompletionReport(Long memberId) {
        Member member = members.getByIdOrThrow(memberId);
        member.validateActive();
        return new FirstAnalysisCompletionReportResponse(reportable(member));
    }

    @Override
    @Transactional
    public FirstAnalysisCompletionReportResponse claimFirstAnalysisCompletionReport(Long memberId) {
        Member member = members.findByIdForUpdate(memberId)
                .orElseThrow(() -> new AppException(MemberErrorCode.MEMBER_NOT_FOUND));
        member.validateActive();
        boolean shouldReport = reportable(member);
        if (shouldReport) member.markFirstAnalysisCompletionReported(LocalDateTime.now());
        return new FirstAnalysisCompletionReportResponse(shouldReport);
    }

    private boolean reportable(Member member) {
        return member.getFirstAnalysisCompletionReportedAt() == null
                && jobs.existsCompletedSettingExtractionByMemberId(member.getId());
    }
}
