package org.monitoring.catchholebackend.domain.worldsetting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.exception.WorldSettingErrorCode;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("설정집 세계관 비교 묶음의 회차 없는 출처 검증")
class WorldSettingBookComparisonGroupTest {
    @Test
    @DisplayName("회차 없는 설정집 후보도 주체 해소 검증에 도달한다")
    void bookCandidateWithoutEpisodeReachesSubjectValidation() {
        var service = mock(WorldSettingWorkerServiceImpl.class, CALLS_REAL_METHODS);
        var job = mock(AnalysisJob.class);
        when(job.getId()).thenReturn(UUID.randomUUID());
        when(job.getJobType()).thenReturn(AnalysisJobType.SETTING_EXTRACTION);
        var candidate = mock(WorldSettingCandidate.class);
        when(candidate.getAnalysisJob()).thenReturn(job);
        when(candidate.isPendingReview()).thenReturn(true);
        when(candidate.getComparisonStatus()).thenReturn(WorldSettingComparisonStatus.PENDING);
        when(candidate.getScopeName()).thenReturn("엘프");
        assertThat(candidate.getSourceEpisode()).isNull();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service,
                "validateBatchCandidateGroup", job, List.of(candidate)))
                .isInstanceOfSatisfying(AppException.class, error -> assertThat(error.getResultCode())
                        .isEqualTo(WorldSettingErrorCode.WORLD_SETTING_SUBJECT_RESOLUTION_REQUIRED));
    }
}
