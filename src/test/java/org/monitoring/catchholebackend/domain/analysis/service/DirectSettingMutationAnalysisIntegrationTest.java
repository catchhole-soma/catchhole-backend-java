package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.character.dto.request.CharacterUpdateRequest;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.service.CharacterService;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCreateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingIdentityUpdateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingPropertyCreateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingPropertyUpdateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingService;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:direct-setting-mutation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("확정 설정 직접 편집과 순차 분석 기록 보호")
class DirectSettingMutationAnalysisIntegrationTest {
    @Autowired EntityManager entities;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AnalysisRunStateService states;
    @Autowired CharacterService characters;
    @Autowired WorldSettingService worldSettings;
    private TransactionTemplate tx;

    @BeforeEach
    void prepare() {
        tx = new TransactionTemplate(transactions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"character-name", "character-level", "character-archive", "character-restore",
            "world-create", "world-identity", "world-add", "world-value", "world-protect"})
    @DisplayName("완료 후 직접 수정은 실제 설정만 변경하고 완료 입력과 결과를 보존한다")
    void completedAnalysisKeepsHistoricalResult(String action) {
        Fixture fixture = fixture(State.COMPLETED, action.equals("character-restore"), !action.equals("world-protect"));
        JsonNode base = tx.execute(status -> entities.find(AnalysisJob.class, fixture.job()).getRunBaseState());

        mutate(fixture, action);

        tx.executeWithoutResult(status -> {
            AnalysisJob job = entities.find(AnalysisJob.class, fixture.job());
            assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
            assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.SEALED);
            assertThat(job.getJournalInvalidationReason()).isNull();
            assertThat(job.getRunBaseState()).isEqualTo(base);
            assertThat(job.getInputStateHash()).isEqualTo("b".repeat(64));
            assertThat(job.getStateJournal().path("testEvidence").asText()).isEqualTo("당시 근거");
            WorkCharacter character = entities.find(WorkCharacter.class, fixture.character());
            WorldSetting world = entities.find(WorldSetting.class, fixture.world());
            switch (action) {
                case "character-name" -> assertThat(character.getName()).isEqualTo("수정 인물");
                case "character-level" -> assertThat(character.getCurrentLevel()).isEqualTo(11);
                case "character-archive" -> assertThat(character.getStatus()).isEqualTo(CharacterStatus.ARCHIVED);
                case "character-restore" -> assertThat(character.getStatus()).isEqualTo(CharacterStatus.ACTIVE);
                case "world-create" -> assertThat(entities.createQuery(
                                "select count(w) from WorldSetting w where w.work.id = :work", Long.class)
                        .setParameter("work", fixture.work()).getSingleResult()).isEqualTo(2);
                case "world-identity" -> assertThat(world.getSubjectName()).isEqualTo("북부 엘프");
                case "world-add" -> assertThat(world.getPropertyValue(null, "수명")).isEqualTo("천 년");
                case "world-value" -> assertThat(world.getPropertyValue(null, "서식지")).isEqualTo("남부");
                case "world-protect" -> assertThat(world.isManuallyEdited(null, "서식지")).isTrue();
                default -> throw new IllegalArgumentException(action);
            }
        });
    }

    @ParameterizedTest
    @MethodSource("blockedChanges")
    @DisplayName("실행·대기·실패 재개를 보호하고 거절된 편집은 DB에 남지 않는다")
    void meaningfulChangeWaitsWithoutInvalidatingOrPersisting(State state, String action) {
        Fixture fixture = fixture(state, action.equals("character-restore"), !action.equals("world-protect"));

        assertThatThrownBy(() -> mutate(fixture, action))
                .isInstanceOfSatisfying(AppException.class, error ->
                        assertThat(error.getResultCode().getCode()).isEqualTo("ANALYSIS_REVIEW_WAIT_REQUIRED"));

        tx.executeWithoutResult(status -> {
            AnalysisJob job = entities.find(AnalysisJob.class, fixture.job());
            assertThat(job.getStatus()).isEqualTo(state.status);
            assertThat(job.getJournalStatus()).isEqualTo(state.journal);
            assertThat(job.getJournalInvalidationReason()).isNull();
            WorkCharacter character = entities.find(WorkCharacter.class, fixture.character());
            assertThat(character.getName()).isEqualTo("레온");
            assertThat(character.getCurrentLevel()).isEqualTo(10);
            assertThat(character.getStatus()).isEqualTo(action.equals("character-restore")
                    ? CharacterStatus.ARCHIVED : CharacterStatus.ACTIVE);
            WorldSetting world = entities.find(WorldSetting.class, fixture.world());
            assertThat(world.getSubjectName()).isEqualTo("엘프");
            assertThat(world.getVersion()).isZero();
            assertThat(world.getPropertyCount()).isEqualTo(1);
            assertThat(world.getPropertyValue(null, "서식지")).isEqualTo("북부");
            assertThat(world.isManuallyEdited(null, "서식지")).isEqualTo(!action.equals("world-protect"));
            assertThat(entities.createQuery("select count(w) from WorldSetting w where w.work.id = :work", Long.class)
                    .setParameter("work", fixture.work()).getSingleResult()).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"PENDING", "RUNNING", "FAILED", "INCOMPLETE"})
    @DisplayName("같은 이름·설정·수동 보호 상태를 저장하면 실행·재개 대기 중에도 허용한다")
    void noOpDoesNotWaitOrInvalidate(State state) {
        Fixture fixture = fixture(state, false, true);
        characters.updateCharacter(fixture.member(), fixture.work(), fixture.character(), characterRequest("  레온  ", 10));
        worldSettings.updateWorldSettingIdentity(fixture.member(), fixture.work(), fixture.world(),
                new WorldSettingIdentityUpdateRequest(WorldSettingCategory.RACE, "  엘프  ", 0L));
        worldSettings.updateWorldSettingProperty(fixture.member(), fixture.work(), fixture.world(),
                new WorldSettingPropertyUpdateRequest("서식지", "서식지", "북부", 0L));

        tx.executeWithoutResult(status -> {
            AnalysisJob job = entities.find(AnalysisJob.class, fixture.job());
            assertThat(job.getStatus()).isEqualTo(state.status);
            assertThat(job.getJournalStatus()).isEqualTo(state.journal);
            assertThat(job.getJournalInvalidationReason()).isNull();
            assertThat(entities.find(WorldSetting.class, fixture.world()).getVersion()).isZero();
            assertThat(entities.find(WorkCharacter.class, fixture.character()).getSnapshotVersion()).isZero();
            assertThat(entities.createQuery("select count(f) from CharacterFact f where f.workCharacter.id = :character", Long.class)
                    .setParameter("character", fixture.character()).getSingleResult()).isZero();
        });
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"INVALIDATED", "CANCELED"})
    @DisplayName("이미 중단되거나 무효화된 실행은 새로운 직접 설정 수정을 막지 않는다")
    void terminalHistoryDoesNotBlockNewSettings(State state) {
        Fixture fixture = fixture(state, false, true);
        mutate(fixture, "character-name");
        mutate(fixture, "world-value");
        tx.executeWithoutResult(status -> {
            assertThat(entities.find(WorkCharacter.class, fixture.character()).getName()).isEqualTo("수정 인물");
            assertThat(entities.find(WorldSetting.class, fixture.world()).getPropertyValue(null, "서식지")).isEqualTo("남부");
            assertThat(entities.find(AnalysisJob.class, fixture.job()).getJournalStatus()).isEqualTo(state.journal);
        });
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"FAILED", "INCOMPLETE"})
    @DisplayName("과거 실패가 새 직접 검토 완료로 대체되면 과거 기록을 보존하며 설정을 수정한다")
    void supersededLegacyFailureDoesNotLockSettings(State state) {
        Fixture fixture = fixture(state, false, true);
        tx.executeWithoutResult(status -> {
            AnalysisJob old = entities.find(AnalysisJob.class, fixture.job());
            AnalysisJob replacement = AnalysisJob.create(old.getWork(), old.getBatch(), old.getEpisode(), old.getJobType());
            ReflectionTestUtils.setField(replacement, "status", AnalysisJobStatus.SUCCEEDED);
            entities.persist(replacement);
            entities.flush();
            setCreatedAt(old.getId(), LocalDateTime.of(2026, 1, 1, 0, 0));
            setCreatedAt(replacement.getId(), LocalDateTime.of(2026, 1, 2, 0, 0));
        });

        assertHistoryAllowsMutation(fixture, state);
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"FAILED", "INCOMPLETE"})
    @DisplayName("생성 시간이 같은 분석도 UUID 순으로 최신 완료를 구분하여 과거 실패를 잠금에서 제외한다")
    void sameTimestampUsesLatestJobId(State state) {
        Fixture initial = fixture(state, false, true);
        Fixture fixture = tx.execute(status -> {
            AnalysisJob first = entities.find(AnalysisJob.class, initial.job());
            AnalysisJob second = AnalysisJob.create(first.getWork(), first.getBatch(), first.getEpisode(), first.getJobType());
            second.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
            states.initializeRun(List.of(second));
            List<AnalysisJob> orderedById = entities.createQuery(
                            "select job from AnalysisJob job where job.id in :ids order by job.id asc", AnalysisJob.class)
                    .setParameter("ids", List.of(first.getId(), second.getId())).getResultList();
            AnalysisJob old = orderedById.getFirst();
            AnalysisJob latest = orderedById.getLast();
            ReflectionTestUtils.setField(old, "status", state.status);
            ReflectionTestUtils.setField(old, "journalStatus", state.journal);
            ReflectionTestUtils.setField(old, "inputStateHash", "b".repeat(64));
            ReflectionTestUtils.setField(old, "stateJournal", JsonNodeFactory.instance.objectNode().put("testEvidence", "당시 근거"));
            ReflectionTestUtils.setField(latest, "status", AnalysisJobStatus.SUCCEEDED);
            ReflectionTestUtils.setField(latest, "journalStatus", AnalysisJournalStatus.SEALED);
            ReflectionTestUtils.setField(latest, "automaticAppliedAt", LocalDateTime.now());
            entities.flush();
            LocalDateTime sameTime = LocalDateTime.of(2026, 1, 1, 0, 0);
            setCreatedAt(first.getId(), sameTime);
            setCreatedAt(second.getId(), sameTime);
            return new Fixture(initial.member(), initial.work(), initial.character(), initial.world(), old.getId());
        });

        assertHistoryAllowsMutation(fixture, state);
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"FAILED", "INCOMPLETE"})
    @DisplayName("이미 원문 버전이 달라져 재개할 수 없는 과거 실패는 직접 설정을 잠그지 않는다")
    void staleSourceFailureDoesNotLockSettings(State state) {
        Fixture fixture = fixture(state, false, true);
        tx.executeWithoutResult(status -> {
            Episode episode = entities.find(AnalysisJob.class, fixture.job()).getEpisode();
            episode.updateContent(episode.getEpisodeNo(), episode.getTitle(), episode.getContentS3Key(),
                    "v2", "c".repeat(64), episode.getCharCount());
        });

        assertHistoryAllowsMutation(fixture, state);
    }

    private void assertHistoryAllowsMutation(Fixture fixture, State state) {
        mutate(fixture, "character-name");
        mutate(fixture, "world-value");
        tx.executeWithoutResult(status -> {
            AnalysisJob old = entities.find(AnalysisJob.class, fixture.job());
            assertThat(old.getStatus()).isEqualTo(state.status);
            assertThat(old.getJournalStatus()).isEqualTo(state.journal);
            assertThat(old.getStateJournal().path("testEvidence").asText()).isEqualTo("당시 근거");
            assertThat(old.getInputStateHash()).isEqualTo("b".repeat(64));
            assertThat(entities.find(WorkCharacter.class, fixture.character()).getName()).isEqualTo("수정 인물");
            assertThat(entities.find(WorldSetting.class, fixture.world()).getPropertyValue(null, "서식지")).isEqualTo("남부");
        });
    }

    private void setCreatedAt(UUID jobId, LocalDateTime createdAt) {
        entities.createNativeQuery("update analysis_jobs set created_at = :createdAt where id = :id")
                .setParameter("createdAt", createdAt).setParameter("id", jobId).executeUpdate();
    }

    private static Stream<Arguments> blockedChanges() {
        return Stream.of(State.PENDING, State.RUNNING, State.FAILED, State.INCOMPLETE).flatMap(state ->
                Stream.of("character-name", "character-level", "character-archive", "character-restore",
                                "world-create", "world-identity", "world-add", "world-value", "world-protect")
                        .map(action -> Arguments.of(state, action)));
    }

    private void mutate(Fixture fixture, String action) {
        switch (action) {
            case "character-name" -> characters.updateCharacter(fixture.member(), fixture.work(), fixture.character(), characterRequest("수정 인물", 10));
            case "character-level" -> characters.updateCharacter(fixture.member(), fixture.work(), fixture.character(), characterRequest("레온", 11));
            case "character-archive" -> characters.archiveCharacter(fixture.member(), fixture.work(), fixture.character());
            case "character-restore" -> characters.restoreCharacter(fixture.member(), fixture.work(), fixture.character());
            case "world-create" -> worldSettings.createWorldSetting(fixture.member(), fixture.work(),
                    new WorldSettingCreateRequest(WorldSettingCategory.RACE, "드워프", "서식지", "동굴"));
            case "world-identity" -> worldSettings.updateWorldSettingIdentity(fixture.member(), fixture.work(), fixture.world(),
                    new WorldSettingIdentityUpdateRequest(WorldSettingCategory.RACE, "북부 엘프", 0L));
            case "world-add" -> worldSettings.addWorldSettingProperty(fixture.member(), fixture.work(), fixture.world(),
                    new WorldSettingPropertyCreateRequest("수명", "천 년", 0L));
            case "world-value", "world-protect" -> worldSettings.updateWorldSettingProperty(fixture.member(), fixture.work(), fixture.world(),
                    new WorldSettingPropertyUpdateRequest("서식지", "서식지", action.equals("world-protect") ? "북부" : "남부", 0L));
            default -> throw new IllegalArgumentException(action);
        }
    }

    private CharacterUpdateRequest characterRequest(String name, int level) {
        return new CharacterUpdateRequest(name, null, null, level, null,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private Fixture fixture(State state, boolean archivedCharacter, boolean protectedWorld) {
        return tx.execute(status -> {
            Member member = Member.register(UUID.randomUUID() + "@example.invalid", "test-only", null, "검증 작가");
            entities.persist(member);
            Work work = Work.create(member, "직접 설정 수정 검증", WorkGenre.FANTASY, "테스트");
            entities.persist(work);
            WorkCharacter character = WorkCharacter.create(work, "레온", null, null, 10,
                    null, null, null, null, null, null);
            if (archivedCharacter) character.archive();
            entities.persist(character);
            WorldSetting world = WorldSetting.create(work, WorldSettingCategory.RACE, "엘프", "서식지", "북부");
            if (protectedWorld) world.protectManualProperty(null, "서식지");
            entities.persist(world);
            UploadBatch batch = UploadBatch.create(work, member, UploadType.SINGLE_EPISODE, UploadSourceType.FILE);
            entities.persist(batch);
            Episode episode = Episode.create(work, null, 76, "원고", "mutation-test/" + work.getId(), "v1", "a".repeat(64), 10);
            entities.persist(episode);
            AnalysisJob job = AnalysisJob.create(work, batch, episode, AnalysisJobType.SETTING_EXTRACTION);
            job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
            states.initializeRun(List.of(job));
            ReflectionTestUtils.setField(job, "status", state.status);
            ReflectionTestUtils.setField(job, "journalStatus", state.journal);
            ReflectionTestUtils.setField(job, "automaticInputState", job.getRunBaseState());
            if (state == State.COMPLETED || state == State.INVALIDATED) {
                ReflectionTestUtils.setField(job, "automaticAppliedAt", LocalDateTime.now());
            }
            ReflectionTestUtils.setField(job, "inputStateHash", "b".repeat(64));
            ReflectionTestUtils.setField(job, "stateJournal", JsonNodeFactory.instance.objectNode().put("testEvidence", "당시 근거"));
            entities.flush();
            return new Fixture(member.getId(), work.getId(), character.getId(), world.getId(), job.getId());
        });
    }

    private enum State {
        PENDING(AnalysisJobStatus.PENDING, AnalysisJournalStatus.PENDING),
        RUNNING(AnalysisJobStatus.RUNNING, AnalysisJournalStatus.PENDING),
        FAILED(AnalysisJobStatus.FAILED, AnalysisJournalStatus.PENDING),
        INCOMPLETE(AnalysisJobStatus.SUCCEEDED, AnalysisJournalStatus.INCOMPLETE),
        COMPLETED(AnalysisJobStatus.SUCCEEDED, AnalysisJournalStatus.SEALED),
        INVALIDATED(AnalysisJobStatus.SUCCEEDED, AnalysisJournalStatus.INVALIDATED),
        CANCELED(AnalysisJobStatus.CANCELED, AnalysisJournalStatus.PENDING);

        private final AnalysisJobStatus status;
        private final AnalysisJournalStatus journal;

        State(AnalysisJobStatus status, AnalysisJournalStatus journal) {
            this.status = status;
            this.journal = journal;
        }
    }

    private record Fixture(Long member, UUID work, UUID character, UUID world, UUID job) { }
}
