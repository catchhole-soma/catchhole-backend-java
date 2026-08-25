package org.monitoring.catchholebackend.domain.aitoken.service;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenExtensionCreateRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenExtensionRejectRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenReleaseRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenReserveRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenSettleRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenExtensionAdminResponse;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenExtensionPendingResponse;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenExtensionRequestResponse;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenReservationResponse;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenUsageResponse;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenAccount;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenExtensionRequest;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenGrant;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenUsage;
import org.monitoring.catchholebackend.domain.aitoken.exception.AiTokenErrorCode;
import org.monitoring.catchholebackend.domain.aitoken.mapper.AiTokenMapper;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenAccountRepository;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenExtensionRequestRepository;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenGrantRepository;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenUsageRepository;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenTotals;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenExtensionStatus;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenGrantType;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageStatus;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobLeaseService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.exception.MemberErrorCode;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.global.config.ai.AiTokenProperties;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AiTokenServiceImpl implements AiTokenService {

    private static final int MIN_EXTENSION_FEEDBACK_LENGTH = 35;
    private static final int MAX_EXTENSION_FEEDBACK_LENGTH = 1000;
    private static final int MAX_REJECTION_REASON_LENGTH = 500;

    private final AiTokenAccountRepository accountRepository;
    private final AiTokenExtensionRequestRepository extensionRequestRepository;
    private final AiTokenGrantRepository grantRepository;
    private final AiTokenUsageRepository usageRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final AnalysisJobLeaseService analysisJobLeaseService;
    private final MemberRepository memberRepository;
    private final AiTokenProperties properties;
    private final AiTokenMapper aiTokenMapper;

    @Override
    @Transactional
    public AiTokenUsageResponse getUsage(Long memberId) {
        return aiTokenMapper.toResponse(
                getOrCreateAccount(memberId),
                properties.defaultGrant(),
                properties.contactEmail()
        );
    }

    @Override
    @Transactional
    public AiTokenExtensionRequestResponse createExtensionRequest(
            Long memberId,
            AiTokenExtensionCreateRequest request
    ) {
        String feedback = normalizeFeedback(request.feedback());
        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new AppException(MemberErrorCode.MEMBER_NOT_FOUND));
        member.validateActive();
        getOrCreateAccount(memberId);

        AiTokenExtensionRequest pendingRequest = extensionRequestRepository
                .findFirstByMemberIdAndStatusOrderByCreatedAtDesc(memberId, AiTokenExtensionStatus.PENDING)
                .orElse(null);
        if (pendingRequest != null) {
            return aiTokenMapper.toResponse(pendingRequest);
        }

        return aiTokenMapper.toResponse(extensionRequestRepository.save(
                AiTokenExtensionRequest.request(member, feedback, request.context())
        ));
    }

    @Override
    public AiTokenExtensionPendingResponse getPendingExtensionRequest(Long memberId) {
        return aiTokenMapper.toPendingResponse(extensionRequestRepository
                .findFirstByMemberIdAndStatusOrderByCreatedAtDesc(
                        memberId,
                        AiTokenExtensionStatus.PENDING
                ));
    }

    @Override
    @Transactional
    public PageResponse<AiTokenExtensionAdminResponse> getExtensionRequests(
            AiTokenExtensionStatus status,
            int page,
            int size
    ) {
        Page<AiTokenExtensionRequest> requestPage = extensionRequestRepository
                .findAllByStatusOrderByCreatedAtAsc(status, PageRequest.of(page, size));
        return PageResponse.from(
                requestPage,
                requestPage.getContent().stream()
                        .map(request -> aiTokenMapper.toAdminResponse(
                                request,
                                getOrCreateAccount(request.getMember().getId())
                        ))
                        .toList()
        );
    }

    @Override
    @Transactional
    public AiTokenExtensionAdminResponse getExtensionRequest(UUID requestId) {
        AiTokenExtensionRequest request = getExtensionRequestOrThrow(requestId);
        return aiTokenMapper.toAdminResponse(
                request,
                getOrCreateAccount(request.getMember().getId())
        );
    }

    @Override
    @Transactional
    public AiTokenExtensionAdminResponse approveExtensionRequest(Long reviewerMemberId, UUID requestId) {
        memberRepository.getByIdOrThrow(reviewerMemberId);
        AiTokenExtensionRequest request = getExtensionRequestForUpdate(requestId);
        AiTokenAccount account = getOrCreateAccount(request.getMember().getId());
        if (request.isApproved()) {
            return aiTokenMapper.toAdminResponse(request, account);
        }
        if (request.isRejected()) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_EXTENSION_REVIEW_CONFLICT);
        }

        long grantAmount = properties.defaultGrant();
        if (grantAmount <= 0) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_EXTENSION_GRANT_DISABLED);
        }
        account.grant(grantAmount);
        request.approve(reviewerMemberId, grantAmount, LocalDateTime.now());
        grantRepository.save(AiTokenGrant.createManual(
                request.getMember(),
                grantAmount,
                "추가 사용량 요청 승인: " + request.getId(),
                request
        ));
        return aiTokenMapper.toAdminResponse(request, account);
    }

    @Override
    @Transactional
    public AiTokenExtensionAdminResponse rejectExtensionRequest(
            Long reviewerMemberId,
            UUID requestId,
            AiTokenExtensionRejectRequest rejectRequest
    ) {
        String reason = normalizeRejectionReason(rejectRequest.reason());
        memberRepository.getByIdOrThrow(reviewerMemberId);
        AiTokenExtensionRequest request = getExtensionRequestForUpdate(requestId);
        AiTokenAccount account = getOrCreateAccount(request.getMember().getId());
        if (request.isRejected()) {
            return aiTokenMapper.toAdminResponse(request, account);
        }
        if (request.isApproved()) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_EXTENSION_REVIEW_CONFLICT);
        }

        request.reject(reviewerMemberId, reason, LocalDateTime.now());
        return aiTokenMapper.toAdminResponse(request, account);
    }

    @Override
    @Transactional
    public void ensureAnalysisCanStart(Long memberId) {
        ensureMinimumReservation(memberId, properties.minimumAnalysisReservation());
    }

    @Override
    @Transactional
    public void ensureComparisonCanStart(Long memberId) {
        ensureMinimumReservation(memberId, properties.minimumComparisonReservation());
    }

    private void ensureMinimumReservation(Long memberId, long minimumReservation) {
        if (getOrCreateAccount(memberId).remainingTokens() < minimumReservation) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_QUOTA_EXHAUSTED);
        }
    }

    @Override
    @Transactional
    public AiTokenReservationResponse reserve(AiTokenReserveRequest request, UUID leaseToken) {
        AnalysisJob analysisJob = analysisJobLeaseService.getRunningAnalysisJobForUpdate(
                request.analysisJobId(),
                leaseToken
        );
        AiTokenUsage existing = usageRepository.findByRequestId(request.requestId()).orElse(null);
        if (existing != null) {
            return aiTokenMapper.toResponse(existing);
        }
        AiTokenAccount account = getOrCreateAccount(analysisJob.getWork().getMember().getId());
        account.reserve(request.reservedTokens());
        AiTokenUsage usage = usageRepository.save(AiTokenUsage.reserve(
                request.requestId(),
                analysisJob,
                request.purpose(),
                request.attempt(),
                request.modelName(),
                request.reservedTokens()
        ));
        return aiTokenMapper.toResponse(usage);
    }

    @Override
    @Transactional
    public void settle(UUID requestId, AiTokenSettleRequest request) {
        AnalysisJob analysisJob = lockAnalysisJobForUsage(requestId);
        AiTokenUsage usage = getUsageForUpdate(requestId);
        if (usage.getStatus() == AiTokenUsageStatus.SETTLED) {
            return;
        }
        if (usage.getStatus() != AiTokenUsageStatus.RESERVED) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_RESERVATION_CONFLICT);
        }
        AiTokenAccount account = getOrCreateAccount(usage.getMember().getId());
        long actualTokens = usage.settle(
                request.inputTokens(),
                request.cachedInputTokens(),
                request.outputTokens(),
                request.outcome()
        );
        account.settle(usage.getReservedTokens(), actualTokens);
        synchronizeTerminalJobTokenTotals(analysisJob);
    }

    @Override
    @Transactional
    public void release(UUID requestId, AiTokenReleaseRequest request) {
        lockAnalysisJobForUsage(requestId);
        AiTokenUsage usage = getUsageForUpdate(requestId);
        if (usage.getStatus() == AiTokenUsageStatus.RELEASED) {
            return;
        }
        if (usage.getStatus() != AiTokenUsageStatus.RESERVED) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_RESERVATION_CONFLICT);
        }
        AiTokenAccount account = getOrCreateAccount(usage.getMember().getId());
        account.release(usage.getReservedTokens());
        usage.release(request.outcome());
    }

    @Override
    @Transactional
    public void releaseReservedForAnalysisJob(UUID analysisJobId, AiTokenUsageOutcome outcome) {
        if (outcome != AiTokenUsageOutcome.WORKER_LEASE_EXPIRED
                && outcome != AiTokenUsageOutcome.USAGE_UNAVAILABLE
                && outcome != AiTokenUsageOutcome.WORK_PURGE_CANCELED) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_USAGE_INVALID);
        }
        usageRepository.findAllByAnalysisJobIdAndStatus(analysisJobId, AiTokenUsageStatus.RESERVED)
                .forEach(usage -> {
                    AiTokenAccount account = getOrCreateAccount(usage.getMember().getId());
                    account.release(usage.getReservedTokens());
                    usage.release(outcome);
                });
    }

    @Override
    public long[] getAnalysisJobTokenTotals(UUID analysisJobId) {
        AiTokenTotals totals = usageRepository.sumSettledTokensByAnalysisJobId(
                analysisJobId,
                AiTokenUsageStatus.SETTLED
        );
        return new long[]{totals.getInputTokens(), totals.getOutputTokens()};
    }

    private AiTokenAccount getOrCreateAccount(Long memberId) {
        AiTokenAccount account = accountRepository.findByMemberId(memberId).orElse(null);
        if (account != null) {
            return account;
        }
        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new AppException(MemberErrorCode.MEMBER_NOT_FOUND));
        account = accountRepository.findByMemberId(memberId).orElse(null);
        if (account != null) {
            return account;
        }
        AiTokenAccount created = accountRepository.save(AiTokenAccount.create(member, properties.defaultGrant()));
        if (properties.defaultGrant() > 0) {
            grantRepository.save(AiTokenGrant.create(
                    member,
                    properties.defaultGrant(),
                    AiTokenGrantType.DEFAULT,
                    "기본 사용자 최초 지급"
            ));
        }
        return created;
    }

    private AiTokenExtensionRequest getExtensionRequestOrThrow(UUID requestId) {
        return extensionRequestRepository.findById(requestId)
                .orElseThrow(() -> new AppException(AiTokenErrorCode.AI_TOKEN_EXTENSION_REQUEST_NOT_FOUND));
    }

    private AiTokenExtensionRequest getExtensionRequestForUpdate(UUID requestId) {
        return extensionRequestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new AppException(AiTokenErrorCode.AI_TOKEN_EXTENSION_REQUEST_NOT_FOUND));
    }

    private String normalizeFeedback(String feedback) {
        String normalized = feedback == null ? "" : feedback.strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < MIN_EXTENSION_FEEDBACK_LENGTH
                || length > MAX_EXTENSION_FEEDBACK_LENGTH) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_EXTENSION_FEEDBACK_INVALID);
        }
        return normalized;
    }

    private String normalizeRejectionReason(String reason) {
        String normalized = reason == null ? "" : reason.strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length == 0 || length > MAX_REJECTION_REASON_LENGTH) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_EXTENSION_REJECTION_REASON_INVALID);
        }
        return normalized;
    }

    private AiTokenUsage getUsageForUpdate(UUID requestId) {
        return usageRepository.findByRequestId(requestId)
                .orElseThrow(() -> new AppException(AiTokenErrorCode.AI_TOKEN_RESERVATION_NOT_FOUND));
    }

    private AnalysisJob lockAnalysisJobForUsage(UUID requestId) {
        UUID analysisJobId = usageRepository.findAnalysisJobIdByRequestId(requestId)
                .orElseThrow(() -> new AppException(AiTokenErrorCode.AI_TOKEN_RESERVATION_NOT_FOUND));
        return analysisJobRepository.findByIdForUpdate(analysisJobId)
                .orElseThrow(() -> new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_NOT_FOUND));
    }

    private void synchronizeTerminalJobTokenTotals(AnalysisJob analysisJob) {
        if (analysisJob.getStatus() == AnalysisJobStatus.RUNNING) {
            return;
        }
        long[] totals = getAnalysisJobTokenTotals(analysisJob.getId());
        analysisJob.updateTokenCounts(Math.toIntExact(totals[0]), Math.toIntExact(totals[1]));
    }
}
