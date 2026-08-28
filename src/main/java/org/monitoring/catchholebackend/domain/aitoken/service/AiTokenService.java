package org.monitoring.catchholebackend.domain.aitoken.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenExtensionCreateRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenExtensionRejectRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenReleaseRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenReserveRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenSettleRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenExtensionAdminResponse;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenFeedbackRewardResult;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenExtensionPendingResponse;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenExtensionRequestResponse;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenReservationResponse;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenUsageResponse;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenExtensionStatus;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;
import org.monitoring.catchholebackend.global.common.response.PageResponse;

public interface AiTokenService {

    /**
     * 회원의 누적 지급·사용·예약량을 조회한다.
     * 토큰 계정이 없으면 설정된 기본 지급량으로 최초 한 번 생성한다.
     */
    AiTokenUsageResponse getUsage(Long memberId);

    /** 피드백과 함께 추가 사용량을 요청한다. 기존 처리 대기 요청이 있으면 그 요청을 반환한다. */
    AiTokenExtensionRequestResponse createExtensionRequest(
            Long memberId,
            AiTokenExtensionCreateRequest request
    );

    /** 로그인한 회원의 처리 대기 요청을 조회한다. */
    AiTokenExtensionPendingResponse getPendingExtensionRequest(Long memberId);

    /** 일반 피드백 보상 요청을 회원당 한 번만 만든다. 다른 처리 대기 요청이 있으면 생성을 보류한다. */
    AiTokenFeedbackRewardResult createGeneralFeedbackRewardRequest(Long memberId, String feedback);

    /** 운영자가 상태별 추가 사용량 요청을 오래된 순서로 조회한다. */
    PageResponse<AiTokenExtensionAdminResponse> getExtensionRequests(
            AiTokenExtensionStatus status,
            int page,
            int size
    );

    /** 운영자가 요청 상세와 현재 토큰 계정 상태를 조회한다. */
    AiTokenExtensionAdminResponse getExtensionRequest(UUID requestId);

    /** 설정된 기본 지급량을 요청 회원에게 한 번 지급하고 MANUAL 원장을 남긴다. */
    AiTokenExtensionAdminResponse approveExtensionRequest(Long reviewerMemberId, UUID requestId);

    /** 추가 지급 없이 요청을 거절 처리한다. */
    AiTokenExtensionAdminResponse rejectExtensionRequest(
            Long reviewerMemberId,
            UUID requestId,
            AiTokenExtensionRejectRequest request
    );

    /**
     * 분석 작업 생성 전에 현재 잔여량이 있는지 확인한다.
     * 실제 동시 소비 방지는 Worker의 요청별 예약 단계에서 다시 검증한다.
     */
    void ensureAnalysisCanStart(Long memberId);

    /** 비교 전용 작업 생성 전에 최소 한 번의 비교 요청을 예약할 수 있는지 확인한다. */
    void ensureComparisonCanStart(Long memberId);

    /**
     * AI provider 호출 전에 예상 최대량을 요청 UUID 단위로 예약한다.
     * 회원 토큰 계정을 잠가 동시 요청이 같은 잔여량을 중복 예약하지 못하게 한다.
     */
    AiTokenReservationResponse reserve(AiTokenReserveRequest request, UUID leaseToken);

    /**
     * provider가 반환한 실제 input/output 사용량을 기록하고 남은 예약량을 반환한다.
     */
    void settle(UUID requestId, AiTokenSettleRequest request);

    /**
     * 사용량을 확인할 수 없는 요청의 예약량을 전부 반환한다.
     */
    void release(UUID requestId, AiTokenReleaseRequest request);

    /** Worker가 더 이상 실행할 수 없는 작업에 남은 예약을 반환한다. */
    void releaseReservedForAnalysisJob(UUID analysisJobId, AiTokenUsageOutcome outcome);

    /**
     * 분석 작업에 속한 정산 완료 요청만 합산해 input/output 토큰 수를 반환한다.
     */
    long[] getAnalysisJobTokenTotals(UUID analysisJobId);
}
