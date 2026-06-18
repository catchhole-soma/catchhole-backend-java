package org.monitoring.catchholebackend.domain.character.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum SettingValueType {
    STRING("문자열"),
    NUMBER("숫자"),
    BOOLEAN("참/거짓"),
    JSON("JSON"),
    UNKNOWN("알 수 없음");

    private final String toKorean;
}
