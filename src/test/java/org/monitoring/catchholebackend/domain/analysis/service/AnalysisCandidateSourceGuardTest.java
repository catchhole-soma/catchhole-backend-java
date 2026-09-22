package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeSourcePurgeRequestRepository;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("후보 확정 시 원문 manifest 보호")
class AnalysisCandidateSourceGuardTest {
    private final EpisodeSourcePurgeRequestRepository requests = mock(EpisodeSourcePurgeRequestRepository.class);
    private final AnalysisCandidateSourceGuard guard = new AnalysisCandidateSourceGuard(requests);

    @ParameterizedTest
    @ValueSource(strings = {"episodeNo", "contentHash", "contentS3Key", "contentS3Version", "archived", "purging"})
    @DisplayName("수동 분석도 원문 교체·보관·파기 중이면 확정을 거절한다")
    void rejectsChangedOrUnavailableSource(String change) {
        Episode episode = episode();
        AnalysisJob job = AnalysisJob.create(null, null, episode, AnalysisJobType.SETTING_EXTRACTION);
        switch (change) {
            case "episodeNo" -> ReflectionTestUtils.setField(episode, change, 4);
            case "archived" -> ReflectionTestUtils.setField(episode, "status", EpisodeStatus.ARCHIVED);
            case "purging" -> when(requests.existsByEpisodeId(episode.getId())).thenReturn(true);
            default -> ReflectionTestUtils.setField(episode, change, "changed");
        }
        assertThatThrownBy(() -> guard.assertCurrent(job, episode)).isInstanceOfSatisfying(AppException.class,
                exception -> org.assertj.core.api.Assertions.assertThat(exception.getResultCode().getCode()).isEqualTo("ANALYSIS_RUN_STATE_CONFLICT"));
    }

    @Test
    @DisplayName("원문이 같은 신규 수동 후보는 확정할 수 있다")
    void allowsCurrentManualSource() {
        Episode episode = episode();
        AnalysisJob job = AnalysisJob.create(null, null, episode, AnalysisJobType.SETTING_EXTRACTION);
        assertThatCode(() -> guard.assertCurrent(job, episode)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("manifest가 없던 legacy Job도 보관·파기 여부를 확인하고 기존 확정을 유지한다")
    void preservesLegacyWithoutManifestButChecksAvailability() {
        Episode episode = episode();
        AnalysisJob legacy = AnalysisJob.create(null, null, null, AnalysisJobType.SETTING_EXTRACTION);
        assertThatCode(() -> guard.assertCurrent(legacy, episode)).doesNotThrowAnyException();
        when(requests.existsByEpisodeId(episode.getId())).thenReturn(true);
        assertThatThrownBy(() -> guard.assertCurrent(legacy, episode)).isInstanceOf(AppException.class);
    }

    private Episode episode() {
        Episode episode = Episode.create(null, null, 3, "3화", "source/v1", "v1", "hash-v1", 10);
        ReflectionTestUtils.setField(episode, "id", UUID.randomUUID());
        return episode;
    }
}
