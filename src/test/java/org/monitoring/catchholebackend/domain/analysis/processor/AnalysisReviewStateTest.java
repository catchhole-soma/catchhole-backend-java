package org.monitoring.catchholebackend.domain.analysis.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.type.*;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("후보 검토의 순수 Job 상태 정책")
class AnalysisReviewStateTest {
    @Test
    @DisplayName("모든 모드·상태·journal·자동 적용·원문 유효성 조합은 기존 Job 판정과 같다")
    void reviewStateMatchesExistingPredicates() {
        for (var mode : AnalysisMode.values()) for (var review : AnalysisReviewMode.values())
        for (var status : AnalysisJobStatus.values()) for (var journal : AnalysisJournalStatus.values())
        for (boolean applied : new boolean[] {false, true}) for (boolean current : new boolean[] {false, true}) {
            var episode = Episode.create(null, null, 3, "3화", "synthetic/3", null, "a".repeat(64), 100);
            var job = AnalysisJob.create(null, null, episode, AnalysisJobType.SETTING_EXTRACTION);
            ReflectionTestUtils.setField(job, "analysisMode", mode); ReflectionTestUtils.setField(job, "reviewMode", review);
            ReflectionTestUtils.setField(job, "status", status); ReflectionTestUtils.setField(job, "journalStatus", journal);
            ReflectionTestUtils.setField(job, "automaticAppliedAt", applied ? LocalDateTime.of(2026, 10, 25, 0, 0) : null);
            if (!current) ReflectionTestUtils.setField(episode, "status", EpisodeStatus.ARCHIVED);
            var state = AnalysisReviewState.from(job);
            assertThat(state.ordered()).isEqualTo(job.isOrderedProvisional());
            assertThat(state.automatic()).isEqualTo(job.isAutomaticReview());
            assertThat(state.completedOrdered()).isEqualTo(job.isCompletedOrderedAnalysis());
            assertThat(state.automaticPending()).isEqualTo(job.isAutomaticApplicationPending());
            assertThat(state.currentSourceVersion()).isEqualTo(job.hasCurrentSourceVersion());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "CONFIRMED_ONLY,MANUAL,SUCCEEDED,,false,true,COMPLETED,REVIEW_REQUIRED,true,false,false",
            "CONFIRMED_ONLY,MANUAL,RUNNING,,false,true,COMPLETED,REVIEW_REQUIRED,false,false,false",
            "CONFIRMED_ONLY,MANUAL,SUCCEEDED,,false,false,COMPLETED,REVIEW_REQUIRED,false,false,false",
            "ORDERED_PROVISIONAL,AUTOMATIC,SUCCEEDED,SEALED,true,true,COMPLETED,ADD,true,true,false",
            "ORDERED_PROVISIONAL,AUTOMATIC,SUCCEEDED,SEALED,false,true,COMPLETED,ADD,false,false,false",
            "ORDERED_PROVISIONAL,AUTOMATIC,SUCCEEDED,INCOMPLETE,true,true,COMPLETED,ADD,false,true,false",
            "ORDERED_PROVISIONAL,AUTOMATIC,SUCCEEDED,INVALIDATED,true,true,COMPLETED,ADD,false,true,false",
            "ORDERED_PROVISIONAL,AUTOMATIC,SUCCEEDED,SEALED,true,false,COMPLETED,ADD,false,true,false",
            "ORDERED_PROVISIONAL,AUTOMATIC,PENDING,PENDING,false,true,PENDING,ADD,false,false,true",
            "ORDERED_PROVISIONAL,AUTOMATIC,RUNNING,PENDING,false,true,PROCESSING,ADD,false,false,true",
            "ORDERED_PROVISIONAL,AUTOMATIC,FAILED,INCOMPLETE,false,true,FAILED,ADD,false,false,false",
            "ORDERED_PROVISIONAL,AUTOMATIC,CANCELED,INVALIDATED,false,true,COMPLETED,ADD,false,false,false"
    })
    @DisplayName("수동 완료·자동 보류·진행·실패의 검토 가능성과 적용 대기를 보존한다")
    void preservesCandidateReviewStateMatrix(AnalysisMode mode, AnalysisReviewMode review, AnalysisJobStatus status,
            AnalysisJournalStatus journal, boolean applied, boolean current, CharacterFactComparisonStatus comparison,
            CharacterFactOperation operation, boolean characterManual, boolean worldManual, boolean pending) {
        var job = AnalysisJob.create(null, null, null, AnalysisJobType.SETTING_EXTRACTION);
        var character = SettingCandidate.create(null, null, null, job, SettingEntityType.CHARACTER, "아리아", "age", "17",
                SettingValueType.STRING, null, null, BigDecimal.ONE, null);
        ReflectionTestUtils.setField(character, "comparisonStatus", comparison);
        ReflectionTestUtils.setField(character, "suggestedOperation", operation);
        var episode = Episode.create(null, null, 3, "3화", "synthetic/3", null, "a".repeat(64), 100);
        var world = WorldSettingCandidate.create(nullWork(), episode, job, WorldSettingCategory.LOCATION, "북부", "특징", "합성 값",
                JsonNodeFactory.instance.arrayNode(), BigDecimal.ONE, null);
        ReflectionTestUtils.setField(world, "comparisonStatus", comparison == CharacterFactComparisonStatus.COMPLETED ? WorldSettingComparisonStatus.COMPLETED
                : comparison == CharacterFactComparisonStatus.PENDING ? WorldSettingComparisonStatus.PENDING
                : comparison == CharacterFactComparisonStatus.PROCESSING ? WorldSettingComparisonStatus.PROCESSING : WorldSettingComparisonStatus.FAILED);
        var state = new AnalysisReviewState(mode, review, status, journal, applied ? LocalDateTime.now() : null, current);
        assertThat(character.isManualReviewAvailable(state)).isEqualTo(characterManual);
        assertThat(world.isManualReviewAvailable(state)).isEqualTo(worldManual);
        assertThat(character.isAutomaticApplicationPending(state)).isEqualTo(pending);
        assertThat(world.isAutomaticApplicationPending(state)).isEqualTo(pending);
    }

