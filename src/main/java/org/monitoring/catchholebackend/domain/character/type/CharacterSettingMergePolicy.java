package org.monitoring.catchholebackend.domain.character.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Resolver가 결정한 factKey entry에 새 설정값이 들어왔을 때 적용할 병합 방식입니다.
 * 현재 confirm snapshot은 REPLACE와 UPSERT_BY_NAME만 지원합니다.
 */
@RequiredArgsConstructor
@Getter
public enum CharacterSettingMergePolicy {

    /** 기존 factKey entry를 새 valueJson 전체로 교체하며 object 내부를 deep merge하지 않습니다. */
    REPLACE("값 교체"),

    /** 동적 factKey 원소를 추가하거나 해당 entry 전체를 교체합니다. valueJson.name은 표시 데이터입니다. */
    UPSERT_BY_NAME("이름 기준 추가·갱신"),

    /** 장비 위치 같은 slot을 식별자로 사용해 기존 원소를 갱신하거나 새 원소를 추가합니다. */
    UPSERT_BY_SLOT("슬롯 기준 추가·갱신"),

    /** 기존 원소와의 일치 여부를 확인하지 않고 새 원소를 목록 끝에 추가합니다. */
    APPEND("목록에 추가"),

    /** 직접 입력값을 병합하지 않고 다른 설정값을 바탕으로 계산한 결과를 사용합니다. */
    DERIVED("파생값 계산");

    private final String toKorean;
}
