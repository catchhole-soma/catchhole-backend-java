package org.monitoring.catchholebackend.domain.analysis.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum AnalysisJobType {
    SETTING_EXTRACTION("설정집 추출"),
    EPISODE_VALIDATION("회차 검수");

    private final String toKorean;
}