    @ParameterizedTest
    @CsvSource({"COMPARISON_VALIDATION_FAILED,true", "AI_TOKEN_QUOTA_EXHAUSTED,false"})
    @DisplayName("준비 단계의 비교 실패만 수동으로 보류할 수 있고 토큰 중단은 제외한다")
    void preservesDeferredPreparationFailure(AnalysisFailureCode code, boolean manual) {
        var job = AnalysisJob.create(null, null, null, AnalysisJobType.SETTING_EXTRACTION);
        var candidate = SettingCandidate.create(null, null, null, job, SettingEntityType.CHARACTER, "아리아", "age", "17",
                SettingValueType.STRING, null, null, BigDecimal.ONE, null);
        ReflectionTestUtils.setField(candidate, "comparisonStatus", CharacterFactComparisonStatus.FAILED);
        ReflectionTestUtils.setField(candidate, "comparisonFailureCode", code);
        ReflectionTestUtils.setField(candidate, "preparationFailureStage", CandidatePreparationFailureStage.SUBJECT_RESOLUTION);
        ReflectionTestUtils.setField(candidate, "matchStatus", SettingCandidateMatchStatus.AMBIGUOUS);
        var state = new AnalysisReviewState(AnalysisMode.ORDERED_PROVISIONAL, AnalysisReviewMode.AUTOMATIC,
                AnalysisJobStatus.SUCCEEDED, AnalysisJournalStatus.SEALED, LocalDateTime.now(), true);
        assertThat(candidate.isManualReviewAvailable(state)).isEqualTo(manual);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(CharacterFactConfirmApplicationMode.class)
    @DisplayName("완료된 수동 분석은 저장된 검토 방식을 유지한다")
    void preservesStoredManualReviewDecision(CharacterFactConfirmApplicationMode mode) {
        var job = AnalysisJob.create(null, null, null, AnalysisJobType.SETTING_EXTRACTION);
        var candidate = SettingCandidate.create(null, null, null, job, SettingEntityType.CHARACTER, "아리아", "age", "17",
                SettingValueType.STRING, null, null, BigDecimal.ONE, null);
        ReflectionTestUtils.setField(candidate, "reviewedApplicationMode", mode);
        ReflectionTestUtils.setField(candidate, "comparisonStatus", CharacterFactComparisonStatus.COMPLETED);
        ReflectionTestUtils.setField(candidate, "suggestedOperation", CharacterFactOperation.ADD);
        var state = new AnalysisReviewState(AnalysisMode.CONFIRMED_ONLY, AnalysisReviewMode.MANUAL,
                AnalysisJobStatus.SUCCEEDED, null, null, true);
        assertThat(candidate.isManualReviewAvailable(state)).isTrue();
        ReflectionTestUtils.setField(candidate, "reviewStatus", SettingCandidateReviewStatus.DISMISSED);
        assertThat(candidate.isManualReviewAvailable(state)).isFalse();
    }

    private org.monitoring.catchholebackend.domain.work.entity.Work nullWork() {
        return org.monitoring.catchholebackend.domain.work.entity.Work.create(null, "합성 작품",
                org.monitoring.catchholebackend.domain.work.type.WorkGenre.FANTASY, null);
    }
}
