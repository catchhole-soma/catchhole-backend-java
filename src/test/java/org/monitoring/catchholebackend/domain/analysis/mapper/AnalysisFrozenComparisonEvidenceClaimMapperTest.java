package org.monitoring.catchholebackend.domain.analysis.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterFactEvidenceMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterFactSourceResolver;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("claim의 작은 비교근거 계약은 ORDERED에만 명시되어 구 Worker의 silent downgrade를 막는다")
class AnalysisFrozenComparisonEvidenceClaimMapperTest {
    private final CharacterFactEvidenceMapper evidence = new CharacterFactEvidenceMapper(new CharacterFactSourceResolver());
    private final AnalysisJobWorkerMapper mapper = new AnalysisJobWorkerMapper(new CharacterSnapshotAccessor(),new AnalysisRunContextMapper(evidence),evidence);
    private final tools.jackson.databind.ObjectMapper wire = new tools.jackson.databind.ObjectMapper();

    @Test @DisplayName("ORDERED claim은 비교근거 version1을 명시하고 공유 context 필드는 늘리지 않는다")
    void declaresOrderedComparisonEvidenceContract() {
        AnalysisJob job = job();
        ReflectionTestUtils.setField(job,"analysisMode",AnalysisMode.ORDERED_PROVISIONAL);
        ReflectionTestUtils.setField(job,"analysisRunId",UUID.randomUUID());
        ReflectionTestUtils.setField(job,"runGeneration",1L);
        ReflectionTestUtils.setField(job,"inputStateHash","a".repeat(64));
        ReflectionTestUtils.setField(job,"sourceContentHash","b".repeat(64));
        var result = wire.valueToTree(mapper.toResponse(job,job.getEpisode(),List.of(),List.of(),List.of(),new AnalysisStateJournal().emptyState()));
        assertThat(result.path("comparisonEvidenceContract").path("formatVersion").asInt()).isEqualTo(1);
        assertThat(result.path("analysisContext").has("advisoryCurrentContext")).isFalse();
    }

    @Test @DisplayName("기존 CONFIRMED_ONLY claim에는 새 비교근거 계약을 직렬화하지 않는다")
    void confirmedOnlyOmitsContract() {
        AnalysisJob job = job();
        var result = wire.valueToTree(mapper.toResponse(job,job.getEpisode(),List.of(),List.of(),List.of()));
        assertThat(result.has("comparisonEvidenceContract")).isFalse();
    }

    private AnalysisJob job() {
        Work work=Work.create(Member.register("wire@example.invalid","password","01011111111","작가"),"wire",WorkGenre.FANTASY,"");
        ReflectionTestUtils.setField(work,"id",UUID.randomUUID());
        Episode episode=Episode.create(work,null,1,"1화","immutable.txt",null,"b".repeat(64),10);
        ReflectionTestUtils.setField(episode,"id",UUID.randomUUID());
        AnalysisJob job=AnalysisJob.create(work,null,episode,AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(job,"id",UUID.randomUUID());
        return job;
    }
}
