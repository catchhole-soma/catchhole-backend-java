package org.monitoring.catchholebackend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.type.*;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.exception.AppException;
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
@DisplayName("같은 회차의 수동 수정·제외와 표시된 최종 결과 확정")
class ManualCharacterFinalReviewIntegrationTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    @Autowired EntityManager entities;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SettingCandidateService review;
    @Autowired CharacterSnapshotAccessor snapshots;
    @MockitoBean CharacterFactComparisonWorkerService comparisons;
    @MockitoBean CharacterFactComparisonJobCoordinator coordinator;
    TransactionTemplate tx;

    @BeforeEach void prepare() { tx = new TransactionTemplate(transactions); }

    @Test
    @DisplayName("한 후보 수정과 다른 후보 제외는 남은 제안·선택·시각을 보존하고 AI 없이 확정한다")
    void editsAndExclusionsPreserveFinalResults() {
        Fixture f = fixture(false);
        var sibling = get(f, f.second());
        review.updateSettingCandidate(f.member(), f.work(), f.first(),
                new SettingCandidateUpdateRequest("profile.affiliation", "작가가 정한 소속", null, get(f, f.first()).updatedAt()));
        review.dismissSettingCandidate(f.member(), f.work(), f.third());
        var unchanged = get(f, f.second());
        assertThat(unchanged.updatedAt()).isEqualTo(sibling.updatedAt());
        assertThat(unchanged.proposedFactValue()).isEqualTo("기사단 소속이며 정찰 임무를 맡는다.");
        assertThat(unchanged.comparisonStatus()).isEqualTo(CharacterFactComparisonStatus.COMPLETED);
        var edited = get(f, f.first());
        assertThat(edited.reviewedApplicationMode()).isEqualTo(CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
        assertThat(edited.comparisonStatus()).isEqualTo(CharacterFactComparisonStatus.NOT_REQUIRED);
        var request = request(f, List.of(f.first(), f.second()));
        assertThat(review.confirmSettingCandidateGroup(f.member(), f.work(), request).recomparisonRequired()).isFalse();
        // 응답 유실 뒤 같은 요청 재전송은 이력을 중복 생성하지 않는다.
        assertThat(review.confirmSettingCandidateGroup(f.member(), f.work(), request).recomparisonRequired()).isFalse();
        tx.executeWithoutResult(status -> {
            var snapshot = snapshots.read(entities.find(WorkCharacter.class, f.character()));
            assertThat(snapshot.get(new CharacterSnapshotSlot(CharacterFactType.PROFILE, "profile.affiliation")).factValue())
                    .isEqualTo("작가가 정한 소속");
            assertThat(snapshot.get(new CharacterSnapshotSlot(CharacterFactType.PROFILE, "profile.duty")).factValue())
                    .isEqualTo("기사단 소속이며 정찰 임무를 맡는다.");
            assertThat(entities.createQuery("select count(f) from CharacterFact f where f.settingCandidate.analysisJob.id = :job", Long.class)
                    .setParameter("job", f.job()).getSingleResult()).isEqualTo(2);
        });
        noComparison();
    }

    @Test
    @DisplayName("직접 검토로 우회할 수 없는 비교 실패는 값 수정으로 승인하지 않는다")
    void nonDeferrableFailureCannotBecomeFinalDraft() {
        Fixture f = fixture(false);
        tx.executeWithoutResult(status -> {
            var candidate = entities.find(SettingCandidate.class, f.first());
            candidate.requestComparison();
            candidate.startComparison();
            candidate.failComparison(AnalysisFailureCode.UNEXPECTED_ERROR, "비교 실패");
        });
        var before = get(f, f.first());
        assertThatThrownBy(() -> review.updateSettingCandidate(f.member(), f.work(), f.first(),
                new SettingCandidateUpdateRequest("profile.affiliation", "새 소속", null, before.updatedAt())))
                .isInstanceOf(AppException.class);
        var after = get(f, f.first());
        assertThat(after.reviewedApplicationMode()).isNull();
        assertThat(after.comparisonStatus()).isEqualTo(CharacterFactComparisonStatus.FAILED);
        assertThat(after.attributeValue()).isEqualTo(before.attributeValue());
        noComparison();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("여러 원본 Job의 구형 수동 그룹은 수정한 값으로 공통 비교를 다시 예약한다")
    void recomputesLegacyCrossJobGroupAfterEdit(boolean newCharacter) {
        Fixture f = fixture(newCharacter);
        moveToNextEpisodeJob(f, f.third());
        review.updateSettingCandidate(f.member(), f.work(), f.first(),
                new SettingCandidateUpdateRequest("profile.affiliation", "수정한 소속", null, get(f, f.first()).updatedAt()));
        var edited = get(f, f.first());
        assertThat(edited.attributeValue()).isEqualTo("수정한 소속");
        assertThat(edited.reviewedApplicationMode()).isNull();
        assertThat(edited.comparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PENDING);
        verify(coordinator).enqueueIfNeeded(org.mockito.ArgumentMatchers.eq(f.member()),
                org.mockito.ArgumentMatchers.argThat(candidate -> candidate.getId().equals(f.first())));
        assertThatThrownBy(() -> review.confirmSettingCandidateGroup(f.member(), f.work(),
                request(f, List.of(f.first(), f.second(), f.third())))).isInstanceOf(AppException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("마지막 타 회차 후보를 제외해도 제외 전 그룹을 기준으로 재비교한다")
    void recomputesLegacyGroupBeforeExcludingLastOtherJob(boolean newCharacter) {
        Fixture f = fixture(newCharacter);
        moveToNextEpisodeJob(f, f.third());
        review.dismissSettingCandidate(f.member(), f.work(), f.third());
        assertThat(get(f, f.third()).reviewStatus()).isEqualTo(SettingCandidateReviewStatus.DISMISSED);
        verify(coordinator).enqueueScopes(org.mockito.ArgumentMatchers.eq(f.member()), any());
    }

    @Test
    @DisplayName("같은 배치라도 다른 인물의 타 회차 후보는 단일 회차 검토를 방해하지 않는다")
    void unrelatedCharacterDoesNotInvalidateSingleJobReview() {
        Fixture f = fixture(false);
        tx.executeWithoutResult(status -> {
            var source = entities.find(AnalysisJob.class, f.job());
            var character = WorkCharacter.create(source.getWork(), "다른 인물", null, null, null, null, null, null, null, null, null);
            entities.persist(character);
            candidate(job(source.getWork(), source.getBatch(), episode(source.getWork(), 7)), character,
                    "profile.title", "길잡이", "길잡이");
        });
        review.updateSettingCandidate(f.member(), f.work(), f.first(),
                new SettingCandidateUpdateRequest("profile.affiliation", "새 소속", null, get(f, f.first()).updatedAt()));
        assertThat(get(f, f.first()).reviewedApplicationMode()).isEqualTo(CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
        noComparison();
    }

    private void moveToNextEpisodeJob(Fixture f, UUID candidateId) {
        tx.executeWithoutResult(status -> {
            var candidate = entities.find(SettingCandidate.class, candidateId);
            var source = candidate.getAnalysisJob();
            var nextEpisode = episode(source.getWork(), 7);
            var nextJob = job(source.getWork(), source.getBatch(), nextEpisode);
            entities.flush();
            // 원본 연결은 updatable=false이므로 구형 fixture의 다른 Job 연결을 DB에 직접 구성한다.
            entities.createNativeQuery("update setting_candidates set analysis_job_id = :job, episode_id = :episode where id = :id")
                    .setParameter("job", nextJob.getId()).setParameter("episode", nextEpisode.getId())
                    .setParameter("id", candidateId).executeUpdate();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("선행 후보를 제외하거나 이력으로 남겨도 화면의 병합 최종 문장을 그대로 반영한다")
    void respectsFinalMergeAfterDependencyChoice(boolean history) {
        Fixture f = fixture(false);
        if (!history) review.dismissSettingCandidate(f.member(), f.work(), f.first());
        else review.updateSettingCandidate(f.member(), f.work(), f.first(), new SettingCandidateUpdateRequest(
                "profile.affiliation", "기사단", CharacterFactConfirmApplicationMode.HISTORY_ONLY, get(f, f.first()).updatedAt()));
        review.dismissSettingCandidate(f.member(), f.work(), f.third());
        assertThat(review.confirmSettingCandidateGroup(f.member(), f.work(), request(f,
                history ? List.of(f.first(), f.second()) : List.of(f.second()))).recomparisonRequired()).isFalse();
        tx.executeWithoutResult(status -> {
            var snapshot = snapshots.read(entities.find(WorkCharacter.class, f.character()));
            assertThat(snapshot).doesNotContainKey(new CharacterSnapshotSlot(CharacterFactType.PROFILE, "profile.affiliation"));
            assertThat(snapshot.get(new CharacterSnapshotSlot(CharacterFactType.PROFILE, "profile.duty")).factValue())
                    .isEqualTo("기사단 소속이며 정찰 임무를 맡는다.");
        });
        noComparison();
    }

    @Test
    @DisplayName("저장한 선택은 새로 조회한 뒤에도 현재·이력 사이에서 바꿀 수 있다")
    void changesDraftSelectionWithoutComparison() {
        Fixture f = fixture(false);
        for (var mode : List.of(CharacterFactConfirmApplicationMode.HISTORY_ONLY, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL)) {
            review.updateSettingCandidate(f.member(), f.work(), f.first(), new SettingCandidateUpdateRequest(
                    "profile.affiliation", "새 소속", mode, get(f, f.first()).updatedAt()));
            assertThat(get(f, f.first()).reviewedApplicationMode()).isEqualTo(mode);
        }
        noComparison();
    }

    @ParameterizedTest
    @ValueSource(strings = {"snapshot", "candidate", "source", "running", "other-episode", "foreign-dependency"})
    @DisplayName("외부 설정·후보·원고·진행 상태·다른 회차가 바뀌면 원자적으로 거절하고 AI를 예약하지 않는다")
    void preservesExternalGuards(String scenario) {
        Fixture f = fixture(false);
        var request = request(f, List.of(f.first(), f.second(), f.third()));
        tx.executeWithoutResult(status -> {
            var candidate = entities.find(SettingCandidate.class, f.first());
            var job = candidate.getAnalysisJob();
            switch (scenario) {
                case "snapshot" -> entities.createNativeQuery("update characters set snapshot_version = 1 where id = :id").setParameter("id", f.character()).executeUpdate();
                case "candidate" -> candidate.recordUserModification();
                case "source" -> entities.createNativeQuery("update episodes set content_hash = :hash where id = :id").setParameter("hash", "b".repeat(64)).setParameter("id", candidate.getEpisode().getId()).executeUpdate();
                case "running" -> entities.createNativeQuery("update analysis_jobs set status = 'RUNNING' where id = :id").setParameter("id", job.getId()).executeUpdate();
                case "other-episode" -> {
                    var other = episode(job.getWork(), 7);
                    var otherJob = job(job.getWork(), job.getBatch(), other);
                    ReflectionTestUtils.setField(candidate, "analysisJob", otherJob);
                    ReflectionTestUtils.setField(candidate, "episode", other);
                }
                case "foreign-dependency" -> ReflectionTestUtils.setField(candidate, "comparisonDependencyCandidateIds", JSON.arrayNode().add(UUID.randomUUID().toString()));
            }
        });
        assertThatThrownBy(() -> review.confirmSettingCandidateGroup(f.member(), f.work(), request)).isInstanceOf(AppException.class);
        tx.executeWithoutResult(status -> {
            assertThat(entities.find(SettingCandidate.class, f.second()).getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
            assertThat(snapshots.read(entities.find(WorkCharacter.class, f.character()))).isEmpty();
        });
        noComparison();
    }

    @Test
    @DisplayName("낡은 수정 요청은 새 초안을 덮어쓰지 않는다")
    void rejectsStaleDraft() {
        Fixture f = fixture(false);
        var before = get(f, f.first());
        review.updateSettingCandidate(f.member(), f.work(), f.first(), new SettingCandidateUpdateRequest(
                "profile.affiliation", "첫 수정", null, before.updatedAt()));
        assertThatThrownBy(() -> review.updateSettingCandidate(f.member(), f.work(), f.first(),
                new SettingCandidateUpdateRequest("profile.affiliation", "낡은 수정", null, before.updatedAt())))
                .isInstanceOf(AppException.class);
        assertThat(get(f, f.first()).attributeValue()).isEqualTo("첫 수정");
        noComparison();
    }

    @Test
    @DisplayName("확인 필요 후보는 저장 방식과 값을 정하기 전에는 현재값으로 확정하지 않는다")
    void reviewRequiredNeedsExplicitDecision() {
        Fixture f = fixture(false);
        tx.executeWithoutResult(status -> ReflectionTestUtils.setField(entities.find(SettingCandidate.class, f.first()),
                "suggestedOperation", CharacterFactOperation.REVIEW_REQUIRED));
        assertThatThrownBy(() -> review.confirmSettingCandidateGroup(f.member(), f.work(), request(f, List.of(f.first(), f.second(), f.third()))))
                .isInstanceOf(AppException.class);
        review.updateSettingCandidate(f.member(), f.work(), f.first(), new SettingCandidateUpdateRequest(
                "profile.affiliation", "기사단", CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, get(f, f.first()).updatedAt()));
        assertThat(review.confirmSettingCandidateGroup(f.member(), f.work(), request(f, List.of(f.first(), f.second(), f.third())))
                .recomparisonRequired()).isFalse();
        noComparison();
    }

    @Test
    @DisplayName("신규 인물도 수정·제외 후 한 번만 만들며 최종 결과를 저장한다")
    void newCharacterFinalReview() {
        Fixture f = fixture(true);
        review.updateSettingCandidate(f.member(), f.work(), f.first(), new SettingCandidateUpdateRequest(
                "profile.affiliation", "새 소속", null, get(f, f.first()).updatedAt()));
        review.dismissSettingCandidate(f.member(), f.work(), f.third());
        var request = request(f, List.of(f.first(), f.second()));
        assertThat(review.confirmSettingCandidateGroup(f.member(), f.work(), request).recomparisonRequired()).isFalse();
        assertThat(review.confirmSettingCandidateGroup(f.member(), f.work(), request).recomparisonRequired()).isFalse();
        tx.executeWithoutResult(status -> assertThat(entities.createQuery("select count(c) from WorkCharacter c where c.work.id = :work", Long.class)
                .setParameter("work", f.work()).getSingleResult()).isEqualTo(1));
        noComparison();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("앞선 부상 후보를 제외해도 종료·부수 삭제의 최종 상태를 AI 없이 반영한다")
    void acceptsRemovalOfExcludedPendingStatus(boolean pureRemove) {
        Fixture f = fixture(false);
        tx.executeWithoutResult(status -> {
            var second = entities.find(SettingCandidate.class, f.second());
            entities.persist(CharacterSettingSchema.create(second.getWork(), "status.recovered", null, "회복", CharacterFactType.STATUS,
                    SettingValueType.JSON, CharacterSettingValueSemantics.BASE_VALUE, CharacterSettingMergePolicy.UPSERT_BY_NAME,
                    JSON.arrayNode(), CharacterSettingSchemaSource.SYSTEM_SEED, true));
            ReflectionTestUtils.setField(second, "attributeName", "status.recovered");
            ReflectionTestUtils.setField(second, "attributeValue", "회복했다");
            ReflectionTestUtils.setField(second, "valueType", SettingValueType.JSON);
            ReflectionTestUtils.setField(second, "valueJson", JSON.objectNode().put("name", "회복"));
            ReflectionTestUtils.setField(second, "suggestedOperation", pureRemove ? CharacterFactOperation.REMOVE : CharacterFactOperation.ADD);
            ReflectionTestUtils.setField(second, "comparisonTargetFactType", pureRemove ? null : CharacterFactType.STATUS);
            ReflectionTestUtils.setField(second, "comparisonTargetFactKey", pureRemove ? null : "status.recovered");
            ReflectionTestUtils.setField(second, "resolvedCanonicalFactKey", "status.recovered");
            ReflectionTestUtils.setField(second, "proposedFactValue", pureRemove ? null : "회복했다");
            ReflectionTestUtils.setField(second, "proposedValueJson", pureRemove ? null : JSON.objectNode().put("name", "회복"));
            ReflectionTestUtils.setField(second, "removedSnapshotEntriesJson", JSON.arrayNode().add(JSON.objectNode()
                    .put("factType", "STATUS").put("factKey", "status.wound")));
        });
        review.dismissSettingCandidate(f.member(), f.work(), f.first());
        review.dismissSettingCandidate(f.member(), f.work(), f.third());
        assertThat(review.confirmSettingCandidateGroup(f.member(), f.work(), request(f, List.of(f.second()))).recomparisonRequired()).isFalse();
        tx.executeWithoutResult(status -> {
            var snapshot = snapshots.read(entities.find(WorkCharacter.class, f.character()));
            assertThat(snapshot).doesNotContainKey(new CharacterSnapshotSlot(CharacterFactType.STATUS, "status.wound"));
            assertThat(snapshot.containsKey(new CharacterSnapshotSlot(CharacterFactType.STATUS, "status.recovered"))).isEqualTo(!pureRemove);
        });
        noComparison();
    }

    private org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateResponse get(Fixture f, UUID id) {
        return review.getSettingCandidate(f.member(), f.work(), f.batch(), id);
    }
    private SettingCandidateGroupConfirmRequest request(Fixture f, List<UUID> ids) {
        return new SettingCandidateGroupConfirmRequest(f.batch(), null, ids.stream().map(id -> {
            var c = get(f, id);
            return new SettingCandidateGroupConfirmDecision(id, c.reviewedApplicationMode() == null
                    ? CharacterFactConfirmApplicationMode.APPLY_PROPOSAL : c.reviewedApplicationMode(), c.comparisonBaseSnapshotVersion(),
                    c.reviewedApplicationMode() != null, c.updatedAt());
        }).toList(), true);
    }
    private void noComparison() {
        verify(coordinator, never()).enqueueIfNeeded(any(), any());
        verify(coordinator, never()).enqueueScopes(any(), any());
        verify(comparisons, never()).hasCurrentContext(any());
    }
    private Fixture fixture(boolean newCharacter) {
        clearInvocations(coordinator, comparisons);
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
