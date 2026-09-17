package org.monitoring.catchholebackend.domain.analysis.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisGuideResponse;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.exception.MemberErrorCode;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisGuideServiceImpl implements AnalysisGuideService {
    private final MemberRepository members;
    private final AnalysisJobRepository jobs;

    @Override
    public AnalysisGuideResponse getGuide(Long memberId) {
        Member member = members.getByIdOrThrow(memberId);
        member.validateActive();
        return new AnalysisGuideResponse(eligible(member));
    }

    @Override
    @Transactional
    public AnalysisGuideResponse claimGuide(Long memberId) {
        Member member = lockedMember(memberId);
        boolean shouldShow = eligible(member);
        if (shouldShow) member.markAnalysisGuideShown(LocalDateTime.now());
        return new AnalysisGuideResponse(shouldShow);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markAnalysisStarted(Long memberId) {
        // 분석 생성과 같은 트랜잭션에서 기록하므로 생성 실패 시 함께 롤백된다.
        lockedMember(memberId).markFirstAnalysisStarted(LocalDateTime.now());
    }

    private Member lockedMember(Long memberId) {
        Member member = members.findByIdForUpdate(memberId)
                .orElseThrow(() -> new AppException(MemberErrorCode.MEMBER_NOT_FOUND));
        member.validateActive();
        return member;
    }

    private boolean eligible(Member member) {
        return member.getAnalysisGuideShownAt() == null && member.getFirstAnalysisStartedAt() == null
                && !jobs.existsByWork_Member_Id(member.getId());
    }
}
