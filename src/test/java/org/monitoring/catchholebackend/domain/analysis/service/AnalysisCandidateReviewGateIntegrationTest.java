package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.*;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@Transactional
@DisplayName("다음 회차 후보 검토 분류와 종료의 DB 통합 검증")
class AnalysisCandidateReviewGateIntegrationTest {
    @Autowired AnalysisCandidateReviewGate gate;
    @Autowired MemberRepository members;
    @Autowired WorkRepository works;
    @Autowired UploadBatchRepository batches;
    @Autowired EpisodeRepository episodes;
    @Autowired AnalysisJobRepository jobs;
    @Autowired SettingCandidateRepository characters;
    @Autowired WorldSettingCandidateRepository worlds;
    @Autowired AnalysisPendingReferenceSource references;
    @Autowired AnalysisHumanRejectionPolicy rejections;
    @Autowired EntityManager em;
    private Work work;
    private UploadBatch batch;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString();
        Member member = members.save(Member.register(unique + "@example.com", "encoded-password",
                "010" + unique.replace("-", "").substring(0, 8), "작가"));
        work = works.save(Work.create(member, "후보 종료 작품", WorkGenre.FANTASY, "테스트"));
        batch = batches.save(UploadBatch.create(work, member, UploadType.SINGLE_EPISODE, UploadSourceType.FILE));
    }

    @Test
    @DisplayName("직접 검토와 자동 보류·실패를 구분하며 Job JSON을 엔티티로 읽지 않는다")
    void classifiesWithScalarRowsWithoutLoadingJobEntities() {
        var manualJob = job(1, AnalysisReviewMode.MANUAL);
        var autoJob = job(2, AnalysisReviewMode.AUTOMATIC);
        var manual = character(manualJob);
        var ambiguous = character(autoJob);
        var world = world(autoJob);
        var failed = character(autoJob);
        ReflectionTestUtils.setField(failed, "comparisonStatus", CharacterFactComparisonStatus.FAILED);
        em.flush(); em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var snapshot = gate.snapshot(work.getId(), 3);

        assertThat(snapshot.manualRequired()).extracting(AnalysisCandidateReviewGate.Entry::id).containsExactly(manual.getId());
        assertThat(snapshot.ambiguous()).extracting(AnalysisCandidateReviewGate.Entry::id).containsExactlyInAnyOrder(ambiguous.getId(), world.getId());
        assertThat(snapshot.recoveryRequired()).extracting(AnalysisCandidateReviewGate.Entry::id).containsExactly(failed.getId());
        assertThat(snapshot.ambiguousCharacterCount()).isEqualTo(1);
        assertThat(snapshot.ambiguousWorldCount()).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    @DisplayName("자동 후보의 작가 수정과 전체 반영 보류는 정상 애매함으로 종료하지 않는다")
    void protectsAuthorDraftsAndReviewReleaseHolds() {
        var auto = job(1, AnalysisReviewMode.AUTOMATIC);
        var edited = character(auto);
        edited.recordUserModification();
        var draft = world(auto);
        draft.updateDecisionDraft(WorldSettingOperation.ADD, WorldSettingCategory.RACE, "엘프", null, "수명", "천 년", null);
        var heldJob = job(2, AnalysisReviewMode.AUTOMATIC);
        ReflectionTestUtils.setField(heldJob, "automaticAppliedAt", null);
        ReflectionTestUtils.setField(heldJob, "reviewApplicationHeldAt", LocalDateTime.now());
        var held = character(heldJob);
        em.flush();

        var snapshot = gate.snapshot(work.getId(), 3);

        assertThat(snapshot.ambiguous()).isEmpty();
        assertThat(snapshot.manualRequired()).extracting(AnalysisCandidateReviewGate.Entry::id)
                .containsExactlyInAnyOrder(edited.getId(), draft.getId(), held.getId());
    }

    @Test
    @DisplayName("직접 검토 후보는 비교 대기나 실패 상태여도 다음 회차를 막는다")
    void manualPendingAndFailedRemainBlocking() {
        var origin = job(1, AnalysisReviewMode.MANUAL);
        var pending = character(origin);
        var failed = world(origin);
        ReflectionTestUtils.setField(pending, "comparisonStatus", CharacterFactComparisonStatus.PENDING);
        ReflectionTestUtils.setField(failed, "comparisonStatus", WorldSettingComparisonStatus.FAILED);
        em.flush();
        var snapshot = gate.snapshot(work.getId(), 2);
        assertThat(snapshot.manualRequired()).extracting(AnalysisCandidateReviewGate.Entry::id)
                .containsExactlyInAnyOrder(pending.getId(), failed.getId());
        assertThat(snapshot.recoveryRequired()).isEmpty();
        assertThat(snapshot.ambiguous()).isEmpty();
    }

    @Test
    @DisplayName("같은 회차·보관 원문·이전 시도는 경고와 종료 범위에서 제외한다")
    void limitsScopeToCurrentEarlierLatestEpisodes() {
        var oldJob = job(1, AnalysisReviewMode.AUTOMATIC);
        character(oldJob);
        var replacement = AnalysisJob.create(work, batch, oldJob.getEpisode(), AnalysisJobType.SETTING_EXTRACTION);
        replacement.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        replacement.initializeOrderedRun(UUID.randomUUID(), 1, 0, null,
                new org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal().emptyState());
        jobs.save(replacement);
        var archived = job(2, AnalysisReviewMode.AUTOMATIC);
        character(archived);
        ReflectionTestUtils.setField(archived.getEpisode(), "status", EpisodeStatus.ARCHIVED);
        var same = job(3, AnalysisReviewMode.AUTOMATIC);
        character(same);
        em.flush();

        var snapshot = gate.snapshot(work.getId(), 3);

        assertThat(snapshot.manualRequired()).isEmpty();
        assertThat(snapshot.ambiguous()).isEmpty();
        assertThat(snapshot.recoveryRequired()).isEmpty();
    }

    @Test
    @DisplayName("종료는 근거와 판단을 보존하고 기본 목록·집계·미확정 참고에서만 제외한다")
    void closesWithoutLosingEvidenceOrCreatingHumanRejection() {
        var origin = job(1, AnalysisReviewMode.AUTOMATIC);
        var character = character(origin);
        var world = world(origin);
        var charEvidence = character.getEvidenceSpans().deepCopy();
        var charDecision = character.getRawComparisonJson().deepCopy();
        var worldEvidence = world.getEvidenceSpans().deepCopy();
        var worldDecision = world.getRawComparisonJson().deepCopy();
        var originalJobStatus = origin.getJournalStatus();
        em.flush();
        UUID requestId = UUID.randomUUID();

        gate.closeAmbiguous(gate.snapshot(work.getId(), 2), requestId);
        em.flush(); em.clear();

        var storedCharacter = characters.findById(character.getId()).orElseThrow();
        var storedWorld = worlds.findById(world.getId()).orElseThrow();
        assertThat(storedCharacter.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CLOSED);
        assertThat(storedWorld.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CLOSED);
        assertThat(storedCharacter.getClosureRequestId()).isEqualTo(requestId);
        assertThat(storedWorld.getClosedAt()).isNotNull();
        assertThat(storedCharacter.getEvidenceSpans()).isEqualTo(charEvidence);
        assertThat(storedCharacter.getRawComparisonJson()).isEqualTo(charDecision);
        assertThat(storedCharacter.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.COMPLETED);
        assertThat(storedWorld.getEvidenceSpans()).isEqualTo(worldEvidence);
        assertThat(storedWorld.getRawComparisonJson()).isEqualTo(worldDecision);
        assertThat(storedWorld.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.COMPLETED);
        assertThat(jobs.findById(origin.getId()).orElseThrow().getJournalStatus()).isEqualTo(originalJobStatus);
        assertThat(characters.findReviewListCandidates(work.getId(), batch.getId(), null, EnumSet.allOf(SettingCandidateMatchStatus.class))).isEmpty();
        assertThat(worlds.findReviewListWithoutJobs(work.getId(), batch.getId(), null, null, null, null)).isEmpty();
        assertThat(characters.findReviewListCandidates(work.getId(), batch.getId(), SettingCandidateReviewStatus.CLOSED,
                EnumSet.allOf(SettingCandidateMatchStatus.class))).hasSize(1);
        assertThat(characters.countReviewSummary(work.getId(), batch.getId(), SettingCandidateReviewStatus.PENDING_REVIEW,
                SettingCandidateMatchStatus.AMBIGUOUS, EnumSet.of(CharacterFactComparisonStatus.FAILED)).getTotalCandidateCount()).isZero();
        assertThat(worlds.countReviewSummaryByBatchIds(work.getId(), List.of(batch.getId()), WorldSettingReviewStatus.PENDING_REVIEW,
                WorldSettingComparisonStatus.PENDING, WorldSettingComparisonStatus.PROCESSING, WorldSettingComparisonStatus.FAILED,
                AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED))
                .isEmpty();
        assertThat(rejections.capture(work)).isEmpty();
        var next = job(2, AnalysisReviewMode.AUTOMATIC);
        assertThat(references.capture(next)).isEmpty();
        assertThatThrownBy(storedCharacter::dismissByUser).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> storedWorld.dismiss("종료 후 제외", work.getMember())).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("경고 뒤 수정된 후보는 종료하지 않고 새 확인을 요구한다")
    void rejectsStaleSnapshotWithoutClosingAnyCandidate() {
        var candidate = character(job(1, AnalysisReviewMode.AUTOMATIC));
        em.flush();
        var stale = gate.snapshot(work.getId(), 2);
        candidate.recordUserModification();
        em.flush();

        assertThatThrownBy(() -> gate.closeAmbiguous(stale, UUID.randomUUID())).isInstanceOf(AppException.class);
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
        assertThat(candidate.getClosedAt()).isNull();
    }

    @Test
    @DisplayName("비교 완료라도 입력 초과나 기준 변경 보류는 정상 애매함으로 종료하지 않는다")
    void technicalHoldsRemainRecoveryEvenWithCompletedComparison() {
        var origin = job(1, AnalysisReviewMode.AUTOMATIC);
        var oversized = character(origin);
        oversized.recordAutomaticReviewHold(AutomaticReviewHoldReason.COMPARISON_INPUT_TOO_LARGE);
        var changed = world(origin);
        changed.recordAutomaticReviewHold(AutomaticReviewHoldReason.CURRENT_SETTING_CHANGED);
        em.flush();
        var snapshot = gate.snapshot(work.getId(), 2);
        assertThat(snapshot.ambiguous()).isEmpty();
        assertThat(snapshot.recoveryRequired()).extracting(AnalysisCandidateReviewGate.Entry::id)
                .containsExactlyInAnyOrder(oversized.getId(), changed.getId());
    }

    private AnalysisJob job(int number, AnalysisReviewMode mode) {
        Episode episode = episodes.save(Episode.create(work, null, number, number + "화", "gate/" + number,
                "v1", String.format("%064x", number), 100));
        AnalysisJob job = AnalysisJob.create(work, batch, episode, AnalysisJobType.SETTING_EXTRACTION);
        job.configureReviewMode(mode);
        if (mode == AnalysisReviewMode.AUTOMATIC) {
            job.initializeOrderedRun(UUID.randomUUID(), 1, 0, null,
                    new org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal().emptyState());
            ReflectionTestUtils.setField(job, "inputStateHash", "0".repeat(64));
            var journal = JsonNodeFactory.instance.objectNode().put("formatVersion", 1).put("outputStateHash", "0".repeat(64));
            journal.putArray("changes");
            ReflectionTestUtils.setField(job, "stateJournal", journal);
            ReflectionTestUtils.setField(job, "journalStatus", AnalysisJournalStatus.SEALED);
            ReflectionTestUtils.setField(job, "automaticAppliedAt", LocalDateTime.now());
        }
        ReflectionTestUtils.setField(job, "status", AnalysisJobStatus.SUCCEEDED);
        return jobs.save(job);
    }

    private SettingCandidate character(AnalysisJob job) {
        var candidate = SettingCandidate.create(work, job.getEpisode(), null, job, SettingEntityType.CHARACTER,
                "아리아", "age", "17", SettingValueType.STRING, JsonNodeFactory.instance.objectNode().put("value", "17"),
                JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("quote", "열일곱")),
                new BigDecimal("0.8"), JsonNodeFactory.instance.objectNode().put("raw", "original"));
        ReflectionTestUtils.setField(candidate, "comparisonStatus", CharacterFactComparisonStatus.COMPLETED);
        ReflectionTestUtils.setField(candidate, "suggestedOperation", CharacterFactOperation.REVIEW_REQUIRED);
        ReflectionTestUtils.setField(candidate, "rawComparisonJson", JsonNodeFactory.instance.objectNode().put("reason", "모호한 나이"));
        return characters.save(candidate);
    }

    private WorldSettingCandidate world(AnalysisJob job) {
        var candidate = WorldSettingCandidate.create(work, job.getEpisode(), job, WorldSettingCategory.RACE,
                "엘프", "수명", "천 년", JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("quote", "천 년을 산다")),
                new BigDecimal("0.8"), JsonNodeFactory.instance.objectNode().put("raw", "original"));
        ReflectionTestUtils.setField(candidate, "comparisonStatus", WorldSettingComparisonStatus.COMPLETED);
        ReflectionTestUtils.setField(candidate, "suggestedOperation", WorldSettingSuggestedOperation.REVIEW_REQUIRED);
        ReflectionTestUtils.setField(candidate, "comparisonReviewReason", WorldSettingComparisonReviewReason.GENERAL_UNCERTAINTY);
        ReflectionTestUtils.setField(candidate, "rawComparisonJson", JsonNodeFactory.instance.objectNode().put("reason", "수명이 모호함"));
        return worlds.save(candidate);
    }
}
