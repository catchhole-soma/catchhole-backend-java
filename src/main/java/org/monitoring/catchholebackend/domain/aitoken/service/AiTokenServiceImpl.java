package org.monitoring.catchholebackend.domain.aitoken.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenReleaseRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenReserveRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenSettleRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenReservationResponse;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenUsageResponse;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenAccount;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenGrant;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenUsage;
import org.monitoring.catchholebackend.domain.aitoken.exception.AiTokenErrorCode;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenAccountRepository;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenGrantRepository;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenUsageRepository;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenTotals;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenGrantType;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageStatus;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.exception.MemberErrorCode;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.global.config.ai.AiTokenProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AiTokenServiceImpl implements AiTokenService {

    private final AiTokenAccountRepository accountRepository;
    private final AiTokenGrantRepository grantRepository;
    private final AiTokenUsageRepository usageRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final MemberRepository memberRepository;
    private final AiTokenProperties properties;

    @Override
    @Transactional
    public AiTokenUsageResponse getUsage(Long memberId) {
        return toResponse(getOrCreateAccount(memberId));
    }

    @Override
    @Transactional
    public void ensureAnalysisCanStart(Long memberId) {
        if (getOrCreateAccount(memberId).remainingTokens() <= 0) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_QUOTA_EXHAUSTED);
        }
    }

    @Override
    @Transactional
    public AiTokenReservationResponse reserve(AiTokenReserveRequest request) {
        AnalysisJob job = analysisJobRepository.findByIdForUpdate(request.analysisJobId())
                .orElseThrow(() -> new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_NOT_FOUND));
        AiTokenUsage existing = usageRepository.findByRequestId(request.requestId()).orElse(null);
        if (existing != null) {
            return toReservationResponse(existing);
        }
        if (job.getStatus() != AnalysisJobStatus.RUNNING) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_STATUS_CONFLICT);
        }
        AiTokenAccount account = getOrCreateAccount(job.getWork().getMember().getId());
        account.reserve(request.reservedTokens());
        AiTokenUsage usage = usageRepository.save(AiTokenUsage.reserve(
                request.requestId(),
                job,
                request.purpose(),
                request.attempt(),
                request.modelName(),
                request.reservedTokens()
        ));
        return toReservationResponse(usage);
    }

    @Override
    @Transactional
    public void settle(UUID requestId, AiTokenSettleRequest request) {
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
    }

    @Override
    @Transactional
    public void release(UUID requestId, AiTokenReleaseRequest request) {
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

    private AiTokenUsage getUsageForUpdate(UUID requestId) {
        return usageRepository.findByRequestId(requestId)
                .orElseThrow(() -> new AppException(AiTokenErrorCode.AI_TOKEN_RESERVATION_NOT_FOUND));
    }

    private AiTokenUsageResponse toResponse(AiTokenAccount account) {
        long remaining = account.remainingTokens();
        double percent = account.getGrantedTokens() == 0
                ? 0
                : Math.round((remaining * 10000.0) / account.getGrantedTokens()) / 100.0;
        return new AiTokenUsageResponse(
                account.getGrantedTokens(),
                account.getUsedTokens(),
                account.getReservedTokens(),
                remaining,
                percent,
                remaining == 0,
                properties.contactEmail()
        );
    }

    private AiTokenReservationResponse toReservationResponse(AiTokenUsage usage) {
        return new AiTokenReservationResponse(
                usage.getRequestId(),
                usage.getReservedTokens(),
                usage.getStatus()
        );
    }
}
