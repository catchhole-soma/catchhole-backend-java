package org.monitoring.catchholebackend.domain.aitoken.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenReleaseRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenReserveRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenSettleRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenReservationResponse;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenUsageResponse;

public interface AiTokenService {

    /**
     * 회원의 누적 지급·사용·예약량을 조회한다.
     * 토큰 계정이 없으면 설정된 기본 지급량으로 최초 한 번 생성한다.
     */
    AiTokenUsageResponse getUsage(Long memberId);

    /**
     * 분석 작업 생성 전에 현재 잔여량이 있는지 확인한다.
     * 실제 동시 소비 방지는 Worker의 요청별 예약 단계에서 다시 검증한다.
     */
    void ensureAnalysisCanStart(Long memberId);

    /**
     * AI provider 호출 전에 예상 최대량을 요청 UUID 단위로 예약한다.
     * 회원 토큰 계정을 잠가 동시 요청이 같은 잔여량을 중복 예약하지 못하게 한다.
     */
    AiTokenReservationResponse reserve(AiTokenReserveRequest request);

    /**
     * provider가 반환한 실제 input/output 사용량을 기록하고 남은 예약량을 반환한다.
     */
    void settle(UUID requestId, AiTokenSettleRequest request);

    /**
     * 사용량을 확인할 수 없는 요청의 예약량을 전부 반환한다.
     */
    void release(UUID requestId, AiTokenReleaseRequest request);

    /**
     * 분석 작업에 속한 정산 완료 요청만 합산해 input/output 토큰 수를 반환한다.
     */
    long[] getAnalysisJobTokenTotals(UUID analysisJobId);
}
