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

    boolean prepareInput(AnalysisJob job);

    void assertValidInput(AnalysisJob job);

    void validateResume(AnalysisJob job);

    void appendValidatedChanges(AnalysisJob job, String expectedInputStateHash, List<AnalysisStateChange> changes);

    void seal(AnalysisJob job);

    void invalidateFrom(AnalysisJob job, String reason);

    // 작품 잠금을 먼저 획득하고 후보·정식 설정을 잠그기 전에 호출한다.
    void invalidateRunsForWorkForUpdate(UUID workId, Integer sourceEpisodeNo, String reason);

    void purgeSourceEvidenceForWorkForUpdate(UUID workId, int sourceEpisodeNo);
}
