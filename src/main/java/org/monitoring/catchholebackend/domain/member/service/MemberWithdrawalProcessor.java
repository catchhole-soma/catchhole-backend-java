package org.monitoring.catchholebackend.domain.member.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.entity.MemberWithdrawalRequest;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.member.repository.MemberWithdrawalDataRepository;
import org.monitoring.catchholebackend.domain.member.repository.MemberWithdrawalRequestRepository;
import org.monitoring.catchholebackend.domain.member.type.MemberWithdrawalStatus;
import org.monitoring.catchholebackend.domain.work.service.MemberWorkPurgeCoordinator;
import org.monitoring.catchholebackend.domain.work.service.MemberWorkPurgeProgress;
import org.monitoring.catchholebackend.global.config.memberwithdrawal.MemberWithdrawalProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class MemberWithdrawalProcessor {

    private static final String PROCESSING_FAILED_ERROR = "MEMBER_WITHDRAWAL_PROCESSING_FAILED";
    private static final List<MemberWithdrawalStatus> PROCESSABLE_STATUSES = List.of(
            MemberWithdrawalStatus.REQUESTED,
            MemberWithdrawalStatus.PROCESSING
    );

    private final MemberWithdrawalRequestRepository withdrawalRequestRepository;
    private final MemberRepository memberRepository;
    private final MemberWithdrawalDataRepository withdrawalDataRepository;
    private final MemberWorkPurgeCoordinator workPurgeCoordinator;
    private final MemberWithdrawalProperties properties;
    private final TransactionTemplate transactionTemplate;

    public void processPendingRequests() {
        List<UUID> requestIds = withdrawalRequestRepository.findReadyIds(
                PROCESSABLE_STATUSES,
                LocalDateTime.now(),
                PageRequest.of(0, properties.getBatchSize())
        );
        requestIds.forEach(this::processSafely);
    }

    public void deleteExpiredAuditRecords() {
        transactionTemplate.executeWithoutResult(status ->
                withdrawalRequestRepository.deleteExpired(LocalDateTime.now()));
    }

    private void processSafely(UUID requestId) {
        try {
            transactionTemplate.executeWithoutResult(status -> process(requestId));
        } catch (RuntimeException exception) {
            log.error("회원 탈퇴 영구 파기 처리 실패: requestId={}", requestId, exception);
            recordRetryableFailure(requestId);
        }
    }

    private void process(UUID requestId) {
        MemberWithdrawalRequest withdrawalRequest = withdrawalRequestRepository.findByIdForUpdate(requestId)
                .orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (withdrawalRequest == null || !withdrawalRequest.isReady(now)) {
            return;
        }

        withdrawalRequest.beginAttempt(now);
        Member member = memberRepository.findByIdForUpdate(withdrawalRequest.getMemberId()).orElse(null);
        if (member == null) {
            complete(withdrawalRequest, now);
            return;
        }
        if (!member.isPurging()) {
            throw new IllegalStateException("PURGING 상태가 아닌 회원의 탈퇴 요청입니다.");
        }

        MemberWorkPurgeProgress workProgress = workPurgeCoordinator.coordinateForWithdrawal(member.getId());
        if (workProgress.remainingWorkCount() > 0) {
            log.debug(
                    "회원 탈퇴 작품 파기 대기: requestId={}, remainingWorks={}, created={}, retried={}",
                    requestId,
                    workProgress.remainingWorkCount(),
                    workProgress.createdRequestCount(),
                    workProgress.retriedRequestCount()
            );
            withdrawalRequest.deferUntil(now.plus(properties.getRetryDelay()));
            return;
        }

        withdrawalDataRepository.purgeMemberReferences(member.getId(), now);
        memberRepository.delete(member);
        memberRepository.flush();
        complete(withdrawalRequest, now);
    }

    private void complete(MemberWithdrawalRequest withdrawalRequest, LocalDateTime completedAt) {
        withdrawalRequest.complete(
                completedAt,
                completedAt.plus(properties.getAuditRetention())
        );
    }

    private void recordRetryableFailure(UUID requestId) {
        try {
            transactionTemplate.executeWithoutResult(status -> withdrawalRequestRepository
                    .findByIdForUpdate(requestId)
                    .filter(request -> request.getStatus() != MemberWithdrawalStatus.COMPLETED)
                    .ifPresent(request -> {
                        LocalDateTime failedAt = LocalDateTime.now();
                        request.retryAfterFailure(
                                PROCESSING_FAILED_ERROR,
                                failedAt,
                                failedAt.plus(properties.getRetryDelay())
                        );
                    }));
        } catch (RuntimeException exception) {
            log.error("회원 탈퇴 재시도 상태 기록 실패: requestId={}", requestId, exception);
        }
    }
}
