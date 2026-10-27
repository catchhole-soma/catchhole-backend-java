package org.monitoring.catchholebackend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.character.dto.request.*;
import org.monitoring.catchholebackend.domain.character.entity.*;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.type.*;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("캐릭터 그룹 중복 제외와 대표 서사 시점 확정")
class CharacterGroupExclusionIntegrationTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    @Autowired EntityManager entities;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SettingCandidateService review;
    @Autowired CharacterService characterService;
    @Autowired CharacterSnapshotAccessor snapshots;
    @Autowired CharacterAnalysisStateSource analysisStateSource;
    @MockitoBean CharacterFactComparisonWorkerService comparisons;
    @MockitoBean CharacterFactComparisonJobCoordinator coordinator;
    TransactionTemplate tx;

    @BeforeEach void prepare() { tx = new TransactionTemplate(transactions); }

    @Test
    @DisplayName("신규 인물의 일반 그룹 확정은 EXCLUDE 행을 승격 목록에 넣지 않아야 한다")
    void unresolvedGroupWithExcludeCanConfirmWithoutPromotingDismissedRow() {
        Fixture f = fixture(true);
        org.mockito.Mockito.when(comparisons.hasCurrentContext(any())).thenReturn(true);
        tx.executeWithoutResult(status -> {
            var second = entities.find(SettingCandidate.class, f.second());
            // Real multi-origin review scope: an earlier unresolved candidate stayed pending,
            // then a later episode in the same upload batch joined its NEW-name group.
            var later = job(second.getWork(), second.getAnalysisJob().getBatch(), episode(second.getWork(), 7));
            ReflectionTestUtils.setField(second, "analysisJob", later);
            ReflectionTestUtils.setField(second, "episode", later.getEpisode());
            ReflectionTestUtils.setField(second, "sourceContentS3Key", later.getSourceContentS3Key());
            ReflectionTestUtils.setField(second, "effectiveFromEpisodeNo", 7);
            ReflectionTestUtils.setField(second, "comparisonDependencyCandidateIds", JSON.arrayNode());
            ReflectionTestUtils.setField(entities.find(SettingCandidate.class, f.third()), "suggestedOperation", CharacterFactOperation.EXCLUDE);
        });
        var standard = new SettingCandidateGroupConfirmRequest(f.batch(), null,
                List.of(f.first(), f.second(), f.third()).stream().map(id -> new SettingCandidateGroupConfirmDecision(
                        id, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, 0L, false, get(f, id).updatedAt())).toList(), false);
        assertThat(review.confirmSettingCandidateGroup(f.member(), f.work(), standard).recomparisonRequired()).isFalse();
        assertThat(review.confirmSettingCandidateGroup(f.member(), f.work(), standard).recomparisonRequired()).isFalse();
        tx.executeWithoutResult(status -> {
            assertThat(entities.find(SettingCandidate.class, f.third()).getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.DISMISSED);
            assertThat(entities.createQuery("select count(f) from CharacterFact f where f.workCharacter.work.id = :work", Long.class)
                    .setParameter("work", f.work()).getSingleResult()).isEqualTo(2);
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    @DisplayName("신규 인물의 한 회차·여러 회차 그룹은 승인한 중복 제외의 후행 관측 시점도 반영한다")
    void newCharacterSemanticExcludeRetainsOwnerForNarrativeTimeFinalization(boolean multipleOrigins) {
        Fixture f = fixture(true);
        review.dismissSettingCandidate(f.member(), f.work(), f.second());
        tx.executeWithoutResult(status -> {
            var first = entities.find(SettingCandidate.class, f.first());
            var excluded = entities.find(SettingCandidate.class, f.third());
            if (multipleOrigins) moveToLaterOrigin(excluded);
            var firstTime = point("T1", first.getId());
            var laterTime = point("T2", excluded.getId());
            choose(first, null, firstTime);
            choose(excluded, firstTime, laterTime);
            ReflectionTestUtils.setField(excluded, "suggestedOperation", CharacterFactOperation.EXCLUDE);
        });
        org.mockito.Mockito.when(comparisons.hasCurrentContext(any())).thenReturn(true);
        var request = request(f, List.of(f.first(), f.third()), !multipleOrigins);
        assertThat(review.confirmSettingCandidateGroup(f.member(), f.work(), request).recomparisonRequired()).isFalse();
        assertThat(review.confirmSettingCandidateGroup(f.member(), f.work(), request).recomparisonRequired()).isFalse();
        tx.executeWithoutResult(status -> {
            var character = entities.createQuery("select c from WorkCharacter c where c.work.id = :work", WorkCharacter.class)
                    .setParameter("work", f.work()).getSingleResult();
            assertThat(entities.find(SettingCandidate.class, f.third()).getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.DISMISSED);
            assertThat(character.getCurrentNarrativeTime().path("description").asText()).isEqualTo("T2");
            assertThat(character.getSnapshotVersion()).isEqualTo(1);
            assertThat(entities.find(SettingCandidate.class, f.third()).getMatchedCharacterId()).isEqualTo(character.getId());
            // The next analysis must be able to resolve the persisted observation's actual owner.
            assertThat(analysisStateSource.captureCharacter(character).path("currentNarrativeTime").path("sourceCandidateId").asText())
                    .isEqualTo(f.third().toString());
            assertThat(factCount(f.work())).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("기존 인물의 최종 승인 결과가 모두 EXCLUDE여도 검증된 관측 시점은 반영해야 한다")
    void existingCharacterAllExcludeGroupStillFinalizesApprovedNarrativeTime() {
        Fixture f = fixture(false);
        review.dismissSettingCandidate(f.member(), f.work(), f.second());
        review.dismissSettingCandidate(f.member(), f.work(), f.third());
        tx.executeWithoutResult(status -> {
            var first = entities.find(SettingCandidate.class, f.first());
            var character = entities.find(WorkCharacter.class, f.character());
            var oldTime = point("T0", UUID.randomUUID());
            character.updateCurrentNarrativeTime(oldTime, true);
            choose(first, oldTime, point("T1", first.getId()));
            ReflectionTestUtils.setField(first, "suggestedOperation", CharacterFactOperation.EXCLUDE);
        });
        var request = request(f, List.of(f.first()));
        assertThat(review.confirmSettingCandidateGroup(f.member(), f.work(), request).recomparisonRequired()).isFalse();
        assertThat(review.confirmSettingCandidateGroup(f.member(), f.work(), request).recomparisonRequired()).isFalse();
        tx.executeWithoutResult(status -> {
            var first = entities.find(SettingCandidate.class, f.first());
            assertThat(first.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.DISMISSED);
            assertThat(first.getRawComparisonJson().path("validatedNarrativeTime").path("advance").asBoolean()).isTrue();
            assertThat(entities.find(WorkCharacter.class, f.character()).getCurrentNarrativeTime().path("description").asText())
                    .isEqualTo("T1");
            assertThat(entities.find(WorkCharacter.class, f.character()).getSnapshotVersion()).isEqualTo(2);
            assertThat(factCount(f.work())).isZero();
        });
    }

    @Test
    @DisplayName("인물 보관 후 중복 제외 최종 승인은 성공하되 보관한 시점과 버전은 바꾸지 않는다")
    void archivedCharacterAllExcludeApprovalPreservesArchivedTimeAndVersion() {
        Fixture f = fixture(false);
        review.dismissSettingCandidate(f.member(), f.work(), f.second());
        review.dismissSettingCandidate(f.member(), f.work(), f.third());
        var oldTime = point("보관한 시점", UUID.randomUUID());
        tx.executeWithoutResult(status -> {
            var first = entities.find(SettingCandidate.class, f.first());
            var character = entities.find(WorkCharacter.class, f.character());
            character.updateCurrentNarrativeTime(oldTime, true);
            choose(first, oldTime, point("제외 후보의 후행 시점", first.getId()));
            ReflectionTestUtils.setField(first, "suggestedOperation", CharacterFactOperation.EXCLUDE);
        });
        characterService.archiveCharacter(f.member(), f.work(), f.character());
        long archivedVersion = tx.execute(status -> entities.find(WorkCharacter.class, f.character()).getSnapshotVersion());

        var request = request(f, List.of(f.first()));
        assertThat(review.confirmSettingCandidateGroup(f.member(), f.work(), request).recomparisonRequired()).isFalse();
        assertThat(review.confirmSettingCandidateGroup(f.member(), f.work(), request).recomparisonRequired()).isFalse();

        tx.executeWithoutResult(status -> {
            var character = entities.find(WorkCharacter.class, f.character());
            assertThat(entities.find(SettingCandidate.class, f.first()).getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.DISMISSED);
            assertThat(character.getStatus()).isEqualTo(CharacterStatus.ARCHIVED);
            assertThat(character.getCurrentNarrativeTime()).isEqualTo(oldTime);
            assertThat(character.getSnapshotVersion()).isEqualTo(archivedVersion);
            assertThat(factCount(f.work())).isZero();
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    @DisplayName("신규 인물의 모든 결과가 제외이면 캐릭터나 Fact를 만들지 않고 재전송도 멱등이다")
    void entirelyExcludedNewGroupCreatesNoCharacter(boolean displayedResults) {
        Fixture f = fixture(true);
        org.mockito.Mockito.when(comparisons.hasCurrentContext(any())).thenReturn(true);
        tx.executeWithoutResult(status -> {
            for (UUID id : List.of(f.first(), f.second(), f.third())) {
                var candidate = entities.find(SettingCandidate.class, id);
                ReflectionTestUtils.setField(candidate, "suggestedOperation", CharacterFactOperation.EXCLUDE);
                ReflectionTestUtils.setField(candidate, "comparisonDependencyCandidateIds", JSON.arrayNode());
            }
        });
        var request = request(f, List.of(f.first(), f.second(), f.third()), displayedResults);
        assertThat(review.confirmSettingCandidateGroup(f.member(), f.work(), request).recomparisonRequired()).isFalse();
        assertThat(review.confirmSettingCandidateGroup(f.member(), f.work(), request).recomparisonRequired()).isFalse();
        tx.executeWithoutResult(status -> {
            assertThat(entities.createQuery("select count(c) from WorkCharacter c where c.work.id = :work", Long.class)
                    .setParameter("work", f.work()).getSingleResult()).isZero();
            assertThat(factCount(f.work())).isZero();
        });
    }

    @Test
    @DisplayName("사용자가 반려한 후보의 관측 시점은 남은 그룹 승인으로 되살리지 않는다")
    void explicitUserDismissalDoesNotAdvanceNarrativeTime() {
        Fixture f = fixture(false);
        review.dismissSettingCandidate(f.member(), f.work(), f.second());
        tx.executeWithoutResult(status -> {
            var character = entities.find(WorkCharacter.class, f.character());
            var oldTime = point("T0", UUID.randomUUID());
            character.updateCurrentNarrativeTime(oldTime, true);
            var rejected = entities.find(SettingCandidate.class, f.third());
            ReflectionTestUtils.setField(rejected, "suggestedOperation", CharacterFactOperation.EXCLUDE);
            choose(rejected, oldTime, point("T1", rejected.getId()));
        });
        review.dismissSettingCandidate(f.member(), f.work(), f.third());
        var request = request(f, List.of(f.first()));
        assertThat(review.confirmSettingCandidateGroup(f.member(), f.work(), request).recomparisonRequired()).isFalse();
        tx.executeWithoutResult(status -> {
            var rejected = entities.find(SettingCandidate.class, f.third());
            assertThat(rejected.getRawComparisonJson() == null
                    || !rejected.getRawComparisonJson().has("validatedNarrativeTime")).isTrue();
            assertThat(entities.find(WorkCharacter.class, f.character()).getCurrentNarrativeTime().path("description").asText())
                    .isEqualTo("T0");
            assertThat(factCount(f.work())).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("같은 작품의 동명 제외 후보도 이번 승인 그룹 밖이면 새 인물에 임의 연결하지 않는다")
    void newGroupDoesNotClaimExcludedObservationFromAnotherBatch() {
        Fixture f = fixture(true);
        review.dismissSettingCandidate(f.member(), f.work(), f.second());
        review.dismissSettingCandidate(f.member(), f.work(), f.third());
        UUID unrelatedId = tx.execute(status -> {
            var first = entities.find(SettingCandidate.class, f.first());
            var firstTime = point("T1", first.getId());
            choose(first, null, firstTime);
            var work = first.getWork();
            var otherBatch = UploadBatch.create(work, work.getMember(), UploadType.SINGLE_EPISODE, UploadSourceType.FILE);
            entities.persist(otherBatch);
            var unrelated = candidate(job(work, otherBatch, episode(work, 8)), null, "profile.title", "신참", "신참");
            ReflectionTestUtils.setField(unrelated, "suggestedOperation", CharacterFactOperation.EXCLUDE);
            choose(unrelated, firstTime, point("T2", unrelated.getId()));
            unrelated.dismiss();
            entities.flush();
            return unrelated.getId();
        });
        assertThat(review.confirmSettingCandidateGroup(f.member(), f.work(), request(f, List.of(f.first()))).recomparisonRequired()).isFalse();
        tx.executeWithoutResult(status -> {
            var character = entities.createQuery("select c from WorkCharacter c where c.work.id = :work", WorkCharacter.class)
                    .setParameter("work", f.work()).getSingleResult();
            assertThat(character.getCurrentNarrativeTime().path("description").asText()).isEqualTo("T1");
            assertThat(entities.find(SettingCandidate.class, unrelatedId).getMatchedCharacterId()).isNull();
            assertThat(factCount(f.work())).isEqualTo(1);
        });
    }

    private long factCount(UUID workId) {
        return entities.createQuery("select count(f) from CharacterFact f where f.workCharacter.work.id = :work", Long.class)
                .setParameter("work", workId).getSingleResult();
    }

    private void moveToLaterOrigin(SettingCandidate candidate) {
        var later = job(candidate.getWork(), candidate.getAnalysisJob().getBatch(), episode(candidate.getWork(), 7));
        ReflectionTestUtils.setField(candidate, "analysisJob", later);
        ReflectionTestUtils.setField(candidate, "episode", later.getEpisode());
        ReflectionTestUtils.setField(candidate, "sourceContentS3Key", later.getSourceContentS3Key());
        ReflectionTestUtils.setField(candidate, "effectiveFromEpisodeNo", 7);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode point(String description, UUID source) {
        var p = JSON.objectNode().put("description", description).put("sourceCandidateId", source.toString());
        p.putArray("evidenceSpans").add(JSON.objectNode().put("quote", "합성 근거").put("startOffset", 0)
                .put("endOffset", 5).put("sourceChunkId", UUID.randomUUID().toString()));
        return p;
    }

    private void choose(SettingCandidate candidate, com.fasterxml.jackson.databind.JsonNode before,
            com.fasterxml.jackson.databind.JsonNode selected) {
        var marker = JSON.objectNode().put("advance", true);
        marker.set("expectedCurrent", before == null ? JSON.nullNode() : before.deepCopy());
        marker.set("selected", selected.deepCopy());
        ReflectionTestUtils.setField(candidate, "rawComparisonJson", JSON.objectNode().set("validatedNarrativeTime", marker));
    }

    private org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateResponse get(Fixture f, UUID id) {
        return review.getSettingCandidate(f.member(), f.work(), f.batch(), id);
    }
    private SettingCandidateGroupConfirmRequest request(Fixture f, List<UUID> ids) {
        return request(f, ids, true);
    }
    private SettingCandidateGroupConfirmRequest request(Fixture f, List<UUID> ids, boolean displayedResults) {
        return new SettingCandidateGroupConfirmRequest(f.batch(), null, ids.stream().map(id -> {
            var c = get(f, id);
            return new SettingCandidateGroupConfirmDecision(id, c.reviewedApplicationMode() == null
                    ? CharacterFactConfirmApplicationMode.APPLY_PROPOSAL : c.reviewedApplicationMode(), c.comparisonBaseSnapshotVersion(),
                    c.reviewedApplicationMode() != null, c.updatedAt());
        }).toList(), displayedResults);
    }
    private Fixture fixture(boolean newCharacter) {
        org.mockito.Mockito.reset(coordinator, comparisons);
        return tx.execute(status -> {
            Member member = Member.register(UUID.randomUUID() + "@example.invalid", "test-only", null, "검증 작가");
            entities.persist(member);
            Work work = Work.create(member, "수동 최종 검토", WorkGenre.FANTASY, "합성 테스트");
            entities.persist(work);
            WorkCharacter character = newCharacter ? null : WorkCharacter.create(work, "루안", null, null, null, null, null, null, null, null, null);
            if (character != null) entities.persist(character);
            for (String key : List.of("profile.affiliation", "profile.duty", "profile.title")) entities.persist(CharacterSettingSchema.create(work,
                    key, null, key, CharacterFactType.PROFILE, SettingValueType.STRING, CharacterSettingValueSemantics.BASE_VALUE,
                    CharacterSettingMergePolicy.REPLACE, JSON.arrayNode(), CharacterSettingSchemaSource.SYSTEM_SEED, true));
            UploadBatch batch = UploadBatch.create(work, member, UploadType.SINGLE_EPISODE, UploadSourceType.FILE);
            entities.persist(batch);
            Episode episode = episode(work, 6);
            AnalysisJob job = job(work, batch, episode);
            var first = candidate(job, character, "profile.affiliation", "기사단", "기사단");
            var third = candidate(job, character, "profile.title", "신참", "신참");
            var second = candidate(job, character, "profile.duty", "정찰 임무", "기사단 소속이며 정찰 임무를 맡는다.");
            ReflectionTestUtils.setField(second, "suggestedOperation", CharacterFactOperation.MERGE);
            ReflectionTestUtils.setField(second, "comparisonDependencyCandidateIds", JSON.arrayNode().add(first.getId().toString()).add(third.getId().toString()));
            entities.flush();
            return new Fixture(member.getId(), work.getId(), batch.getId(), character == null ? null : character.getId(), job.getId(),
                    first.getId(), second.getId(), third.getId());
        });
    }
    private Episode episode(Work work, int number) {
        var episode = Episode.create(work, null, number, number + "화", "synthetic/" + UUID.randomUUID(), "v1", "a".repeat(64), 10);
        entities.persist(episode);
        return episode;
    }
    private AnalysisJob job(Work work, UploadBatch batch, Episode episode) {
        var job = AnalysisJob.create(work, batch, episode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(job, "status", AnalysisJobStatus.SUCCEEDED);
        entities.persist(job);
        return job;
    }
    private SettingCandidate candidate(AnalysisJob job, WorkCharacter character, String key, String value, String proposal) {
        var candidate = SettingCandidate.create(job.getWork(), job.getEpisode(), null, job, SettingEntityType.CHARACTER, "루안", key, value,
                SettingValueType.STRING, JSON.objectNode().put("value", value), JSON.arrayNode(), BigDecimal.ONE, JSON.objectNode());
        if (character != null) candidate.matchExistingCharacter(character);
        candidate.startComparison();
        candidate.recordComparisonContext(0, "validated-context");
        candidate.completeComparison(CharacterFactOperation.ADD, CharacterFactType.PROFILE, key, proposal, JSON.objectNode().put("value", proposal),
                JSON.arrayNode(), CharacterFactTemporalScope.PRESENT, "검증된 제안", JSON.objectNode(), LocalDateTime.now());
        entities.persist(candidate);
        return candidate;
    }
    private record Fixture(Long member, UUID work, UUID batch, UUID character, UUID job, UUID first, UUID second, UUID third) { }
}
