package org.monitoring.catchholebackend.domain.character.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum SettingEntityType {
    CHARACTER("캐릭터");

    private final String toKorean;
}
