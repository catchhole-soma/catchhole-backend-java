package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.StoredTextObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * #387 자동 누적 생성의 미래 출처 scalar 조회. 같은 시나리오를 H2와 PostgreSQL에서 실행한다.
 * 회차 이동을 흉내 내려고 추출 Job을 만든 뒤 회차 번호만 바꾼다(Job의 source_episode_no는 그대로다).
 */
@Transactional
abstract class AnalysisFutureSourceCheckScenarios {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Autowired EntityManager em;
    @Autowired AnalysisJobRepository jobs;
    @Autowired AnalysisRunStateService states;
    private final java.util.Map<UUID, Integer> sourceOrders = new java.util.HashMap<>();

    @Test
    @DisplayName("세 조회는 ACTIVE 인물의 현재 출처 Fact·확정 발견과 확정 세계관 후보의 추출 Job 고정 회차만 보고 설정집 NULL은 건너뛴다")
    void queriesReturnLatestFrozenJobEpisodeOfCountedRowsOnly() {
        Work work = work();
        WorkCharacter active = character(work, "현재 인물");
        WorkCharacter archived = character(work, "보관 인물");
        currentFact(active, extraction(work, 3));
        currentFact(active, settingBookExtraction(work));
        historyFact(active, extraction(work, 9));
        currentFact(archived, extraction(work, 8));
        confirmedDiscovery(active, extraction(work, 4));
        confirmedDiscovery(archived, extraction(work, 10));
        discovery(active, extraction(work, 11));
        WorldSetting world = world(WorldSetting.create(work, WorldSettingCategory.RACE, "엘프", "서식지", "북부"));
        worldCandidate(world, extraction(work, 5), true, false);
        worldCandidate(world, extraction(work, 12), true, true);
        worldCandidate(world, extraction(work, 13), false, false);
        worldCandidate(null, extraction(work, 14), true, false);
        Work other = work();
        currentFact(character(other, "다른 작품 인물"), extraction(other, 20));
        confirmedDiscovery(character(other, "다른 작품 발견"), extraction(other, 21));
        worldCandidate(world(WorldSetting.create(other, WorldSettingCategory.RACE, "드워프", "서식지", "동굴")),
                extraction(other, 22), true, false);
        Work bookOnly = work();
        currentFact(character(bookOnly, "설정집 인물"), settingBookExtraction(bookOnly));
        em.flush();

        assertThat(jobs.findLatestCurrentCharacterSourceJobEpisodeNo(work.getId())).isEqualTo(3);
        assertThat(jobs.findLatestConfirmedDiscoveryJobEpisodeNo(work.getId())).isEqualTo(4);
        assertThat(jobs.findLatestConfirmedWorldSourceJobEpisodeNo(work.getId())).isEqualTo(5);
        assertThat(jobs.findLatestCurrentCharacterSourceJobEpisodeNo(bookOnly.getId())).isNull();
        assertThat(jobs.findLatestConfirmedDiscoveryJobEpisodeNo(bookOnly.getId())).isNull();
        assertThat(jobs.findLatestConfirmedWorldSourceJobEpisodeNo(bookOnly.getId())).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"currentCharacterSource", "confirmedDiscovery", "confirmedWorldSource"})
    @DisplayName("회차 이동으로 지금 번호는 앞이지만 추출 Job 고정 번호가 시작 회차 이후면 자동 생성도 수동 S0 순회와 같은 오류로 거절한다")
    void renumberedEpisodeWithFutureFrozenJobNumberIsRejected(String kind) {
        Work work = work();
        AnalysisJob moved = extraction(work, 7);
        Episode start = episode(work, 5);
        Episode later = episode(work, 8);
        // 7화를 2화로 옮긴다. 지금 번호(2)만 보는 기존 조회는 5화 시작을 막지 못한다.
        moved.getEpisode().updateMetadata(2, moved.getEpisode().getTitle(), moved.getEpisode().getCharCount());
        WorkCharacter owner = character(work, "이동 회차 인물");
        switch (kind) {
            case "currentCharacterSource" -> currentFact(owner, moved);
            case "confirmedDiscovery" -> confirmedDiscovery(owner, moved);
            default -> worldCandidate(world(WorldSetting.create(work, WorldSettingCategory.RACE, "엘프", "서식지", "북부")),
                    moved, true, false);
        }
        em.flush();
        assertThat(moved.getSourceEpisodeNo()).isEqualTo(7);
        assertThat(moved.getEpisode().getEpisodeNo()).isEqualTo(2);

        AnalysisJob automatic = AnalysisJob.create(work, null, start, AnalysisJobType.SETTING_EXTRACTION);
        automatic.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        AnalysisJob manual = AnalysisJob.create(work, null, start, AnalysisJobType.SETTING_EXTRACTION);
        manual.configureReviewMode(AnalysisReviewMode.MANUAL);
        for (AnalysisJob job : List.of(automatic, manual)) {
            assertThatThrownBy(() -> states.initializeRun(List.of(job)))
                    .isInstanceOfSatisfying(AppException.class, error -> {
                        assertThat(error.getResultCode()).isEqualTo(AnalysisJobErrorCode.ANALYSIS_FUTURE_HISTORY_CONFLICT);
                        assertThat(error.getErrorContext()).isEmpty();
                    });
            assertThat(job.getAnalysisRunId()).isNull();
        }

        AnalysisJob afterFrozen = AnalysisJob.create(work, null, later, AnalysisJobType.SETTING_EXTRACTION);
        afterFrozen.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        states.initializeRun(List.of(afterFrozen));
        em.flush();
        assertThat(afterFrozen.getRunBaseState()).isEqualTo(JSON.objectNode().put("automaticInputCapturedAtClaim", true));
    }

    private WorldSetting world(WorldSetting setting) {
        em.persist(setting);
        return setting;
    }

    private Work work() {
        Member member = Member.register("future-" + UUID.randomUUID() + "@example.invalid", "test-only", null, "합성 작가");
        em.persist(member);
        Work work = Work.create(member, "합성 작품", WorkGenre.FANTASY, "");
        em.persist(work);
        return work;
    }

    private Episode episode(Work work, int episodeNo) {
        Episode episode = Episode.create(work, null, episodeNo, "합성 회차", "synthetic/" + UUID.randomUUID(), "v1", "a".repeat(64), 8);
        em.persist(episode);
        return episode;
    }

    private AnalysisJob extraction(Work work, int episodeNo) {
        AnalysisJob job = AnalysisJob.create(work, null, episode(work, episodeNo), AnalysisJobType.SETTING_EXTRACTION);
        em.persist(job);
        return job;
    }

    private AnalysisJob settingBookExtraction(Work work) {
        UploadBatch batch = UploadBatch.create(work, work.getMember(), UploadType.SETTING_BOOK, UploadSourceType.FILE);
        em.persist(batch);
        UploadFile book = UploadFile.create(batch, UploadFileRole.SETTING_BOOK, "합성 설정집.txt", "text/plain", "synthetic/book", 4);
        em.persist(book);
        SettingBookRevision revision = SettingBookRevision.create(book, 1,
                new StoredTextObject("synthetic/revision/" + UUID.randomUUID(), "v1", "b".repeat(64), 4));
        em.persist(revision);
        book.linkCurrentRevision(revision);
        AnalysisJob job = AnalysisJob.createSettingBook(work, book, revision);
        // 자동 반영 설정집 Job은 PostgreSQL CHECK상 누적 실행이어야 한다.
        job.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, JSON.objectNode().put("automaticInputCapturedAtClaim", true));
        em.persist(job);
        return job;
    }

    private WorkCharacter character(Work work, String name) {
        WorkCharacter character = WorkCharacter.create(work, name, null, null, 10, null, null, null, null, null, null);
        if (name.startsWith("보관")) character.archive();
        em.persist(character);
        return character;
    }

    private CharacterFact fact(WorkCharacter owner, AnalysisJob job) {
        CharacterFact fact = CharacterFact.create(owner, null, CharacterFactType.LEVEL, "level", "10", "10",
                JSON.objectNode().put("value", 10), job.getEpisode(), null, job, null, job.getSourceEpisodeNo());
        em.persist(fact);
        return fact;
    }

    private void currentFact(WorkCharacter owner, AnalysisJob job) {
        int order = sourceOrders.merge(owner.getId(), 1, Integer::sum) - 1;
        em.persist(CharacterSnapshotSource.create(owner, CharacterFactType.LEVEL, "level", fact(owner, job), order));
    }

    private void historyFact(WorkCharacter owner, AnalysisJob job) {
        fact(owner, job);
    }

    private SettingCandidate discovery(WorkCharacter owner, AnalysisJob job) {
        var evidence = JSON.arrayNode();
        evidence.addObject().put("quote", owner.getName() + "라고 불렀다.");
        SettingCandidate candidate = SettingCandidate.createCharacterDiscovery(owner.getWork(), job.getEpisode(), null, job,
                owner.getName(), owner.getName(), owner.getId(), SettingCandidateMatchStatus.MATCHED, evidence, BigDecimal.ONE, null);
        em.persist(candidate);
        return candidate;
    }

    private void confirmedDiscovery(WorkCharacter owner, AnalysisJob job) {
        discovery(owner, job).confirm();
    }

    private void worldCandidate(WorldSetting target, AnalysisJob job, boolean confirmed, boolean historyOnly) {
        var evidence = JSON.arrayNode();
        evidence.addObject().put("quote", "엘프는 북부에 산다.");
        WorldSettingCandidate candidate = WorldSettingCandidate.create(job.getWork(), job.getEpisode(), job,
                WorldSettingCategory.RACE, "엘프", "서식지", "북부", evidence, BigDecimal.ONE, JSON.objectNode());
        ReflectionTestUtils.setField(candidate, "comparisonStatus", WorldSettingComparisonStatus.COMPLETED);
        if (target != null) ReflectionTestUtils.setField(candidate, "targetWorldSetting", target);
        if (confirmed) ReflectionTestUtils.setField(candidate, "reviewStatus", WorldSettingReviewStatus.CONFIRMED);
        ReflectionTestUtils.setField(candidate, "historyOnly", historyOnly);
        em.persist(candidate);
    }
}
