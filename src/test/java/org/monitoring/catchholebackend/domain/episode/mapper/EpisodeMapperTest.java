package org.monitoring.catchholebackend.domain.episode.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeAnalysisStatus;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeSummaryResponse;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;

@DisplayName("회차 Mapper 단위 테스트")
class EpisodeMapperTest {

    private final EpisodeMapper episodeMapper = new EpisodeMapper();

    @Test
    @DisplayName("명시적인 미처리 항목 수를 완료 회차 응답에 반영한다")
    void toSummaryResponseMapsExplicitUnresolvedFindingCount() {
        EpisodeSummaryResponse response = completedEpisodeResponse(
                "{\"unresolvedFindingCount\":3}"
        );

        assertThat(response.unresolvedFindingCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("findings 배열만 있는 이전 요약은 배열 크기를 미처리 항목 수로 사용한다")
    void toSummaryResponseMapsLegacyFindingsArraySize() {
        EpisodeSummaryResponse response = completedEpisodeResponse(
                "{\"findings\":[{\"id\":1},{\"id\":2}]}"
        );

        assertThat(response.unresolvedFindingCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "{}",
            "{\"candidateCount\":5}",
            "{\"unresolvedFindingCount\":-1}",
            "{invalid-json"
    })
    @DisplayName("건수를 확인할 수 없는 완료 요약은 문제 없음으로 단정하지 않는다")
    void toSummaryResponseReturnsNullForUnknownFindingCount(String summaryJson) {
        EpisodeSummaryResponse response = completedEpisodeResponse(summaryJson);

        assertThat(response.unresolvedFindingCount()).isNull();
    }

    private EpisodeSummaryResponse completedEpisodeResponse(String summaryJson) {
        AnalysisJob job = completedJob(summaryJson);
        return episodeMapper.toSummaryResponse(job.getEpisode(), null, job);
    }

    @ParameterizedTest
    @CsvSource({"INVALIDATED,true,REANALYSIS_REQUIRED", "INCOMPLETE,false,FAILED",
            "SEALED,false,FAILED", "SEALED,true,COMPLETED"})
    @DisplayName("원고 목록도 순차 분석의 무효화·저장 미완료를 완료로 표시하지 않는다")
    void orderedEpisodeSummaryUsesJournalState(AnalysisJournalStatus journal, boolean applied,
            EpisodeAnalysisStatus expected) {
        AnalysisJob job = completedJob("{\"unresolvedFindingCount\":2}");
        ReflectionTestUtils.setField(job, "analysisMode", AnalysisMode.ORDERED_PROVISIONAL);
        ReflectionTestUtils.setField(job, "reviewMode", AnalysisReviewMode.AUTOMATIC);
        ReflectionTestUtils.setField(job, "journalStatus", journal);
        if (applied) ReflectionTestUtils.setField(job, "automaticAppliedAt", LocalDateTime.now());

        EpisodeSummaryResponse response = episodeMapper.toSummaryResponse(job.getEpisode(), null, job);
        assertThat(response.analysisStatus()).isEqualTo(expected);
        if (expected != EpisodeAnalysisStatus.COMPLETED) assertThat(response.unresolvedFindingCount()).isNull();
        assertThat(job.getStatus()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("가벼운 목록 projection의 완료 판정은 Job의 모든 상태·반영 방식과 같다")
    void projectionPreservesOrderedCompletionForEveryStatusAndApplicationLatch() {
        AnalysisJob job = completedJob("{}");
        for (AnalysisMode mode : AnalysisMode.values()) {
            for (AnalysisReviewMode reviewMode : AnalysisReviewMode.values()) {
                for (AnalysisJobStatus status : AnalysisJobStatus.values()) {
                    for (AnalysisJournalStatus journal : AnalysisJournalStatus.values()) {
                        for (String application : new String[]{"NONE", "APPLIED", "HELD"}) {
                            ReflectionTestUtils.setField(job, "analysisMode", mode);
                            ReflectionTestUtils.setField(job, "reviewMode", reviewMode);
                            ReflectionTestUtils.setField(job, "status", status);
                            ReflectionTestUtils.setField(job, "journalStatus", journal);
                            ReflectionTestUtils.setField(job, "automaticAppliedAt",
                                    application.equals("APPLIED") ? LocalDateTime.now() : null);
                            ReflectionTestUtils.setField(job, "reviewApplicationHeldAt",
                                    application.equals("HELD") ? LocalDateTime.now() : null);
                            var summary = episodeMapper.toAnalysisSummary(job);
                            assertThat(summary.isCompletedOrderedAnalysis())
                                    .as("%s / %s / %s / %s / %s", mode, reviewMode, status, journal, application)
                                    .isEqualTo(job.isCompletedOrderedAnalysis());
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("회차 전체 확정을 보류한 자동 분석도 봉인 완료를 유지한다")
    void heldReviewApplicationRemainsCompleted() {
        AnalysisJob job = completedJob("{\"unresolvedFindingCount\":2}");
        ReflectionTestUtils.setField(job, "analysisMode", AnalysisMode.ORDERED_PROVISIONAL);
        ReflectionTestUtils.setField(job, "reviewMode", AnalysisReviewMode.AUTOMATIC);
        ReflectionTestUtils.setField(job, "journalStatus", AnalysisJournalStatus.SEALED);
        ReflectionTestUtils.setField(job, "reviewApplicationHeldAt", LocalDateTime.now());
        EpisodeSummaryResponse response = episodeMapper.toSummaryResponseWithAnalysisSummary(
                job.getEpisode(), null, episodeMapper.toAnalysisSummary(job), null);
        assertThat(response.analysisStatus()).isEqualTo(EpisodeAnalysisStatus.COMPLETED);
        assertThat(response.unresolvedFindingCount()).isEqualTo(2);
    }

    private AnalysisJob completedJob(String summaryJson) {
        Member member = Member.register(
                "writer@example.com",
                "encoded-password",
                "01012345678",
                "작가"
        );
        Work work = Work.create(member, "내 작품", WorkGenre.FANTASY, "내 설명");
        Episode episode = Episode.create(
                work,
                null,
                1,
                "첫 회차",
                "episodes/1.txt",
                null,
                "hash-1",
                100
        );
        episode.markAnalyzed();
        AnalysisJob analysisJob = AnalysisJob.create(
                work,
                null,
                episode,
                AnalysisJobType.SETTING_EXTRACTION
        );
        analysisJob.succeed(summaryJson, 0, 0);

        return analysisJob;
    }
}
