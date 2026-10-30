package org.monitoring.catchholebackend.domain.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;

public interface AnalysisRunStateService {
    void initializeRun(List<AnalysisJob> jobs);

    JsonNode getInputState(AnalysisJob job);

    JsonNode getProjectedState(AnalysisJob job);

    /** Job 잠금을 보유한 한 번의 처리에서만 재사용한다. 반환 상태는 읽기 전용이다. */
    PreparedState prepareState(AnalysisJob job);

    interface PreparedState {
        JsonNode projectedState();

        void append(List<AnalysisStateChange> changes);
    }

    boolean prepareInput(AnalysisJob job);

    void assertValidInput(AnalysisJob job);

    void validateResume(AnalysisJob job);

    void appendValidatedChanges(AnalysisJob job, String expectedInputStateHash, List<AnalysisStateChange> changes);

    void seal(AnalysisJob job);

    void invalidateFrom(AnalysisJob job, String reason);

    // 작품 잠금 아래 의미 있는 정식 설정 변경 전에 호출한다. 완료 기록은 무효화하지 않는다.
    void assertSettingMutationAllowed(UUID workId);

    void confirmedSettingsChanged(UUID workId);

    // 작품 잠금을 먼저 획득하고 후보·정식 설정을 잠그기 전에 호출한다.
    void invalidateRunsForEpisodeChangeForUpdate(UUID workId, UUID episodeId, int firstAffectedEpisodeNo, String reason);

    void invalidateRunsForSettingBookChangeForUpdate(UUID workId, UUID settingBookId, String reason);

    void purgeSourceEvidenceForWorkForUpdate(UUID workId, UUID episodeId, int sourceEpisodeNo);
    void purgeSourceEvidenceForWorkForUpdate(UUID workId, UUID episodeId, int sourceEpisodeNo, String retainedContentKey);
}
