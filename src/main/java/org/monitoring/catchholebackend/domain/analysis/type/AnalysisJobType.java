package org.monitoring.catchholebackend.domain.analysis.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum AnalysisJobType {
    SETTING_EXTRACTION("설정집 추출"),
    CHARACTER_FACT_COMPARISON("캐릭터 설정 재비교"),
    WORLD_SETTING_COMPARISON("세계관 설정 재비교"),
    EPISODE_VALIDATION("회차 검수");

    private final String toKorean;
}
