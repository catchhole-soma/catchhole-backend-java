package org.monitoring.catchholebackend.domain.validation.type;

/**
 * 문맥 블록의 앞뒤 줄을 읽었는지. READY가 아니면 인용 문장만 보이고 앞뒤 줄은 비운다.
 * 검사 당시 고정 원문과 다른 원문을 대신 보여 주지 않기 위해 hash가 맞지 않으면 읽지 않는다.
 */
public enum ValidationSourceContextStatus {
    READY,
    /** 원고·설정집이 검사 뒤에 바뀌어 고정 hash와 다르다. */
    SOURCE_CHANGED,
    /** 원고가 삭제·보관되었거나 파기 대기 중이다. */
    SOURCE_PURGED,
    /** 저장소에서 원문을 읽지 못했다(일시 오류 포함). */
    SOURCE_UNREADABLE,
    /** report에 고정 출처 기록이 없다(무효화로 지워짐 등). */
    SOURCE_UNAVAILABLE
}
