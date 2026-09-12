package org.monitoring.catchholebackend.domain.worldsetting.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.worldsetting.dto.WorldSettingComparisonDiagnostic;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.springframework.test.util.ReflectionTestUtils;

class WorldSettingMapperDiagnosticsTest {
    @Test
    void candidateResponsePreservesDiagnosticStagesAndAcceptsLegacyMetadata() throws Exception {
        ObjectMapper json = new ObjectMapper();
        WorldSettingCandidate candidate = WorldSettingCandidate.create(mock(Work.class), mock(Episode.class),
                mock(AnalysisJob.class), WorldSettingCategory.LOCATION, "미궁", "광원", "수정",
                json.createArrayNode(), new BigDecimal("0.95"), json.createObjectNode());
        ReflectionTestUtils.setField(candidate, "comparisonStatus", WorldSettingComparisonStatus.COMPLETED);
        candidate.recordComparisonDiagnostics(json.readTree("""
                [{"attempt":1,"rule":"TEST_RULE","candidateRefs":["C1"],"selectedProperties":[],
                  "stage":"DECISION_VALIDATION","phase":"RECOVERY"},
                 {"attempt":2,"rule":"TEST_RULE","candidateRefs":[],"selectedProperties":[]},
                 {"attempt":3,"rule":"TEST_RULE","candidateRefs":[],"selectedProperties":[],
                  "stage":"FUTURE_STAGE","phase":null}]
                """));
        var diagnostics = new WorldSettingMapper().toCandidateResponse(candidate).comparisonDiagnostics();
        assertThat(diagnostics).hasSize(3);
        assertThat(diagnostics.getFirst().stage()).isEqualTo(WorldSettingComparisonDiagnostic.Stage.DECISION_VALIDATION);
        assertThat(diagnostics.getFirst().phase()).isEqualTo(WorldSettingComparisonDiagnostic.Phase.RECOVERY);
        assertThat(diagnostics.get(1).stage()).isNull();
        assertThat(diagnostics.get(1).phase()).isNull();
        assertThat(diagnostics.get(2).stage()).isNull();
    }
}
