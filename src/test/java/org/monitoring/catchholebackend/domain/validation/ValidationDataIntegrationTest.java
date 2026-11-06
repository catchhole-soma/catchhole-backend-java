package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.type.*;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationDataRequest;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.service.ValidationDataService;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.storage.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.docker.compose.enabled=false", "internal.api-key=gh306-synthetic-internal",
                "spring.jpa.properties.hibernate.generate_statistics=true"})
@ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
@DisplayName("GH306 고정 기준·실제 원문 읽기 경계")
class ValidationDataIntegrationTest {
    @Autowired EntityManager em;
    @Autowired AnalysisStateJournal journal;
    @Autowired ObjectStorageService hashes;
    @Autowired ValidationDataService service;
    @Autowired MockMvc mvc;
    ObjectMapper json = new ObjectMapper();
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ObjectStorage storage;
    @LocalServerPort int port;
    @Autowired PlatformTransactionManager transactions;
    Member member; Work work; Episode prior; Episode target; ValidationReport report;
    String text = "😀민수는 검을 쥐었다. 민수는 검을 쥐었다.";

    @BeforeEach void fixture() { prepareFixture(2, true); }
    void prepareFixture(int targetNo, boolean includePrior) {
        member = Member.register(UUID.randomUUID()+"@test.invalid", "test-only", null, "합성 작가"); em.persist(member);
        work = Work.create(member, "검수 자료", WorkGenre.FANTASY, "합성"); em.persist(work);
        var batch = UploadBatch.create(work, member, UploadType.SINGLE_EPISODE, UploadSourceType.FILE); em.persist(batch);
        prior = null;
        if (includePrior) { prior = Episode.create(work, null, 1, "앞 회차", "synthetic/prior", "v1", hashes.contentHash(text), 10); em.persist(prior); }
        target = Episode.create(work, null, targetNo, "대상", "synthetic/target", "v1", hashes.contentHash(text), 10); em.persist(target);
        report = ValidationReport.create(work, target, batch, "a".repeat(64)); em.persist(report);
        var seed = JsonNodeFactory.instance.objectNode().put("formatVersion", 1);
        seed.putObject("target").put("episodeId", target.getId().toString()).put("episodeNo", targetNo)
                .put("contentHash", target.getContentHash()).put("contentS3Key", target.getContentS3Key()).put("contentS3Version", "v1");
        seed.putObject("characters"); seed.putObject("worldSettings"); seed.putArray("worldHistory");
        seed.putArray("sources");
        if (includePrior) {
        var manifest = ((com.fasterxml.jackson.databind.node.ArrayNode) seed.path("sources")).addObject().put("sourceKind", "EPISODE")
                .put("episodeId", prior.getId().toString()).put("episodeNo", 1)
                .put("contentHash", prior.getContentHash()).put("contentS3Key", prior.getContentS3Key()).put("contentS3Version", "v1");
        em.persist(ValidationBaselineSource.create(report, prior, null, manifest));
        }
        report.captureSeed(seed, journal.hash(seed), "b".repeat(64));
        var entries = JsonNodeFactory.instance.arrayNode();
        entries.addObject().put("domain", "characters").put("subjectRef", "character:synthetic")
                .putObject("state").put("name", "민수").putObject("slots").putObject("STATUS:손")
                .put("factType", "STATUS").put("factKey", "손").put("factValue", "왼손 상실");
        report.baselineReady(entries);
        em.flush();
        when(storage.getText(anyString())).thenReturn(text);
    }
    ValidationDataRequest request(String operation, String source, String expectedSource, Integer start, Integer end) {
        return new ValidationDataRequest(report.getBaselineHash(), report.getSourceContentHash(), operation,
                0, 100, source, expectedSource, "v1", start, end, null);
    }
    ValidationDataRequest settings(List<String> subjectRefs) {
        return new ValidationDataRequest(report.getBaselineHash(), report.getSourceContentHash(), "SETTINGS", 0, 100, null, null, null, null, null, subjectRefs);
    }
    JsonNode read(String operation) { return service.readValidationData(member.getId(), work.getId(), report.getId(), request(operation, null, null, null, null)); }

