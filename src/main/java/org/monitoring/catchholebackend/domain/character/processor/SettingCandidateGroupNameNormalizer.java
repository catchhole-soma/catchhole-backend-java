package org.monitoring.catchholebackend.domain.character.processor;

import java.util.Locale;

/** 설정 후보의 화면 표시 이름과 그룹 식별자를 같은 규칙으로 정규화한다. */
public final class SettingCandidateGroupNameNormalizer {

    private SettingCandidateGroupNameNormalizer() {
    }

    public static String toDisplayName(String entityName) {
        return entityName.trim().replaceAll("\\s+", " ");
    }

    public static String toGroupKey(String entityName) {
        return toDisplayName(entityName).toLowerCase(Locale.ROOT);
    }
}
