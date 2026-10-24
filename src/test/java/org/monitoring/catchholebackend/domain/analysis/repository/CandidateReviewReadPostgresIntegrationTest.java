package org.monitoring.catchholebackend.domain.analysis.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFactComparisonBatch;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.worldsetting.type.*;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonBatch;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecision;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.support.CandidateReviewApiFixture;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=org.monitoring.catchholebackend.domain.analysis.repository.CandidateReviewReadPostgresIntegrationTest$SqlCapture"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DisplayName("PostgreSQL 후보 검토의 전체 HTTP 읽기 경계")
class CandidateReviewReadPostgresIntegrationTest extends CandidateReviewApiFixture {
    @Autowired CandidateReviewMetadataRepository metadata;
    @Autowired org.monitoring.catchholebackend.domain.analysis.service.CandidateReviewSourceScope sources;
    @Autowired org.monitoring.catchholebackend.domain.character.mapper.SettingCandidateMapper characterMapper;
    @Autowired org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingMapper worldMapper;
    @Autowired org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository characters;
    @Autowired org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository worlds;

    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_DB", "candidate_review_test").withEnv("POSTGRES_USER", "candidate_review_test")
            .withEnv("POSTGRES_PASSWORD", "disposable-candidate-review-test-only").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2).withStartupTimeout(Duration.ofMinutes(1)));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/candidate_review_test");
        properties.add("spring.datasource.username", () -> "candidate_review_test");
        properties.add("spring.datasource.password", () -> "disposable-candidate-review-test-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.docker.compose.enabled", () -> "false");
    }
    public static class SqlCapture implements StatementInspector {
        static final List<String> statements = new ArrayList<>();
        @Override public String inspect(String sql) { statements.add(sql); return sql; }
    }
    @ParameterizedTest
    @ValueSource(strings = {"EPISODE", "SETTING_BOOK", "MANUAL"})
    @DisplayName("메타데이터 매핑은 비교 revision·고정 미리보기·현재 대상·출처를 포함한 전체 DTO와 같다")
    void metadataMappingPreservesWholeCandidateResponse(String kind) {
        var origin = kind.equals("SETTING_BOOK") ? bookJob() : kind.equals("EPISODE") ? completedJob(3) : job(3);
        if (kind.equals("MANUAL")) ReflectionTestUtils.setField(origin, "status", org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.SUCCEEDED);
        var character = comparedCharacter(origin); var world = comparedWorld(origin); em.flush();
        var expectedCharacter = characterMapper.toReviewListResponse(character, false, null,
                org.monitoring.catchholebackend.domain.character.processor.SettingCandidateValueValidation.valid(), "나이");
        var expectedWorld = worldMapper.toCandidateResponse(world);
        assertThat(expectedCharacter.comparisonRevision()).isEqualTo("e".repeat(64));
        assertThat(expectedCharacter.snapshotChanges().getFirst().beforeFactValue()).isEqualTo(kind.equals("MANUAL") ? null : "35");
        assertThat(expectedWorld.currentWorldSettingVersion()).isEqualTo(0);
        var sourceId = origin.getId(); em.flush(); em.clear(); SqlCapture.statements.clear();
        var stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics(); stats.clear();
        var source = sources.validateRead(work, batch.getId(), sourceId);
        var readCharacters = characters.findReviewListCandidates(work.getId(), batch.getId(), null,
                java.util.EnumSet.allOf(SettingCandidateMatchStatus.class), sourceId);
        var readWorlds = worlds.findReviewListWithoutJobs(work.getId(), batch.getId(), null, null, null, null, sourceId);
        var ids = Set.of(sourceId, readCharacters.getFirst().getCharacterComparisonBatch().getAnalysisJob().getId());
        var context = sources.readMetadata(ids, source);
        assertThat(characterMapper.toReviewListResponse(readCharacters.getFirst(), false, null,
                org.monitoring.catchholebackend.domain.character.processor.SettingCandidateValueValidation.valid(), "나이", context)).isEqualTo(expectedCharacter);
        assertThat(worldMapper.toCandidateResponse(readWorlds.getFirst(), context)).isEqualTo(expectedWorld);
        assertThat(stats.getEntityStatistics(AnalysisJob.class.getName()).getLoadCount()).isZero();
        assertThat(String.join("\n", SqlCapture.statements).toLowerCase()).doesNotContain("automatic_input_state", "run_base_state", "state_journal");
    }

    @Test
    @DisplayName("기존 getter의 확정 시점·검토 완료·비실패 분기는 Job proxy를 초기화하지 않는다")
    void existingPredicateGuardsDoNotLoadUnneededJobs() {
        var origin = bookJob(); var character = character(origin, "아리아"); var world = world(origin, "북부");
        world.recordTemporalScope(CharacterFactTemporalScope.PRESENT, null);
        ReflectionTestUtils.setField(character, "reviewStatus", SettingCandidateReviewStatus.DISMISSED);
        ReflectionTestUtils.setField(world, "reviewStatus", WorldSettingReviewStatus.DISMISSED);
        em.flush(); em.clear();
        var readCharacter = em.find(SettingCandidate.class, character.getId());
        var readWorld = em.find(WorldSettingCandidate.class, world.getId());
        SqlCapture.statements.clear(); var stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics(); stats.clear();
        assertThat(readWorld.getTemporalScope()).isEqualTo(CharacterFactTemporalScope.PRESENT);
        assertThat(readWorld.isManualReviewAvailable()).isFalse();
        assertThat(readWorld.isAutomaticApplicationPending()).isFalse();
        assertThat(readCharacter.isAutomaticApplicationPending()).isFalse();
        assertThat(readCharacter.canDeferFailedComparison()).isFalse();
        assertThat(stats.getEntityStatistics(AnalysisJob.class.getName()).getLoadCount()).isZero();
        assertThat(SqlCapture.statements).isEmpty();
    }

    @Test
    @DisplayName("과거 후보의 상세 매핑은 source ID만 필요한 회차 본문을 읽지 않는다")
    void detailMappingDoesNotReadUnneededJobEpisode() {
        var origin = job(7);
        var candidate = SettingCandidate.create(work, null, UUID.randomUUID(), origin, SettingEntityType.CHARACTER,
                "아리아", "age", "17", SettingValueType.STRING, null, JsonNodeFactory.instance.arrayNode(), new java.math.BigDecimal("1.0000"), null);
        em.persist(candidate); em.flush(); em.clear();
        em.find(AnalysisJob.class, origin.getId()); var read = em.find(SettingCandidate.class, candidate.getId());
        SqlCapture.statements.clear(); var stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics(); stats.clear();
        var response = characterMapper.toReviewListResponse(read, false, null,
                org.monitoring.catchholebackend.domain.character.processor.SettingCandidateValueValidation.valid(), "나이");
        assertThat(response.episodeId()).isNull();
        assertThat(response.source().episodeNo()).isEqualTo(7);
        assertThat(stats.getEntityStatistics(org.monitoring.catchholebackend.domain.episode.entity.Episode.class.getName()).getLoadCount()).isZero();
        assertThat(SqlCapture.statements).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_REVIEW", "CONFIRMED", "DISMISSED"})
    @DisplayName("상세 매핑은 검토 가능한 설정집 후보에만 최신 Job을 조회한다")
    void detailMappingChecksLatestBookOriginOnlyForManualReview(String reviewStatus) {
        var origin = bookJob(); var character = character(origin, "아리아"); var world = world(origin, "북부");
        ReflectionTestUtils.setField(character, "comparisonStatus", CharacterFactComparisonStatus.COMPLETED);
        ReflectionTestUtils.setField(character, "suggestedOperation", CharacterFactOperation.REVIEW_REQUIRED);
        ReflectionTestUtils.setField(world, "comparisonStatus", WorldSettingComparisonStatus.COMPLETED);
        ReflectionTestUtils.setField(character, "reviewStatus", SettingCandidateReviewStatus.valueOf(reviewStatus));
        ReflectionTestUtils.setField(world, "reviewStatus", WorldSettingReviewStatus.valueOf(reviewStatus));
        em.flush(); SqlCapture.statements.clear();
        var characterResponse = characterMapper.toReviewListResponse(character, false, null,
                org.monitoring.catchholebackend.domain.character.processor.SettingCandidateValueValidation.valid(), "나이");
        var worldResponse = worldMapper.toCandidateResponse(world);
        boolean pending = reviewStatus.equals("PENDING_REVIEW");
        assertThat(characterResponse.manualReviewAvailable()).isEqualTo(pending);
        assertThat(worldResponse.manualReviewAvailable()).isEqualTo(pending);
        assertThat(SqlCapture.statements.stream().anyMatch(sql -> sql.toLowerCase().contains("from analysis_jobs"))).isEqualTo(pending);
    }

    @Test
    @DisplayName("null 원문 버전의 동등성과 변경·보관·최신 origin 동률 순서를 보존한다")
    void preservesNullVersionCurrentSourceAndLatestOrigin() {
        var nullVersionEpisode = org.monitoring.catchholebackend.domain.episode.entity.Episode.create(work, null, 3, "3화", "synthetic/3", null, "a".repeat(64), 100); em.persist(nullVersionEpisode);
        var episodeOrigin = AnalysisJob.create(work, batch, nullVersionEpisode, org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION);
        episodeOrigin.configureReviewMode(org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC); complete(episodeOrigin); em.persist(episodeOrigin);
        var book = bookJob(); em.flush();
        assertThat(metadata.findByIds(List.of(episodeOrigin.getId(), book.getId())))
                .allSatisfy(row -> assertThat(row.currentSourceVersion()).isTrue());
        ReflectionTestUtils.setField(episodeOrigin.getEpisode(), "contentS3Version", "changed");
        ReflectionTestUtils.setField(book.getSourceSettingBook(), "archivedAt", java.time.LocalDateTime.now()); em.flush();
        assertThat(metadata.findByIds(List.of(episodeOrigin.getId(), book.getId())))
                .allSatisfy(row -> assertThat(row.currentSourceVersion()).isFalse());
        ReflectionTestUtils.setField(book.getSourceSettingBook(), "archivedAt", null);
        var revision = org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision.create(book.getSourceSettingBook(), 2,
                new org.monitoring.catchholebackend.global.storage.StoredTextObject("synthetic/book-2", null, "c".repeat(64), 100)); em.persist(revision);
        book.getSourceSettingBook().linkCurrentRevision(revision); em.flush();
        assertThat(metadata.findByIds(List.of(book.getId())).getFirst().currentSourceVersion()).isFalse();
        book.getSourceSettingBook().linkCurrentRevision(book.getSourceSettingBookRevision()); em.flush();
        var later = AnalysisJob.createSettingBook(work, book.getSourceSettingBook(), book.getSourceSettingBookRevision()); complete(later); em.persist(later); em.flush();
        em.createNativeQuery("update analysis_jobs set created_at=:at where id in (:first,:second)")
                .setParameter("at", java.time.LocalDateTime.of(2026, 10, 25, 0, 0)).setParameter("first", book.getId()).setParameter("second", later.getId()).executeUpdate();
        UUID latest = book.getId().toString().compareTo(later.getId().toString()) > 0 ? book.getId() : later.getId();
        for (var row : metadata.findByIds(List.of(book.getId(), later.getId()))) {
            assertThat(row.currentSourceVersion()).isTrue();
            assertThat(row.latestOrigin()).isEqualTo(row.id().equals(latest));
            assertThat(row.actionable()).isEqualTo(row.id().equals(latest));
        }
        SqlCapture.statements.clear(); assertThat(metadata.findByIds(List.of())).isEmpty(); assertThat(SqlCapture.statements).isEmpty();
    }

    private SettingCandidate comparedCharacter(AnalysisJob origin) {
        var candidate = character(origin, "아리아");
        var target = WorkCharacter.create(work, "아리아", null, null, null, null, null, null, null, null, null); em.persist(target);
        candidate.matchExistingCharacter(target); ReflectionTestUtils.setField(candidate, "matchedCharacter", target);
        var comparison = AnalysisJob.createCharacterFactComparison(candidate, "e".repeat(64)); em.persist(comparison);
        var comparisonBatch = CharacterFactComparisonBatch.createForPerson(work, origin.getEpisode(), comparison, target, 1, 0); em.persist(comparisonBatch);
        ReflectionTestUtils.setField(candidate, "characterComparisonBatch", comparisonBatch);
        ReflectionTestUtils.setField(candidate, "characterComparisonCandidateRef", "C1");
        ReflectionTestUtils.setField(candidate, "comparisonStatus", CharacterFactComparisonStatus.COMPLETED);
        ReflectionTestUtils.setField(candidate, "suggestedOperation", CharacterFactOperation.UPDATE);
        ReflectionTestUtils.setField(candidate, "comparisonTargetFactType", CharacterFactType.STAT);
        ReflectionTestUtils.setField(candidate, "comparisonTargetFactKey", "stats.mental");
        ReflectionTestUtils.setField(candidate, "proposedFactValue", "36");
        ReflectionTestUtils.setField(candidate, "rawComparisonJson", JsonNodeFactory.instance.objectNode().set("backendComparisonBefore",
                JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("factType", "STAT").put("factKey", "stats.mental").put("factValue", "35"))));
        return candidate;
    }

    private WorldSettingCandidate comparedWorld(AnalysisJob origin) {
        var candidate = world(origin, "북부");
        candidate.recordTemporalScope(CharacterFactTemporalScope.PRESENT, null);
        var target = WorldSetting.create(work, WorldSettingCategory.LOCATION, "북부", "특징", "이전 값"); em.persist(target);
        var actualIds = JsonNodeFactory.instance.arrayNode().add(target.getId().toString());
        var comparisonBatch = origin.isOrderedProvisional()
                ? WorldSettingComparisonBatch.createOrdered(work, origin.getEpisode(), origin, WorldSettingCategory.LOCATION,
                        null, WorldSettingSubjectResolutionType.EXISTING, "LOCATION|북부", "북부", actualIds, JsonNodeFactory.instance.arrayNode(), 1)
                : WorldSettingComparisonBatch.create(work, origin.getEpisode(), origin, WorldSettingCategory.LOCATION,
                        null, WorldSettingSubjectResolutionType.EXISTING, "LOCATION|북부", "북부", actualIds, 1);
        em.persist(comparisonBatch);
        var decision = WorldSettingComparisonDecision.create(comparisonBatch, "decision-1", "북부", target, null, "특징",
                WorldSettingConsolidationStatus.SINGLE, WorldSettingSuggestedOperation.UPDATE, null, null, "특징", "이전 값", "합성 값", "합성 비교", null); em.persist(decision);
        ReflectionTestUtils.setField(candidate, "comparisonBatch", comparisonBatch); ReflectionTestUtils.setField(candidate, "comparisonDecision", decision);
        ReflectionTestUtils.setField(candidate, "comparisonCandidateRef", "C1");
        ReflectionTestUtils.setField(candidate, "targetWorldSetting", target); ReflectionTestUtils.setField(candidate, "comparisonStatus", WorldSettingComparisonStatus.COMPLETED);
        return candidate;
    }

    @ParameterizedTest
    @CsvSource({"character,false,false,false", "character,true,false,false", "character,false,false,true", "character,false,true,true", "character,true,true,true", "world,false,false,false", "world,false,false,true", "world,false,true,true", "summary,false,false,false", "summary,false,false,true", "summary,false,true,true"})
    @DisplayName("목록·호환 페이지·설정집 출처·요약 모두 Job JSON을 읽지 않는다")
    void listAndSummaryDoNotLoadAnalysisJobJson(String kind, boolean legacy, boolean book, boolean scoped) throws Exception {
        var origin = book ? bookJob() : completedJob(3);
        comparedCharacter(origin); comparedWorld(origin);
        em.flush(); em.clear(); SqlCapture.statements.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics(); statistics.clear();
        String url = switch (kind) { case "character" -> characterUrl(); case "world" -> worldUrl(); default -> worldUrl() + "/summary"; };
        var response = mvc.perform(request(url, scoped ? origin.getId() : null).queryParam("includeLegacyCandidates", Boolean.toString(legacy)))
                .andExpect(status().isOk()).andReturn();
        var data = json.readTree(response.getResponse().getContentAsString()).path("data");
        assertThat(data.path("totalCandidateCount").asLong()).isEqualTo(1);
        assertThat(SqlCapture.statements).isNotEmpty();
        assertThat(String.join("\n", SqlCapture.statements).toLowerCase()).doesNotContain("automatic_input_state", "run_base_state", "state_journal");
        assertThat(statistics.getEntityStatistics(AnalysisJob.class.getName()).getLoadCount()).isZero();
        if (kind.equals("summary")) {
            assertThat(statistics.getEntityStatistics(SettingCandidate.class.getName()).getLoadCount()).isZero();
            assertThat(statistics.getEntityStatistics(WorldSettingCandidate.class.getName()).getLoadCount()).isZero();
            assertThat(String.join("\n", SqlCapture.statements).toLowerCase()).doesNotContain("raw_extraction_json", "evidence_spans");
        }
    }
}