    @Test @DisplayName("legacy report의 설정 조회는 저장 기준 항목을 읽고 현재값 조회나 추출 쓰기를 하지 않는다")
    void readsFrozenEntriesWithoutWrites() {
        assertThat(service.readValidationData(member.getId(), work.getId(), report.getId(), settings(List.of("S1")))
                .at("/settings/0/state/slots/STATUS:손/factValue").asText()).isEqualTo("왼손 상실");
        assertThat(read("INPUT").at("/sources/0/sourceRef").asText()).isEqualTo("R1");
        for (String table : List.of("analysis_jobs", "setting_candidates", "world_setting_candidates", "character_facts"))
            assertThat(jdbc.queryForObject("select count(*) from "+table, Integer.class)).isZero();
    }
    @Test @DisplayName("#381 ENTRIES 조회는 없어졌고 요청하면 400이다")
    void entriesOperationIsRemoved() throws Exception {
        assertThatThrownBy(() -> read("ENTRIES")).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class)
                .hasFieldOrPropertyWithValue("resultCode", org.monitoring.catchholebackend.global.exception.CommonErrorCode.REQUEST_VALIDATION_FAILED);
        mvc.perform(post("/api/internal/v1/works/"+work.getId()+"/validation-reports/"+report.getId()+"/data")
                .header("X-Internal-Api-Key", "gh306-synthetic-internal").header("X-Validation-Member-Id", member.getId()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request("ENTRIES", null, null, null, null)))).andExpect(status().isBadRequest());
    }

    @Autowired org.monitoring.catchholebackend.domain.validation.service.ValidationBaselineService baselines;
    /**
     * #381 live report: legacy fixture report를 퇴역(current=false)시키고 같은 회차의 다음 revision으로 seed 없는 report를 실제 접수 준비로 만든다.
     * 첫 인물은 envelope 이전 raw snapshot과 근거 Fact·확정 인물 발견 별칭, 세계관 하나는 확정 근거 후보를 가진다.
     */
    ValidationReport live(int characters) {
        org.springframework.test.util.ReflectionTestUtils.setField(report, "current", false); em.flush();
        var job = org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.create(work, null, prior, org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION); em.persist(job);
        for (int i = 0; i < characters; i++) {
            var profile = JsonNodeFactory.instance.objectNode(); profile.putObject("직업").put("value", "용병");
            var character = org.monitoring.catchholebackend.domain.character.entity.WorkCharacter.create(work, "인물" + i, null, 20 + i, 1, profile, null, null, null, null, null);
            em.persist(character);
            if (i > 0) continue;
            var candidate = org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.create(work, prior, null, job,
                    org.monitoring.catchholebackend.domain.character.type.SettingEntityType.CHARACTER, character.getName(), character.getName(), character.getId(),
                    org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.MATCHED, "직업", "용병단장",
                    org.monitoring.catchholebackend.domain.character.type.SettingValueType.STRING, null, JsonNodeFactory.instance.arrayNode(), java.math.BigDecimal.ONE, null);
            em.persist(candidate);
            org.springframework.test.util.ReflectionTestUtils.setField(candidate, "reviewStatus", org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CONFIRMED);
            var fact = org.monitoring.catchholebackend.domain.character.entity.CharacterFact.create(character, candidate,
                    org.monitoring.catchholebackend.domain.character.type.CharacterFactType.PROFILE, "직업", "용병단장", "용병단장", null, prior, null, job, java.math.BigDecimal.ONE, 1);
            em.persist(fact);
            em.persist(org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource.create(character,
                    org.monitoring.catchholebackend.domain.character.type.CharacterFactType.PROFILE, "직업", fact, 0));
            var discovery = org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.createCharacterDiscovery(work, prior, null, job, "첫째", "첫째", character.getId(),
                    org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.MATCHED, JsonNodeFactory.instance.arrayNode(), java.math.BigDecimal.ONE, null);
            em.persist(discovery);
            org.springframework.test.util.ReflectionTestUtils.setField(discovery, "reviewStatus", org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CONFIRMED);
        }
        var world = org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.create(work,
                org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE, "엘프", "서식지", "숲"); em.persist(world);
        var history = org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(work, prior, job,
                org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE, "엘프", "서식지", "숲", JsonNodeFactory.instance.arrayNode(), java.math.BigDecimal.ONE, null);
        org.springframework.test.util.ReflectionTestUtils.setField(history, "targetWorldSetting", world);
        org.springframework.test.util.ReflectionTestUtils.setField(history, "reviewStatus", org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.CONFIRMED);
        em.persist(history);
        // 같은 회차의 다음 revision(재검수와 같은 번호 규칙)이라 PostgreSQL의 (episode, revision) unique를 지킨다.
        var live = ValidationReport.recheck(report, report.getBatch(), "d".repeat(64), null); em.persist(live);
        baselines.prepare(live); em.flush();
        assertThat(live.getBaselineSeed()).isNull(); assertThat(live.getBaselineHash()).isNotNull();
        return live;
    }
    ValidationDataRequest liveRequest(ValidationReport live, String operation, List<String> subjectRefs) {
        return new ValidationDataRequest(live.getBaselineHash(), live.getSourceContentHash(), operation, 0, 100, null, null, null, null, null, subjectRefs);
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints = {2, 20})
    @DisplayName("#381 live SETTINGS는 요청 주체만 id로 읽고 후보·Fact·Job 엔티티를 읽지 않으며 SQL 수가 작품 크기와 무관하다")
    void liveSettingsReadOnlyRequestedSubjectsWithConstantStatements(int characters) {
        var live = live(characters); UUID liveId = live.getId();
        var subjects = service.readValidationData(member.getId(), work.getId(), liveId, liveRequest(live, "SUBJECTS", null));
        assertThat(subjects.path("subjectCount").asInt()).isEqualTo(characters + 1);
        assertThat(subjects.path("subjects").findValuesAsText("name")).contains("인물0", "엘프");
        var refs = new ArrayList<String>(); subjects.path("subjects").forEach(row -> refs.add(row.path("subject_ref").asText()));
        var request = liveRequest(live, "SETTINGS", refs);
        em.flush(); em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.clear();

        var result = service.readValidationData(member.getId(), work.getId(), liveId, request);

        java.util.function.Function<Class<?>, Long> loads = type -> statistics.getEntityStatistics(type.getName()).getLoadCount();
        assertThat(loads.apply(org.monitoring.catchholebackend.domain.character.entity.WorkCharacter.class)).isEqualTo(characters);
        assertThat(loads.apply(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.class)).isEqualTo(1);
        for (Class<?> forbidden : List.of(org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.class,
                org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.class,
                org.monitoring.catchholebackend.domain.character.entity.CharacterFact.class,
                org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource.class,
                org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.class))
            assertThat(loads.apply(forbidden)).as(forbidden.getSimpleName()).isZero();
        // 19 = 앞뒤 현재성 확인(lease 1·report scalar 1·fingerprint 2)×2 + report 1 + 출처 목록 1 + 색인 3 + 인물 id 1 + raw 근거 표시값 1 + 세계관 id 1 + 최신 출처 회차 1 + 정정 1.
        // 인물 2명이든 20명이든 같다.
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(LIVE_SETTINGS_STATEMENTS);
        var rows = result.path("settings");
        assertThat(rows).hasSize(characters + 1);
        assertThat(rows.toString()).contains("\"factValue\":\"용병단장\"", "\"aliases\":[\"첫째\"]", "\"latestSourceEpisodeNo\":1", "\"서식지\":\"숲\"");
        assertThat(rows.toString()).doesNotContain("historyFacts", "provenance");
        assertThat(result.path("authorCorrectionProofs")).isEmpty();
    }
    static final long LIVE_SETTINGS_STATEMENTS = 19;

    @Test @DisplayName("#381 live report는 잠금을 우회해 확정 설정 version을 바꾼 쓰기 뒤 모든 자료 읽기를 409로 멈춘다(Worker는 INPUT_CHANGED)")
    void liveReadStopsAfterBypassingVersionBump() {
        var live = live(1);
        assertThat(service.readValidationData(member.getId(), work.getId(), live.getId(), liveRequest(live, "INPUT", null)).path("entryCount").asInt()).isEqualTo(2);
        jdbc.update("update world_settings set version = version + 1 where work_id = ?", work.getId());
        for (String operation : List.of("INPUT", "SUBJECTS", "SETTINGS", "VALIDATE"))
            assertThatThrownBy(() -> service.readValidationData(member.getId(), work.getId(), live.getId(), liveRequest(live, operation, operation.equals("SETTINGS") ? List.of("S1") : null)))
                    .as(operation).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class)
                    .hasFieldOrPropertyWithValue("resultCode", org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode.VALIDATION_SOURCE_VERSION_CONFLICT);
    }

    @Test @DisplayName("#381 live SETTINGS도 모르는 주체·100개 초과·빈 요청을 거절한다")
    void liveSettingsValidatesSubjectRefs() {
        var live = live(1);
        for (var refs : List.of(List.of("S3"), List.of("S1", "S99"), List.<String>of()))
            assertThatThrownBy(() -> service.readValidationData(member.getId(), work.getId(), live.getId(), liveRequest(live, "SETTINGS", refs))).as(refs.toString())
                    .isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class)
                    .hasFieldOrPropertyWithValue("resultCode", org.monitoring.catchholebackend.global.exception.CommonErrorCode.REQUEST_VALIDATION_FAILED);
        var tooMany = new ArrayList<String>(); for (int i = 1; i <= 101; i++) tooMany.add("S" + i);
        assertThatThrownBy(() -> service.readValidationData(member.getId(), work.getId(), live.getId(), liveRequest(live, "SETTINGS", tooMany)))
                .isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
        assertThatThrownBy(() -> service.readValidationData(member.getId(), work.getId(), live.getId(), liveRequest(live, "SETTINGS", null)))
                .isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
        assertThat(service.readValidationData(member.getId(), work.getId(), live.getId(), liveRequest(live, "SETTINGS", List.of("S2", "S1"))).path("settings"))
                .extracting(row -> row.path("entry_ref").asText()).containsExactly("B1", "B2");
    }
    @Test @DisplayName("SUBJECTS는 주체 목록을 한 번에, SETTINGS는 요청 주체의 현재값 행만 공통 fixture와 같게 준다(grid v4)")
    void subjectsAndCurrentSettingsFollowTheSharedVector() throws Exception {
        var vector=json.readTree(java.nio.file.Path.of("docs/contracts/validation-execution-v1.json").toFile()).path("execution").path("subjectCatalogueVector");
        var entries=(com.fasterxml.jackson.databind.node.ArrayNode)vector.path("entries").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)entries.path(0).path("state")).putArray("explicitAuthorCorrections").addObject().put("correctionId","synthetic-correction");
        org.springframework.test.util.ReflectionTestUtils.setField(report,"baselineEntries",entries);em.flush();
        var subjects=read("SUBJECTS");
        assertThat(subjects.path("projectionVersion").asInt()).isEqualTo(2);
        assertThat(subjects.has("clueVersion")).isFalse();
        assertThat(subjects.path("subjects")).isEqualTo(vector.path("subjects"));
        assertThat(subjects.path("subjectCount").asInt()).isEqualTo(vector.path("subjectCount").asInt());
        assertThat(subjects.path("entryCount").asInt()).isEqualTo(vector.path("entryCount").asInt());
        for(var request:vector.path("settingsRequests")) {
            var refs=new ArrayList<String>();request.path("subjectRefs").forEach(ref->refs.add(ref.asText()));
            var result=service.readValidationData(member.getId(),work.getId(),report.getId(),settings(refs));
            var actual=new ArrayList<String>();result.path("settings").forEach(row->{actual.add(row.path("entry_ref").asText());assertThat(row.path("domain").asText()).isNotEqualTo("worldHistory");assertThat(row.path("author_correction").isNull()).isTrue();});
            var expected=new ArrayList<String>();request.path("entryRefs").forEach(ref->expected.add(ref.asText()));
            assertThat(actual).as(refs.toString()).isEqualTo(expected);
            assertThat(result.path("authorCorrectionProofs").size()).isEqualTo(actual.contains("B1")?1:0);
        }
        for(var invalid:vector.path("invalidSettingsRequests")) {
            var refs=new ArrayList<String>();invalid.forEach(ref->refs.add(ref.asText()));
            assertThatThrownBy(()->service.readValidationData(member.getId(),work.getId(),report.getId(),settings(refs))).as(refs.size()+" refs")
                    .isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class).hasFieldOrPropertyWithValue("resultCode",org.monitoring.catchholebackend.global.exception.CommonErrorCode.REQUEST_VALIDATION_FAILED);
        }
        assertThatThrownBy(()->service.readValidationData(member.getId(),work.getId(),report.getId(),settings(null))).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
    }
    @Test @DisplayName("전체 대상 원문 읽기는 더 이상 기계 단서(modelClues)를 계산하지 않는다")
    void fullTargetReadHasNoModelClues() {
        var result=service.readValidationData(member.getId(),work.getId(),report.getId(),request("READ_SOURCE","T1",target.getContentHash(),null,null));
        assertThat(result.path("status").asText()).isEqualTo("READY");
        assertThat(result.has("modelClues")).isFalse();assertThat(result.has("clueVersion")).isFalse();
    }
    @Test @DisplayName("반복 인용·이모지의 Unicode code point 위치를 정확히 읽는다")
    void readsExactUnicodeSlice() {
        var result = service.readValidationData(member.getId(), work.getId(), report.getId(), request("READ_SOURCE", "T1", target.getContentHash(), 1, 3));
        assertThat(result.path("status").asText()).isEqualTo("READY");
        assertThat(result.path("text").asText()).isEqualTo("민수");
        assertThat(result.path("start").asInt()).isEqualTo(1);
        assertThat(result.path("totalCodePoints").asInt()).isEqualTo(24);
    }
    @Test @DisplayName("읽는 동안의 교체는 이전 본문을 정상 결과로 반환하지 않는다")
    void replacementDuringReadInvalidatesResponse() {
        when(storage.getText("synthetic/prior")).thenAnswer(call -> {
            jdbc.update("update episodes set content_hash = ? where id = ?", "c".repeat(64), prior.getId());
            return text;
        });
        var result = service.readValidationData(member.getId(), work.getId(), report.getId(), request("READ_SOURCE", "R1", prior.getContentHash(), 0, 2));
        assertThat(result.path("status").asText()).isEqualTo("SOURCE_CHANGED");
        assertThat(result.hasNonNull("text")).isFalse();
    }
    @Test @DisplayName("필수 원문 장애는 빈 성공 결과와 구별한다")
    void unavailableSourceIsExplicit() {
        when(storage.getText(anyString())).thenThrow(new IllegalStateException("private-storage-detail"));
        var result = service.readValidationData(member.getId(), work.getId(), report.getId(), request("READ_SOURCE", "R1", prior.getContentHash(), 0, 2));
        assertThat(result.path("status").asText()).isEqualTo("SOURCE_UNREADABLE");
        assertThat(result.toString()).doesNotContain("private-storage-detail");
    }
    @Test @DisplayName("다른 소유권·없는 참조·틀린 fingerprint는 허용하지 않는다")
    void rejectsForgedScopeAndSource() {
        assertThatThrownBy(() -> service.readValidationData(member.getId()+1, work.getId(), report.getId(), request("INPUT", null, null, null, null))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.readValidationData(member.getId(), work.getId(), report.getId(), request("READ_SOURCE", "R99", prior.getContentHash(), 0, 2))).isInstanceOf(RuntimeException.class);
        var result = service.readValidationData(member.getId(), work.getId(), report.getId(), request("READ_SOURCE", "R1", "c".repeat(64), 0, 2));
        assertThat(result.path("status").asText()).isEqualTo("SOURCE_CHANGED");
    }
    @Test @DisplayName("내부 read endpoint는 API key 없이 원문을 노출하지 않는다")
    void internalAuthenticationIsRequired() throws Exception {
        mvc.perform(post("/api/internal/v1/works/"+work.getId()+"/validation-reports/"+report.getId()+"/data")
                .header("X-Validation-Member-Id", member.getId()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request("INPUT", null, null, null, null))))
                .andExpect(status().isUnauthorized());
    }

    @Test @DisplayName("첫 회차는 추출하지 않은 설정집의 실제 고정 revision을 읽는다")
    void firstEpisodeReadsUnanalyzedSettingBookRevision() {
        prepareFixture(1, false);
        var book=org.monitoring.catchholebackend.domain.upload.entity.UploadFile.create(report.getBatch(), UploadFileRole.SETTING_BOOK,
                "설정집.txt", "text/plain", "s3://synthetic/original", 20); em.persist(book);
        var revision=org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision.create(book, 1,
                new StoredTextObject("synthetic/book", "v1", hashes.contentHash(text), 24), SettingBookReferenceTimeKind.WORK_START, null);
        em.persist(revision); book.linkCurrentRevision(revision);
        var seed=(com.fasterxml.jackson.databind.node.ObjectNode)report.getBaselineSeed().deepCopy();
        var manifest=((com.fasterxml.jackson.databind.node.ArrayNode)seed.path("sources")).addObject()
                .put("sourceKind", "SETTING_BOOK").put("settingBookId", book.getId().toString()).put("revisionId", revision.getId().toString())
                .put("revisionNo", 1).put("contentHash", revision.getContentHash()).put("contentS3Key", revision.getContentS3Key())
                .put("contentS3Version", "v1").put("referenceTimeKind", "WORK_START").putNull("referenceEpisodeNo");
        org.springframework.test.util.ReflectionTestUtils.setField(report, "baselineSeed", seed);
        org.springframework.test.util.ReflectionTestUtils.setField(report, "baselineHash", journal.hash(seed));
        em.persist(ValidationBaselineSource.create(report, null, revision, manifest)); em.flush();
        var input=read("INPUT");
        assertThat(input.at("/target/episodeNo").asInt()).isEqualTo(1);
        assertThat(input.at("/sources/0/sourceKind").asText()).isEqualTo("SETTING_BOOK");
        assertThat(input.at("/sources/0").has("episodeNo")).isFalse();
        var result=service.readValidationData(member.getId(), work.getId(), report.getId(), request("READ_SOURCE", "R1", revision.getContentHash(), 1, 3));
        assertThat(result.path("text").asText()).isEqualTo("민수");
        assertThat(jdbc.queryForObject("select count(*) from analysis_jobs", Integer.class)).isZero();
    }

    @Test @DisplayName("읽는 동안 기준 무효화·원문 파기를 정상 결과로 반환하지 않는다")
    void baselineInvalidationAndDeletionDuringReadAreRejected() {
        when(storage.getText("synthetic/prior")).thenAnswer(call -> {
            jdbc.update("update validation_reports set status='INVALIDATED',baseline_status='INVALIDATED',baseline_hash=null,baseline_seed=null,baseline_entries=null where id=?", report.getId());
            return text;
        });
        assertThatThrownBy(() -> service.readValidationData(member.getId(), work.getId(), report.getId(), request("READ_SOURCE", "R1", prior.getContentHash(), 0, 2)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test @DisplayName("실제 Java HTTP 응답을 Python 고정 원문 adapter가 읽는다")
    @EnabledIfEnvironmentVariable(named="GH306_AI_CHECKOUT", matches=".+")
    void actualPythonHttpAdapterReadsFrozenSource() throws Exception {
        var memberId=member.getId(); var workId=work.getId();
        var reportId=report.getId(); var baseline=report.getBaselineHash(); var hash=target.getContentHash();
        TestTransaction.flagForCommit(); TestTransaction.end();
        try {
            String checkout=System.getenv("GH306_AI_CHECKOUT");
            String python=System.getenv("GH306_PYTHON");
            assertThat(python).isNotBlank();
            var builder=new ProcessBuilder(python, checkout+"/tests/support/validation_data_http_client.py",
                    "http://127.0.0.1:"+port, memberId.toString(), workId.toString(), reportId.toString(), baseline, hash);
            builder.environment().put("PYTHONPATH", checkout);
            builder.directory(new java.io.File(checkout)); builder.redirectErrorStream(true);
            var process=builder.start();
            assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String output=new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            assertThat(output).contains("GH306 actual Java-Python HTTP contract passed");
        } finally {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update("delete from validation_baseline_sources where report_id=?", reportId);
                jdbc.update("delete from validation_reports where id=?", reportId);
                jdbc.update("delete from episodes where work_id=?", workId);
                jdbc.update("delete from upload_batches where work_id=?", workId);
                jdbc.update("delete from works where id=?", workId);
                jdbc.update("delete from members where id=?", memberId);
            });
        }
    }
}
