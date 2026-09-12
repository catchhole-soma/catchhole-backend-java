package org.monitoring.catchholebackend.domain.episode.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeUploadPolicyRepository;

@DisplayName("선행 분석 없는 다회차 업로드 정책")
class EpisodeUploadPolicyTest {

    private final UUID workId = UUID.randomUUID();
    private final EpisodeUploadPolicyRepository repository = mock(EpisodeUploadPolicyRepository.class);
    private final EpisodeUploadPolicy policy = new EpisodeUploadPolicy(repository);

    @ParameterizedTest
    @ValueSource(longs = {0L, 9L, 10L})
    @DisplayName("완료 이력과 관계없이 다회차를 허용하고 이력·검토 안내·분량 제한은 보존한다")
    void policyHasNoPrerequisiteAndPreservesUploadInformation(long completedCount) {
        when(repository.countCompletedSingleEpisodes(workId)).thenReturn(completedCount);
        when(repository.countPendingCharacterCandidates(workId)).thenReturn(3L);
        when(repository.countPendingWorldSettingCandidates(workId)).thenReturn(2L);

        var response = policy.getPolicy(workId);

        assertThat(response.multiEpisodeUploadEnabled()).isTrue();
        assertThat(response.requiredSingleEpisodeCount()).isZero();
        assertThat(response.completedSingleEpisodeCount()).isEqualTo(completedCount);
        assertThat(response.pendingCharacterCandidateCount()).isEqualTo(3);
        assertThat(response.pendingWorldSettingCandidateCount()).isEqualTo(2);
        assertThat(response.maxUploadCharacters()).isEqualTo(250_000);
    }
}
