package org.monitoring.catchholebackend.domain.validation.type;

/** 오류 리포트 상세의 근거 문장·인용이 어디에서 왔는지(오류 리포트 가독성, 2026-11-02). 화면 표기는 서버가 만든 label을 쓴다. */
public enum ValidationQuoteSourceKind {
    /** 고정 기준의 저장 설정값(캐릭터 slot 값·세계관 설정값). 원고 위치가 없다. */
    SETTING,
    /** 이전 회차 또는 이번 원고 원문. */
    EPISODE,
    /** 작가가 올린 설정집 원문. */
    SETTING_BOOK
}
