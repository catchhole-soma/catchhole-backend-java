package org.monitoring.catchholebackend.domain.character.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 같은 canonical schema key에 새 설정값이 들어왔을 때 적용할 병합 방식입니다.
 * 실제 snapshot 병합은 NVM-233에서 구현하며, 현재 Registry는 적용할 정책만 저장합니다.
 */
@RequiredArgsConstructor
@Getter
public enum CharacterSettingMergePolicy {

    /** 기존 값을 새 값으로 완전히 교체합니다. */
    REPLACE("값 교체"),

    /** 목록 원소의 name을 식별자로 사용해 기존 원소를 갱신하거나 새 원소를 추가합니다. */
    UPSERT_BY_NAME("이름 기준 추가·갱신"),

    /** 장비 위치 같은 slot을 식별자로 사용해 기존 원소를 갱신하거나 새 원소를 추가합니다. */
    UPSERT_BY_SLOT("슬롯 기준 추가·갱신"),

    /** 기존 원소와의 일치 여부를 확인하지 않고 새 원소를 목록 끝에 추가합니다. */
    APPEND("목록에 추가"),

    /** 직접 입력값을 병합하지 않고 다른 설정값을 바탕으로 계산한 결과를 사용합니다. */
    DERIVED("파생값 계산");

    private final String toKorean;
}
