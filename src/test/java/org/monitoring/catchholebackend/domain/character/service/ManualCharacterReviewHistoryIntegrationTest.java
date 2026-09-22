package org.monitoring.catchholebackend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateConfirmRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateGroupConfirmDecision;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateGroupConfirmRequest;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactConfirmApplicationMode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
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
@DisplayName("수동 재분석 후보의 회차별 현재값 보호")
class ManualCharacterReviewHistoryIntegrationTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    @Autowired EntityManager entities;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SettingCandidateService review;
    @Autowired CharacterSnapshotAccessor accessor;
    // 이 테스트는 이미 끝난 비교를 확정하는 DB 경계만 검증한다. 비교 문맥 검증의 실행 여부도 별도 검증한다.
    @MockitoBean CharacterFactComparisonWorkerService comparisons;
    @MockitoBean CharacterFactComparisonJobCoordinator coordinator;
    private TransactionTemplate tx;

    @BeforeEach
    void prepare() {
        tx = new TransactionTemplate(transactions);
        when(comparisons.hasCurrentContext(any())).thenReturn(true);
    }

    @ParameterizedTest(name = "{0}, group={1}")
    @MethodSource("reviewCases")
    @DisplayName("단건과 그룹 모두 후행·동일 회차·수동·삭제·과거 설정을 이력으로 보존한다")
    void protectsCurrentSettingForManualReview(String scenario, boolean group) {
        Fixture fixture = fixture(scenario);
        boolean historyOnly = !List.of("empty", "earlier").contains(scenario);
        confirm(fixture, group, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
        confirm(fixture, group, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);

        tx.executeWithoutResult(status -> {
            SettingCandidate candidate = entities.find(SettingCandidate.class, fixture.candidate());
            assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
            assertThat(candidate.getConfirmedApplicationMode()).isEqualTo(historyOnly
                    ? CharacterFactConfirmApplicationMode.HISTORY_ONLY : CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
            assertThat(entities.createQuery("select count(f) from CharacterFact f where f.settingCandidate.id = :id", Long.class)
                    .setParameter("id", fixture.candidate()).getSingleResult()).isEqualTo(1L);
            WorkCharacter character = entities.find(WorkCharacter.class, fixture.character());
            var value = accessor.read(character).values().stream().filter(entry -> entry.slot().factKey().equals("stats.mental"))
                    .map(entry -> entry.factValue()).findFirst().orElse(null);
            assertThat(value).isEqualTo(historyOnly ? scenario.equals("removed") || scenario.equals("past") ? null : "36" : "35");
            assertThat(entities.find(AnalysisJob.class, fixture.job()).getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
        });
    }

    static Stream<Arguments> reviewCases() {
        return Stream.of("later", "same", "manual", "edited-origin", "unknown-source", "removed", "empty", "earlier", "past")
                .flatMap(scenario -> Stream.of(Arguments.of(scenario, false), Arguments.of(scenario, true)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("사용자가 선택한 이력 저장은 빈 항목이라도 현재값에 반영하지 않는다")
    void retainsExplicitHistorySelection(boolean group) {
        Fixture fixture = fixture("empty");
        confirm(fixture, group, CharacterFactConfirmApplicationMode.HISTORY_ONLY);
        tx.executeWithoutResult(status -> assertThat(accessor.read(entities.find(WorkCharacter.class, fixture.character()))).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("후행 값이 있어도 오래된 비교 문맥은 이력 보호로 우회하지 않고 재비교한다")
    void staleComparisonStillRequiresRecomparison(boolean group) {
        Fixture fixture = fixture("later");
        when(comparisons.hasCurrentContext(any())).thenReturn(false);
        if (group) {
            assertThat(review.confirmSettingCandidateGroup(fixture.member(), fixture.work(), request(fixture,
                    CharacterFactConfirmApplicationMode.APPLY_PROPOSAL)).recomparisonRequired()).isTrue();
        } else {
            assertThat(review.confirmSettingCandidate(fixture.member(), fixture.work(), fixture.candidate(),
                    new SettingCandidateConfirmRequest(CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, 0L))
                    .recomparisonRequired()).isTrue();
        }
        tx.executeWithoutResult(status -> {
            assertThat(entities.find(SettingCandidate.class, fixture.candidate()).getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
            assertThat(entities.createQuery("select count(f) from CharacterFact f where f.settingCandidate.id = :id", Long.class)
                    .setParameter("id", fixture.candidate()).getSingleResult()).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("교체된 원문으로 만든 이전 수동 후보는 단건과 그룹 모두 확정하지 않는다")
    void changedSourceCannotBeConfirmed(boolean group) {
        Fixture fixture = fixture("empty");
        tx.executeWithoutResult(status -> entities.find(AnalysisJob.class, fixture.job()).getEpisode()
                .updateContentStorage("new-source", "v2", "b".repeat(64)));
        assertThatThrownBy(() -> confirm(fixture, group, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL))
                .isInstanceOfSatisfying(AppException.class, exception -> assertThat(exception.getResultCode().getCode())
                        .isEqualTo("ANALYSIS_RUN_STATE_CONFLICT"));
        tx.executeWithoutResult(status -> assertThat(entities.find(SettingCandidate.class, fixture.candidate()).isPendingReview()).isTrue());
    }

    @Test
    @DisplayName("자동으로 이력이 된 제안에 의존한 그룹 후보도 함께 이력으로 보존한다")
    void dependentProposalDoesNotApplyWithoutProtectedPredecessor() {
        Fixture fixture = fixture("later");
        UUID dependentId = tx.execute(status -> {
            SettingCandidate first = entities.find(SettingCandidate.class, fixture.candidate());
            SettingCandidate dependent = candidate(first.getAnalysisJob(), entities.find(WorkCharacter.class, fixture.character()),
                    "stats.strength", CharacterFactOperation.ADD, CharacterFactTemporalScope.PRESENT);
            ReflectionTestUtils.setField(dependent, "comparisonDependencyCandidateIds", JSON.arrayNode().add(first.getId().toString()));
            return dependent.getId();
        });
        assertThat(review.confirmSettingCandidateGroup(fixture.member(), fixture.work(), new SettingCandidateGroupConfirmRequest(
                fixture.batch(), List.of(new SettingCandidateGroupConfirmDecision(fixture.candidate(), CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, 0L),
                new SettingCandidateGroupConfirmDecision(dependentId, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, 0L))))
                .recomparisonRequired()).isFalse();
        tx.executeWithoutResult(status -> {
            assertThat(entities.find(SettingCandidate.class, dependentId).getConfirmedApplicationMode()).isEqualTo(CharacterFactConfirmApplicationMode.HISTORY_ONLY);
            assertThat(accessor.read(entities.find(WorkCharacter.class, fixture.character())).keySet())
                    .noneMatch(slot -> slot.factKey().equals("stats.strength"));
        });
    }

    @Test
    @DisplayName("사용자가 선행 후보만 이력으로 선택한 불일치 그룹은 기존처럼 거절한다")
    void explicitInconsistentDependencyIsStillRejected() {
        Fixture fixture = fixture("empty");
        UUID dependentId = tx.execute(status -> {
            SettingCandidate first = entities.find(SettingCandidate.class, fixture.candidate());
            SettingCandidate dependent = candidate(first.getAnalysisJob(), entities.find(WorkCharacter.class, fixture.character()),
                    "stats.strength", CharacterFactOperation.ADD, CharacterFactTemporalScope.PRESENT);
            ReflectionTestUtils.setField(dependent, "comparisonDependencyCandidateIds", JSON.arrayNode().add(first.getId().toString()));
            return dependent.getId();
        });
        assertThatThrownBy(() -> review.confirmSettingCandidateGroup(fixture.member(), fixture.work(), new SettingCandidateGroupConfirmRequest(
                fixture.batch(), List.of(new SettingCandidateGroupConfirmDecision(fixture.candidate(), CharacterFactConfirmApplicationMode.HISTORY_ONLY, 0L),
                new SettingCandidateGroupConfirmDecision(dependentId, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, 0L)))))
                .isInstanceOfSatisfying(AppException.class, exception -> assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_GROUP_DECISION_DEPENDENCY_CONFLICT));
    }

    @Test
    @DisplayName("스키마가 없는 제외 행은 현재값 보호 검사를 거치지 않고 정상 행과 함께 처리한다")
    void excludedUnknownSchemaDoesNotBlockOtherGroupDecisions() {
        Fixture fixture = fixture("empty");
        UUID excludedId = tx.execute(status -> {
            SettingCandidate first = entities.find(SettingCandidate.class, fixture.candidate());
            SettingCandidate excluded = candidate(first.getAnalysisJob(), entities.find(WorkCharacter.class, fixture.character()),
                    "unknown.retired-schema", CharacterFactOperation.EXCLUDE, CharacterFactTemporalScope.PRESENT);
            return excluded.getId();
        });
        assertThat(review.confirmSettingCandidateGroup(fixture.member(), fixture.work(), new SettingCandidateGroupConfirmRequest(
                fixture.batch(), List.of(new SettingCandidateGroupConfirmDecision(fixture.candidate(), CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, 0L),
                new SettingCandidateGroupConfirmDecision(excludedId, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, 0L))))
                .recomparisonRequired()).isFalse();
        tx.executeWithoutResult(status -> {
            assertThat(entities.find(SettingCandidate.class, excludedId).getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.DISMISSED);
            assertThat(entities.find(SettingCandidate.class, fixture.candidate()).getConfirmedApplicationMode()).isEqualTo(CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
            assertThat(accessor.read(entities.find(WorkCharacter.class, fixture.character()))
                    .get(new CharacterSnapshotSlot(CharacterFactType.STAT, "stats.mental")).factValue()).isEqualTo("35");
            assertThat(entities.createQuery("select count(f) from CharacterFact f where f.settingCandidate.id = :id", Long.class)
                    .setParameter("id", excludedId).getSingleResult()).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("같은 새 항목의 독립 PRESENT 후보는 앞선 과거 이력에 막히지 않고 그룹 순서대로 반영한다")
    void independentCurrentProposalCanFollowHistoryOrCurrent(boolean firstIsPast) {
        Fixture fixture = fixture(firstIsPast ? "past" : "empty");
        UUID secondId = tx.execute(status -> {
            SettingCandidate first = entities.find(SettingCandidate.class, fixture.candidate());
            SettingCandidate second = candidate(first.getAnalysisJob(), entities.find(WorkCharacter.class, fixture.character()),
                    "stats.mental", CharacterFactOperation.UPDATE, CharacterFactTemporalScope.PRESENT);
            ReflectionTestUtils.setField(second, "attributeValue", "36");
            ReflectionTestUtils.setField(second, "valueJson", JSON.objectNode().put("value", 36));
            ReflectionTestUtils.setField(second, "proposedFactValue", "36");
            ReflectionTestUtils.setField(second, "proposedValueJson", JSON.objectNode().put("value", 36));
            return second.getId();
        });
        assertThat(review.confirmSettingCandidateGroup(fixture.member(), fixture.work(), new SettingCandidateGroupConfirmRequest(
                fixture.batch(), List.of(new SettingCandidateGroupConfirmDecision(fixture.candidate(), CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, 0L),
                new SettingCandidateGroupConfirmDecision(secondId, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, 0L))))
                .recomparisonRequired()).isFalse();
        tx.executeWithoutResult(status -> {
            assertThat(entities.find(SettingCandidate.class, secondId).getConfirmedApplicationMode()).isEqualTo(CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
            assertThat(accessor.read(entities.find(WorkCharacter.class, fixture.character()))
                    .get(new CharacterSnapshotSlot(CharacterFactType.STAT, "stats.mental")).factValue()).isEqualTo("36");
            assertThat(entities.find(SettingCandidate.class, fixture.candidate()).getConfirmedApplicationMode()).isEqualTo(firstIsPast
                    ? CharacterFactConfirmApplicationMode.HISTORY_ONLY : CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
        });
    }

    @ParameterizedTest(name = "{0}, group={1}")
    @MethodSource("mergeCases")
    @DisplayName("일반 수동 비교의 UPDATE·MERGE 제안값은 후보 원값으로 재작성하지 않는다")
    void preservesValidatedProposal(String operation, boolean group) {
        Fixture fixture = fixture("earlier");
        tx.executeWithoutResult(status -> {
            SettingCandidate pending = entities.find(SettingCandidate.class, fixture.candidate());
            ReflectionTestUtils.setField(pending, "suggestedOperation", CharacterFactOperation.valueOf(operation));
            ReflectionTestUtils.setField(pending, "proposedFactValue", "99");
            ReflectionTestUtils.setField(pending, "proposedValueJson", JSON.objectNode().put("value", 99));
        });
        confirm(fixture, group, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
        tx.executeWithoutResult(status -> {
            assertThat(entities.find(SettingCandidate.class, fixture.candidate()).getSuggestedOperation()).isEqualTo(CharacterFactOperation.valueOf(operation));
            assertThat(accessor.read(entities.find(WorkCharacter.class, fixture.character()))
                    .get(new CharacterSnapshotSlot(CharacterFactType.STAT, "stats.mental")).factValue()).isEqualTo("99");
            assertThat(entities.createQuery("select f.factValue from CharacterFact f where f.settingCandidate.id = :id", String.class)
                    .setParameter("id", fixture.candidate()).getSingleResult()).isEqualTo("35");
        });
    }

    static Stream<Arguments> mergeCases() {
        return Stream.of("UPDATE", "MERGE").flatMap(operation -> Stream.of(Arguments.of(operation, false), Arguments.of(operation, true)));
    }

    @ParameterizedTest(name = "{0}, {1}, group={2}")
    @MethodSource("removalCases")
    @DisplayName("REMOVE와 부수 삭제는 앞 회차만 정상 적용하고 후행 회차의 상태는 지우지 않는다")
    void protectsEveryRemovedSlot(String scenario, String operation, boolean group) {
        Fixture fixture = fixture("empty");
        tx.executeWithoutResult(status -> {
            WorkCharacter character = entities.find(WorkCharacter.class, fixture.character());
            SettingCandidate pending = entities.find(SettingCandidate.class, fixture.candidate());
            CharacterSnapshotSlot wound = new CharacterSnapshotSlot(CharacterFactType.STATUS, "status.부상");
            var snapshot = accessor.read(character);
            snapshot.put(wound, accessor.entry(CharacterFactType.STATUS, wound.factKey(), "부상", JSON.objectNode().put("name", "부상").put("active", true)));
            accessor.replace(character, snapshot, false, false);
            Episode originEpisode = episode(character.getWork(), scenario.equals("earlier") ? 2 : 5);
            AnalysisJob originJob = job(character.getWork(), pending.getAnalysisJob().getBatch(), originEpisode);
            SettingCandidate origin = candidate(originJob, character, "stats.strength", CharacterFactOperation.ADD, CharacterFactTemporalScope.PRESENT);
            origin.confirm();
            origin.recordConfirmedApplicationMode(CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
            CharacterFact fact = CharacterFact.create(character, origin, CharacterFactType.STATUS, wound.factKey(), "부상", "부상",
                    JSON.objectNode().put("name", "부상").put("active", true), originEpisode, null, originJob, BigDecimal.ONE, originEpisode.getEpisodeNo());
            entities.persist(fact);
            entities.persist(CharacterSnapshotSource.create(character, wound.factType(), wound.factKey(), fact, 0));
            ReflectionTestUtils.setField(pending, "removedSnapshotEntriesJson", JSON.arrayNode().add(JSON.objectNode().put("factType", "STATUS").put("factKey", wound.factKey())));
            if (operation.equals("REMOVE")) {
                entities.persist(CharacterSettingSchema.create(character.getWork(), "status.회복", null, "회복", CharacterFactType.STATUS,
                        SettingValueType.JSON, CharacterSettingValueSemantics.BASE_VALUE, CharacterSettingMergePolicy.UPSERT_BY_NAME,
                        JSON.arrayNode(), CharacterSettingSchemaSource.SYSTEM_SEED, true));
                ReflectionTestUtils.setField(pending, "attributeName", "status.회복");
                ReflectionTestUtils.setField(pending, "attributeValue", "회복");
                ReflectionTestUtils.setField(pending, "valueType", SettingValueType.JSON);
                ReflectionTestUtils.setField(pending, "valueJson", JSON.objectNode().put("name", "회복").put("active", true));
                ReflectionTestUtils.setField(pending, "suggestedOperation", CharacterFactOperation.REMOVE);
                ReflectionTestUtils.setField(pending, "comparisonTargetFactType", null);
                ReflectionTestUtils.setField(pending, "comparisonTargetFactKey", null);
                ReflectionTestUtils.setField(pending, "proposedValueJson", null);
                ReflectionTestUtils.setField(pending, "proposedFactValue", null);
            }
        });
        confirm(fixture, group, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
        tx.executeWithoutResult(status -> {
            assertThat(accessor.read(entities.find(WorkCharacter.class, fixture.character()))
                    .containsKey(new CharacterSnapshotSlot(CharacterFactType.STATUS, "status.부상"))).isEqualTo(scenario.equals("later"));
            assertThat(entities.find(SettingCandidate.class, fixture.candidate()).getConfirmedApplicationMode()).isEqualTo(scenario.equals("later")
                    ? CharacterFactConfirmApplicationMode.HISTORY_ONLY : CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
        });
    }

    static Stream<Arguments> removalCases() {
        return Stream.of("earlier", "later").flatMap(scenario -> Stream.of("REMOVE", "ADD_WITH_REMOVAL")
                .flatMap(operation -> Stream.of(Arguments.of(scenario, operation, false), Arguments.of(scenario, operation, true))));
    }

    private void confirm(Fixture fixture, boolean group, CharacterFactConfirmApplicationMode mode) {
        if (group) assertThat(review.confirmSettingCandidateGroup(fixture.member(), fixture.work(), request(fixture, mode)).recomparisonRequired()).isFalse();
        else assertThat(review.confirmSettingCandidate(fixture.member(), fixture.work(), fixture.candidate(),
                new SettingCandidateConfirmRequest(mode, 0L)).recomparisonRequired()).isFalse();
    }

    private SettingCandidateGroupConfirmRequest request(Fixture fixture, CharacterFactConfirmApplicationMode mode) {
        return new SettingCandidateGroupConfirmRequest(fixture.batch(), List.of(new SettingCandidateGroupConfirmDecision(fixture.candidate(), mode, 0L)));
    }

    private Fixture fixture(String scenario) {
        return tx.execute(status -> {
            Member member = Member.register(UUID.randomUUID() + "@example.invalid", "test-only", null, "검증 작가");
            entities.persist(member);
            Work work = Work.create(member, "과거 수동 확정", WorkGenre.FANTASY, "테스트");
            entities.persist(work);
            WorkCharacter character = WorkCharacter.create(work, "레온", null, null, null, null,
                    List.of("empty", "removed", "past").contains(scenario) ? null : JSON.objectNode().set("stats.mental", JSON.objectNode().put("value", 36)),
                    null, null, null, null);
            entities.persist(character);
            for (String key : List.of("stats.mental", "stats.strength")) entities.persist(CharacterSettingSchema.create(work, key, null, key,
                    CharacterFactType.STAT, SettingValueType.NUMBER, CharacterSettingValueSemantics.BASE_VALUE,
                    CharacterSettingMergePolicy.REPLACE, JSON.arrayNode(), CharacterSettingSchemaSource.SYSTEM_SEED, true));
            UploadBatch batch = UploadBatch.create(work, member, UploadType.SINGLE_EPISODE, UploadSourceType.FILE);
            entities.persist(batch);
            Episode episode = episode(work, 3);
            AnalysisJob job = job(work, batch, episode);
            if (!List.of("empty", "past", "unknown-source").contains(scenario)) {
                Episode originEpisode = scenario.equals("same") ? episode : episode(work, scenario.equals("earlier") ? 2 : 5);
                AnalysisJob originJob = job(work, batch, originEpisode);
                SettingCandidate origin = candidate(originJob, character, "stats.mental", CharacterFactOperation.ADD, CharacterFactTemporalScope.PRESENT);
                if (scenario.equals("edited-origin")) origin.recordUserModification();
                origin.confirm();
                origin.recordConfirmedApplicationMode(CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
                CharacterFact fact = scenario.equals("manual") ? CharacterFact.createManual(character, CharacterFactType.STAT, "stats.mental", "36", JSON.objectNode().put("value", 36))
                        : CharacterFact.create(character, origin, CharacterFactType.STAT, "stats.mental", "36", "36", JSON.objectNode().put("value", 36),
                        originEpisode, null, originJob, BigDecimal.ONE, originEpisode.getEpisodeNo());
                entities.persist(fact);
                if (!scenario.equals("removed")) entities.persist(CharacterSnapshotSource.create(character, CharacterFactType.STAT, "stats.mental", fact, 0));
            }
            SettingCandidate pending = candidate(job, character, "stats.mental", List.of("empty", "past").contains(scenario)
                    ? CharacterFactOperation.ADD : CharacterFactOperation.UPDATE,
                    scenario.equals("past") ? CharacterFactTemporalScope.PAST : CharacterFactTemporalScope.PRESENT);
            entities.flush();
            return new Fixture(member.getId(), work.getId(), batch.getId(), character.getId(), job.getId(), pending.getId());
        });
    }

    private Episode episode(Work work, int number) {
        Episode episode = Episode.create(work, null, number, number + "화", "review/" + work.getId() + "/" + number, "v1", "a".repeat(64), 10);
        entities.persist(episode);
        return episode;
    }

    private AnalysisJob job(Work work, UploadBatch batch, Episode episode) {
        AnalysisJob job = AnalysisJob.create(work, batch, episode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(job, "status", AnalysisJobStatus.SUCCEEDED);
        entities.persist(job);
        return job;
    }

    private SettingCandidate candidate(AnalysisJob job, WorkCharacter character, String key,
            CharacterFactOperation operation, CharacterFactTemporalScope scope) {
        SettingCandidate candidate = SettingCandidate.create(job.getWork(), job.getEpisode(), null, job,
                SettingEntityType.CHARACTER, character.getName(), key, "35", SettingValueType.NUMBER,
                JSON.objectNode().put("value", 35), JSON.arrayNode(), BigDecimal.ONE, JSON.objectNode());
        candidate.matchExistingCharacter(character);
        candidate.startComparison();
        candidate.recordComparisonContext(character.getSnapshotVersion(), "validated-context");
        candidate.completeComparison(operation, CharacterFactType.STAT, key, "35", JSON.objectNode().put("value", 35),
                JSON.arrayNode(), scope, "검증된 제안", JSON.objectNode(), LocalDateTime.now());
        entities.persist(candidate);
        return candidate;
    }

    private record Fixture(Long member, UUID work, UUID batch, UUID character, UUID job, UUID candidate) { }
}
