package org.monitoring.catchholebackend.domain.validation.type;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenPurpose;
/**
 * 검수 호출 역할. 모델·추론 강도·출력 상한은 코드가 아니라 실행 설정(configuration v2)의 역할별 값이다(#349 grid v4).
 * RELATED_SELECTION·COVERAGE는 grid v4에서 쓰지 않는 퇴역 역할이지만 저장 원장(AiTokenPurpose)과 설정 binding을 위해 상수를 남긴다.
 * 실행이 꺼진 기동에서는 남은 api.env 키가 binding되어도 실패하지 않고, 켜진 기동은 퇴역 역할 예산을 명시적으로 거절한다.
 * INDEX_EMBEDDING(2026-11-02)은 접수 때 그 작품의 빈 RAG 색인(앞 회차·현재 설정집)을 채우는 임베딩이며 서비스 부담(SERVICE_PAID_V1)이다.
 */
public enum ValidationRole {
    PLANNER, RELATED_SELECTION, COVERAGE, JUDGE, TYPO, QUERY_EMBEDDING, INDEX_EMBEDDING;
    /** 모델·추론 강도·출력 상한을 가진 텍스트 생성 역할(PLANNER, JUDGE, TYPO). */
    public boolean generation() { return this==PLANNER||this==JUDGE||this==TYPO; }
    /** grid v4에서 없어진 역할. 새 Job 설정에 쓰지 않는다. */
    public boolean retired() { return this==RELATED_SELECTION||this==COVERAGE; }
    /** 검색 색인 공간(model/version/dimensions)을 쓰는 임베딩 역할(QUERY_EMBEDDING, INDEX_EMBEDDING). */
    public boolean embedding() { return this==QUERY_EMBEDDING||this==INDEX_EMBEDDING; }
    public AiTokenPurpose purpose() { return AiTokenPurpose.valueOf("VALIDATION_"+name()); }
}
