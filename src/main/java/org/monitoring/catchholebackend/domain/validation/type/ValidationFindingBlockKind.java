package org.monitoring.catchholebackend.domain.validation.type;

/**
 * 오류 리포트 문맥 블록(2026-11-03)의 종류. 상세 화면은 위에서부터 TARGET(이번 원고) → SETTING(기준: 저장 설정값) →
 * EPISODE·SETTING_BOOK(근거: 판정이 옮긴 이전 원문 문장, 회차 순) 순서로 쌓는다. 화면 머리말의 "이번 원고"·"기준"·"근거"는 Front가 종류로 고른다.
 */
public enum ValidationFindingBlockKind {
    /** 이번 원고에서 질문·오탈자가 가리킨 위치 하나. */
    TARGET,
    /** 판정이 옮긴 저장 설정값 문장. 원고 위치가 없다. */
    SETTING,
    /** 판정이 옮긴 이전 회차 원문 문장. */
    EPISODE,
    /** 판정이 옮긴 설정집 원문 문장. */
    SETTING_BOOK
}
