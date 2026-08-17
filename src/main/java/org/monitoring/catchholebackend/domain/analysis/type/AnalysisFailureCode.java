package org.monitoring.catchholebackend.domain.analysis.type;

/**
 * AI 분석 실패를 Worker와 Spring, Frontend가 공통으로 판별하기 위한 기계 판독용 코드다.
 * 원문 오류는 운영 진단용으로만 보관하고 사용자 응답에는 이 코드의 안전한 메시지를 사용한다.
 */
public enum AnalysisFailureCode {
    AI_TOKEN_QUOTA_EXHAUSTED("AI 토큰이 부족해 분석이 중단되었습니다."),
    LLM_OUTPUT_TRUNCATED("AI 응답 길이 제한으로 분석을 완료하지 못했습니다."),
    LLM_NETWORK_ERROR("AI 서비스 연결이 불안정해 분석을 완료하지 못했습니다."),
    LLM_PROVIDER_ERROR("AI 서비스 오류로 분석을 완료하지 못했습니다."),
    LLM_RESPONSE_PARSE_ERROR("AI 응답을 해석하지 못해 분석을 완료하지 못했습니다."),
    COMPARISON_VALIDATION_FAILED("설정 비교 결과를 검증하지 못했습니다."),
    WORKER_LEASE_EXPIRED("분석 처리 시간이 초과되어 작업이 중단되었습니다."),
    UNEXPECTED_ERROR("분석 중 오류가 발생했습니다.");

    private final String publicMessage;

    AnalysisFailureCode(String publicMessage) {
        this.publicMessage = publicMessage;
    }

    public String getPublicMessage() {
        return publicMessage;
    }

    public static AnalysisFailureCode orUnexpected(AnalysisFailureCode failureCode) {
        return failureCode == null ? UNEXPECTED_ERROR : failureCode;
    }
}
