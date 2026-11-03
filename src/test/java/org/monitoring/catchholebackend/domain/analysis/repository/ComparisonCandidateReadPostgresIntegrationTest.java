package org.monitoring.catchholebackend.domain.analysis.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.hibernate.Hibernate;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFactComparisonBatch;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonBatch;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=org.monitoring.catchholebackend.domain.analysis.repository.ComparisonCandidateReadPostgresIntegrationTest$SqlCapture"
})
@ActiveProfiles("test")
@DirtiesContext
@Testcontainers
@DisplayName("비교 내부 후보 조회의 PostgreSQL JSON·잠금 경계")
class ComparisonCandidateReadPostgresIntegrationTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String SUBJECT = "합성 북부";
    private static final String SUBJECT_KEY = "synthetic-north";
    private static final String QUOTE = "합성 인물이 북부에 도착했다.";

    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_DB", "comparison_read_test").withEnv("POSTGRES_USER", "comparison_read_test")
            .withEnv("POSTGRES_PASSWORD", "disposable-comparison-read-only").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(1)));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/comparison_read_test");
        p.add("spring.datasource.username", () -> "comparison_read_test");
        p.add("spring.datasource.password", () -> "disposable-comparison-read-only");
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        p.add("spring.flyway.enabled", () -> "true");
        p.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired EntityManager em;
    @Autowired SettingCandidateRepository characters;
    @Autowired WorldSettingCandidateRepository worlds;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;

    public static class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> CAPTURE = new ThreadLocal<>();
        @Override public String inspect(String sql) {
            List<String> statements = CAPTURE.get();
            if (statements != null && sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("select")) {
                statements.add(sql.toLowerCase(Locale.ROOT));
            }
            return sql;
        }
        static void start() { CAPTURE.set(new ArrayList<>()); }
        static List<String> stop() {
            List<String> statements = List.copyOf(CAPTURE.get());
            CAPTURE.remove();
            return statements;
        }
    }

    @ParameterizedTest
    @EnumSource(QueryKind.class)
    @DisplayName("후보 조회에서 큰 Job JSON을 제외하고 필요한 출처 Job은 한 번만 읽는다")
    void readsCandidatesWithoutRepeatedJobJson(QueryKind kind) {
        Fixture f = fixture(kind);
        tx().executeWithoutResult(status -> {
            List<String> selects;
            SqlCapture.start();
            try {
                List<Row> rows = read(kind, f);
                assertThat(rows).extracting(Row::id).containsExactlyElementsOf(f.expected());
                assertThat(rows).allSatisfy(row -> {
                    assertThat(row.sourceJob().getId()).isEqualTo(f.job());
                    assertThat(row.episode()).isEqualTo(f.episode());
                    assertThat(row.evidence()).isEqualTo(evidence());
                    assertThat(row.state()).isEqualTo(kind.state());
                    assertThat(row.sourceJob().getJobType()).isEqualTo(AnalysisJobType.SETTING_EXTRACTION);
                    assertThat(row.sourceJob()).isSameAs(rows.getFirst().sourceJob());
                });
                assertThat(Hibernate.isInitialized(rows.getFirst().sourceJob())).isTrue();
            } finally {
                selects = SqlCapture.stop();
            }
            List<String> candidateSelects = selects.stream().filter(sql -> sql.contains(" from " + kind.table() + " ")).toList();
            assertThat(candidateSelects).isNotEmpty();
            assertThat(String.join("\n", candidateSelects)).doesNotContain("automatic_input_state", "run_base_state", "state_journal");
            // Include hydration and all metadata access in this thread's capture. SessionFactory
            // statistics are global and could include unrelated background dispatchers.
            assertThat(selects.stream().filter(sql -> sql.contains(" from analysis_jobs ") && !sql.contains(" from " + kind.table() + " ")))
                    .hasSizeLessThanOrEqualTo(1);
        });
        tx().executeWithoutResult(status -> assertThat(read(kind, f)).extracting(Row::state)
                .containsOnly(kind.state()));
    }

    @ParameterizedTest
    @EnumSource(QueryKind.class)
    @DisplayName("Worker처럼 Job을 먼저 읽은 트랜잭션에서는 후보 조회가 그 JSON을 다시 SELECT하지 않는다")
    void reusesTheAlreadyLoadedWorkerJob(QueryKind kind) {
        Fixture f = fixture(kind);
        tx().executeWithoutResult(status -> {
            em.find(AnalysisJob.class, f.job());
            List<String> selects;
            SqlCapture.start();
            try {
                List<Row> rows = read(kind, f);
                assertThat(rows).extracting(Row::id).containsExactlyElementsOf(f.expected());
                rows.forEach(row -> assertThat(row.sourceJob().getJobType()).isEqualTo(AnalysisJobType.SETTING_EXTRACTION));
            } finally {
                selects = SqlCapture.stop();
            }
            assertThat(String.join("\n", selects)).doesNotContain("automatic_input_state", "run_base_state", "state_journal");
        });
    }

    @ParameterizedTest
    @EnumSource(value = QueryKind.class, names = "CHARACTER_COMPLETED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("Job fetch를 제거한 뒤에도 비교 후보의 쓰기 잠금은 다른 트랜잭션을 차단한다")
    void retainsCandidateWriteLocks(QueryKind kind) {
        Fixture f = fixture(kind);
        tx().executeWithoutResult(status -> {
            List<Row> rows = read(kind, f);
            assertThat(rows).hasSize(3);
            assertThatThrownBy(() -> tryCompetingWrite(kind.table(), rows.getFirst().id()))
                    .isInstanceOfSatisfying(SQLException.class,
                            failure -> assertThat(failure.getSQLState()).isEqualTo("55P03"));
        });
        // The same row is writable after the repository transaction ends.
        try {
            tryCompetingWrite(kind.table(), f.expected().getFirst());
        } catch (SQLException failure) {
            throw new AssertionError("후보 잠금이 트랜잭션 종료 후에도 남았습니다", failure);
        }
    }

    private void tryCompetingWrite(String table, UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var timeout = connection.createStatement()) {
                    timeout.execute("set local lock_timeout = '150ms'");
                }
                // table is one of the two internal enum constants, never external input.
                try (var update = connection.prepareStatement("update " + table + " set updated_at=updated_at where id=?")) {
                    update.setObject(1, id);
                    assertThat(update.executeUpdate()).isEqualTo(1);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private List<Row> read(QueryKind kind, Fixture f) {
        return switch (kind) {
            case WORLD_SUBJECT -> worlds.findSubjectResolutionCandidatesForUpdate(f.job(),
                    WorldSettingReviewStatus.PENDING_REVIEW, WorldSettingComparisonStatus.PENDING).stream().map(Row::world).toList();
            case WORLD_GROUP -> worlds.findComparisonBatchCandidatesForUpdate(f.job(), f.episode(), WorldSettingCategory.LOCATION,
                    SUBJECT_KEY, WorldSettingReviewStatus.PENDING_REVIEW, WorldSettingComparisonStatus.PENDING).stream().map(Row::world).toList();
            case WORLD_BATCH -> worlds.findAllByComparisonBatchIdOrderByCreatedAtAscIdAsc(f.comparisonBatch()).stream().map(Row::world).toList();
            case CHARACTER_GROUP -> characters.findComparisonGroupCandidatesForUpdate(f.job(), f.character(),
                    SettingCandidateReviewStatus.PENDING_REVIEW, CharacterFactComparisonStatus.PENDING).stream().map(Row::character).toList();
            case CHARACTER_BATCH -> characters.findAllByCharacterComparisonBatchIdForUpdate(f.comparisonBatch()).stream().map(Row::character).toList();
            case CHARACTER_COMPLETED -> characters.findCompletedComparisonCandidates(f.job(), f.character(),
                    SettingCandidateReviewStatus.PENDING_REVIEW, CharacterFactComparisonStatus.COMPLETED).stream().map(Row::character).toList();
        };
    }

    private Fixture fixture(QueryKind kind) {
        return tx().execute(status -> {
            Member member = Member.register(UUID.randomUUID() + "@local.invalid", "test-only", null, "합성 작가");
            em.persist(member);
            Work work = Work.create(member, "비교 조회 검사", WorkGenre.FANTASY, null); em.persist(work);
            Episode episode = Episode.create(work, null, 35, "35화", "synthetic/35", "v1", "a".repeat(64), 100); em.persist(episode);
            AnalysisJob job = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
            job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
            job.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, JSON.objectNode());
            em.persist(job);
            AnalysisJob otherJob = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION); em.persist(otherJob);
            for (String field : List.of("automaticInputState", "runBaseState", "stateJournal")) {
                ReflectionTestUtils.setField(job, field, JSON.objectNode().put("synthetic", "x".repeat(100_000)));
            }
            WorkCharacter character = WorkCharacter.create(work, "합성 인물", null, null, null, null, null, null, null, null, null);
            em.persist(character);
            CharacterFactComparisonBatch characterBatch = CharacterFactComparisonBatch.createForPerson(work, episode, job, character, 3, 0);
            em.persist(characterBatch);
            WorldSettingComparisonBatch worldBatch = WorldSettingComparisonBatch.create(work, episode, job, WorldSettingCategory.LOCATION,
                    null, WorldSettingSubjectResolutionType.NEW, SUBJECT_KEY, SUBJECT, JSON.arrayNode(), 3);
            em.persist(worldBatch);
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                if (kind.world()) {
                    WorldSettingCandidate candidate = world(work, episode, job);
                    if (kind == QueryKind.WORLD_BATCH) candidate.startComparison(worldBatch, "C" + (i + 1));
                    ids.add(candidate.getId());
                } else {
                    SettingCandidate candidate = character(work, episode, job, character);
                    if (kind != QueryKind.CHARACTER_GROUP) candidate.startComparison(characterBatch, "C" + (i + 1));
                    if (kind == QueryKind.CHARACTER_COMPLETED) {
                        ReflectionTestUtils.setField(candidate, "comparisonStatus", CharacterFactComparisonStatus.COMPLETED);
                    }
                    ids.add(candidate.getId());
                }
            }
            if (kind.world()) {
                world(work, episode, otherJob);
                ReflectionTestUtils.setField(world(work, episode, job), "reviewStatus", WorldSettingReviewStatus.DISMISSED);
            } else {
                character(work, episode, otherJob, character);
                ReflectionTestUtils.setField(character(work, episode, job, character), "reviewStatus", SettingCandidateReviewStatus.DISMISSED);
            }
            em.flush();
            LocalDateTime at = LocalDateTime.of(2026, 11, 3, 14, 0);
            // Two tied timestamps exercise the stable UUID tie-breaker, not insertion order.
            for (int i = 0; i < ids.size(); i++) {
                em.createNativeQuery("update " + kind.table() + " set created_at=:at where id=:id")
                        .setParameter("at", i == 0 ? at.plusSeconds(1) : at).setParameter("id", ids.get(i)).executeUpdate();
            }
            List<UUID> expected = new ArrayList<>(ids.subList(1, 3));
            expected.sort(Comparator.comparing(UUID::toString));
            expected.add(ids.getFirst());
            return new Fixture(job.getId(), episode.getId(), character.getId(),
                    kind.world() ? worldBatch.getId() : characterBatch.getId(), List.copyOf(expected));
        });
    }

    private WorldSettingCandidate world(Work work, Episode episode, AnalysisJob job) {
        WorldSettingCandidate candidate = WorldSettingCandidate.create(work, episode, job, WorldSettingCategory.LOCATION,
                SUBJECT, "특징", "합성 값", evidence(), BigDecimal.ONE, JSON.objectNode());
        candidate.resolveSubject(WorldSettingSubjectResolutionType.NEW, SUBJECT_KEY, SUBJECT, JSON.arrayNode());
        em.persist(candidate);
        return candidate;
    }

    private SettingCandidate character(Work work, Episode episode, AnalysisJob job, WorkCharacter character) {
        SettingCandidate candidate = SettingCandidate.create(work, episode, UUID.randomUUID(), job,
                SettingEntityType.CHARACTER, "합성 인물", "나이", "17", SettingValueType.STRING,
                JSON.objectNode().put("value", "17"), evidence(), BigDecimal.ONE, JSON.objectNode());
        candidate.matchExistingCharacter(character);
        em.persist(candidate);
        return candidate;
    }

    private static JsonNode evidence() {
        return JSON.arrayNode().add(JSON.objectNode().put("quote", QUOTE).put("startOffset", 0).put("endOffset", QUOTE.length()));
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }

    enum QueryKind {
        WORLD_SUBJECT, WORLD_GROUP, WORLD_BATCH, CHARACTER_GROUP, CHARACTER_BATCH, CHARACTER_COMPLETED;
        boolean world() { return name().startsWith("WORLD"); }
        String table() { return world() ? "world_setting_candidates" : "setting_candidates"; }
        String state() {
            return switch (this) {
                case WORLD_BATCH, CHARACTER_BATCH -> "PROCESSING";
                case CHARACTER_COMPLETED -> "COMPLETED";
                default -> "PENDING";
            };
        }
    }

    private record Fixture(UUID job, UUID episode, UUID character, UUID comparisonBatch, List<UUID> expected) {}
    private record Row(UUID id, AnalysisJob sourceJob, UUID episode, JsonNode evidence, String state) {
        static Row world(WorldSettingCandidate c) {
            return new Row(c.getId(), c.getAnalysisJob(), c.getSourceEpisode().getId(), c.getEvidenceSpans(), c.getComparisonStatus().name());
        }
        static Row character(SettingCandidate c) {
            return new Row(c.getId(), c.getAnalysisJob(), c.getEpisode().getId(), c.getEvidenceSpans(), c.getComparisonStatus().name());
        }
    }
}
