package org.monitoring.catchholebackend.domain.settingtool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.character.entity.*;
import org.monitoring.catchholebackend.domain.character.processor.*;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.settingtool.dto.*;
import org.monitoring.catchholebackend.domain.settingtool.service.SettingToolService;
import org.monitoring.catchholebackend.domain.settingtool.type.SettingTool;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.*;
import org.monitoring.catchholebackend.domain.worldsetting.type.*;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@SpringBootTest(properties = "internal.api-key=setting-tool-test-key")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("설정 조회 도구의 권한·현재값·근거 계약")
class SettingToolIntegrationTest {
    @Autowired EntityManager em;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @Autowired SettingToolService tools;
    @Autowired CharacterSnapshotAccessor snapshots;
    @Autowired CharacterSnapshotSourceManager sources;
    @MockitoBean ObjectStorageService storage;
    private final ObjectMapper json = new ObjectMapper();
    private final JsonNodeFactory nodes = JsonNodeFactory.instance;
    private Member member;
    private Work work;
    private Work otherWork;
    private WorkCharacter character;
    private Episode episode;
    private AnalysisJob job;
    private String token;
    private static final String TEXT = "😀 마력은 10이다. 다음 문장이다.";
    private static final CharacterSnapshotSlot SLOT = new CharacterSnapshotSlot(CharacterFactType.STAT, "stats.mana");

    @org.springframework.test.context.transaction.BeforeTransaction
    void createPythonOwnedChunkTable() {
        jdbc.execute("create table if not exists episode_chunks (id uuid primary key, episode_id uuid not null)");
    }

    @BeforeEach
    void setUp() throws Exception {
        member = save(Member.register("tool@example.com", "encoded", "01012345678", "작가"));
        work = save(Work.create(member, "도구 작품", WorkGenre.FANTASY, "설명"));
        var other = save(Member.register("other-tool@example.com", "encoded", "01087654321", "다른 작가"));
        otherWork = save(Work.create(other, "다른 작품", WorkGenre.FANTASY, "설명"));
        character = save(WorkCharacter.create(work, "아리아", null, null, null, null, null, null, null, null, null));
        episode = save(Episode.create(work, null, 1, "1화", "source-key", "v1", hash(TEXT), 20));
        job = save(AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION));
        token = tokens.generateAccessToken(member);
        when(storage.getText("source-key")).thenReturn(TEXT);
    }

    @Test @DisplayName("현재값은 ADD·MERGE·UPDATE·REMOVE 후 snapshot과 동일하고 과거 Fact로 복구하지 않는다")
    void currentTracksSnapshotAndSourceTransitions() {
        var first = fact("10", true);
        snapshot("10", List.of(first));
        assertThat(run(SettingTool.CHARACTER_CURRENT, character.getId()).items().content().getFirst().value()).isEqualTo("10");
        var merged = fact("10 + 보너스 5", true);
        snapshot("15", List.of(first, merged));
        var current = run(SettingTool.CHARACTER_CURRENT, character.getId()).items().content().getFirst();
        assertThat(current.value()).isEqualTo("15");
        assertThat(current.sources()).hasSize(2);
        var updated = fact("20", true);
        snapshot("20", List.of(updated));
        assertThat(run(SettingTool.CHARACTER_CURRENT, character.getId()).items().content().getFirst().sources())
                .extracting(reference -> reference.targetId()).containsExactly(updated.getId());
        var late = fact("옛날에는 5", true);
        late.getSettingCandidate().recordConfirmedApplicationMode(CharacterFactConfirmApplicationMode.HISTORY_ONLY);
        assertThat(run(SettingTool.CHARACTER_CURRENT, character.getId()).items().content().getFirst().value()).isEqualTo("20");
        assertThat(run(SettingTool.CHARACTER_HISTORY, character.getId()).items().content())
                .filteredOn(item -> item.id().equals(late.getId())).allMatch(item -> Boolean.FALSE.equals(item.contributesToCurrent()));
        snapshots.replace(character, Map.of());
        sources.removeSources(character, SLOT);
        assertThat(run(SettingTool.CHARACTER_CURRENT, character.getId()).items().content()).isEmpty();
        assertThat(run(SettingTool.CHARACTER_HISTORY, character.getId()).items().content()).hasSize(4);
    }

    @Test @DisplayName("수동 변경 이후 남은 과거 Fact를 현재 근거로 취급하지 않는다")
    void manualCharacterHasUnknownProvenanceWithoutOldReferences() {
        var old = fact("10", true);
        snapshot("10", List.of(old));
        snapshot("직접 수정 30", List.of());
        var current = run(SettingTool.CHARACTER_CURRENT, character.getId()).items().content().getFirst();
        assertThat(current.value()).isEqualTo("직접 수정 30");
        assertThat(current.origin().name()).isEqualTo("UNKNOWN");
        assertThat(current.sources()).isEmpty();
        assertThat(run(SettingTool.CHARACTER_HISTORY, character.getId()).items().content().getFirst().contributesToCurrent()).isFalse();
    }

    @Test @DisplayName("같은 설정명이 다른 scope에 있어도 값과 현재 근거가 섞이지 않는다")
    void distinguishesWorldScopesHistoryAndManualEdits() {
        var world = save(WorldSetting.create(work, WorldSettingCategory.POWER_SYSTEM, "마법", "일반", "소모 마력", "10"));
        world.addProperty("고급", "소모 마력", "50");
        var general = worldCandidate(world, "일반", "10", false, WorldSettingOperation.ADD);
        var advanced = worldCandidate(world, "고급", "50", false, WorldSettingOperation.ADD);
        var old = worldCandidate(world, "일반", "5", true, WorldSettingOperation.ADD);
        assertThat(run(SettingTool.FIND_WORLD_SETTINGS, null).items().content())
                .extracting(item -> item.subjectId()).containsExactly(world.getId());
        var direct = run(SettingTool.WORLD_EVIDENCE, general.getId()).items().content().getFirst();
        assertThat(direct.evidence().status().name()).isEqualTo("AVAILABLE");
        assertThat(direct.contributesToCurrent()).isTrue();
        var scoped = new SettingToolQuery(SettingTool.WORLD_HISTORY, world.getId(), null, "소모 마력", "일반", 1, 1, 0, 20);
        assertThat(tools.querySettings(member.getId(), work.getId(), scoped).items().content())
                .hasSize(2).allMatch(item -> "일반".equals(item.scopeName()));
        var current = run(SettingTool.WORLD_CURRENT, world.getId()).items().content();
        assertThat(current).hasSize(2);
        assertThat(current.stream().filter(item -> "일반".equals(item.scopeName())).findFirst().orElseThrow().sources())
                .extracting(ref -> ref.targetId()).containsExactly(general.getId());
        assertThat(current.stream().filter(item -> "고급".equals(item.scopeName())).findFirst().orElseThrow().sources())
                .extracting(ref -> ref.targetId()).containsExactly(advanced.getId());
        assertThat(run(SettingTool.WORLD_HISTORY, world.getId()).items().content())
                .filteredOn(item -> item.id().equals(old.getId())).allMatch(item -> Boolean.FALSE.equals(item.contributesToCurrent()));
        world.protectManualProperty("일반", "소모 마력");
        var manual = run(SettingTool.WORLD_CURRENT, world.getId()).items().content().stream()
                .filter(item -> "일반".equals(item.scopeName())).findFirst().orElseThrow();
        assertThat(manual.origin().name()).isEqualTo("USER_INPUT");
        assertThat(manual.sources()).isEmpty();
    }

    @Test @DisplayName("세계관 MERGE는 이전 기반 근거를 보존하고 UPDATE 뒤에는 이전 근거를 끊는다")
    void worldMergePreservesSourcesUntilReplacement() {
        var world = save(WorldSetting.create(work, WorldSettingCategory.POWER_SYSTEM, "마법", "일반", "소모 마력", "10"));
        var base = worldCandidate(world, "일반", "10", false, WorldSettingOperation.ADD);
        world.applyProperty("일반", "소모 마력", "10 + 보너스 5");
        var merged = worldCandidate(world, "일반", "10 + 보너스 5", false, WorldSettingOperation.MERGE);
        assertThat(run(SettingTool.WORLD_CURRENT, world.getId()).items().content().getFirst().sources())
                .extracting(ref -> ref.targetId()).containsExactly(merged.getId(), base.getId());
        world.applyProperty("일반", "소모 마력", "30");
        var replacement = worldCandidate(world, "일반", "30", false, WorldSettingOperation.UPDATE);
        assertThat(run(SettingTool.WORLD_CURRENT, world.getId()).items().content().getFirst().sources())
                .extracting(ref -> ref.targetId()).containsExactly(replacement.getId());
    }

    @Test @DisplayName("확정 근거는 실제 원문과 해시 및 Unicode code point offset을 확인한다")
    void validatesRealSourceAndUnicodeOffsets() throws Exception {
        var fact = fact("10", true);
        var data = postQuery(work, request(SettingTool.CHARACTER_EVIDENCE, fact.getId()), 200);
        var evidence = data.at("/items/content/0/evidence");
        assertThat(evidence.path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(evidence.at("/quotes/0/text").asText()).isEqualTo("마력은 10이다.");
        assertThat(evidence.at("/quotes/0/startOffset").asInt()).isEqualTo(2);
        assertThat(data.toString()).doesNotContain("source-key", "contentS3Key");
        verify(storage).getText("source-key");
    }

    @Test @DisplayName("실제 HTTP 응답이 Python과 공유하는 v1 계약 fixture와 일치한다")
    void matchesSharedPythonContract() throws Exception {
        var fact = fact("10", true);
        var actual = postQuery(work, request(SettingTool.CHARACTER_EVIDENCE, fact.getId()), 200);
        String normalized = actual.toString()
                .replace(fact.getId().toString(), "00000000-0000-0000-0000-000000000001")
                .replace(character.getId().toString(), "00000000-0000-0000-0000-000000000002")
                .replace(fact.getSettingCandidate().getId().toString(), "00000000-0000-0000-0000-000000000003")
                .replace(episode.getId().toString(), "00000000-0000-0000-0000-000000000004");
        try (var fixture = getClass().getResourceAsStream("/contracts/setting-tools-v1.json")) {
            assertThat(json.readTree(normalized)).isEqualTo(json.readTree(fixture));
        }
    }

    @Test @DisplayName("청크 ID는 실제 존재와 같은 회차 소속을 검증하며 타 작품 청크는 거절한다")
    void validatesLooseChunkReference() throws Exception {
        var fact = fact("10", true);
        UUID chunkId = UUID.randomUUID();
        jdbc.update("insert into episode_chunks (id, episode_id) values (?, ?)", chunkId, episode.getId());
        ReflectionTestUtils.setField(fact, "sourceChunkId", chunkId);
        assertThat(run(SettingTool.CHARACTER_EVIDENCE, fact.getId()).items().content().getFirst().evidence().sourceChunkId()).isEqualTo(chunkId);
        var foreign = save(Episode.create(otherWork, null, 1, "다른 원문", "secret-key", null, null, 1));
        jdbc.update("update episode_chunks set episode_id = ? where id = ?", foreign.getId(), chunkId);
        postQuery(work, request(SettingTool.CHARACTER_EVIDENCE, fact.getId()), 404);
    }

    @Test @DisplayName("저장소 장애·객체 없음·원문 변경·삭제는 서로 다른 상태이며 인용이 없다")
    void sourceFailuresAreExplicitAndNeverReturnStaleQuotes() {
        var fact = fact("10", true);
        when(storage.getText(anyString())).thenThrow(new IllegalStateException("private-storage-detail"));
        assertEvidence(fact, "STORAGE_UNAVAILABLE");
        doThrow(NoSuchKeyException.builder().statusCode(404).build()).when(storage).getText(anyString());
        assertEvidence(fact, "SOURCE_MISSING");
        episode.updateContentStorage("new-key", "v2", "new-hash");
        assertEvidence(fact, "SOURCE_CHANGED");
        episode.updateStatus(EpisodeStatus.ARCHIVED);
        assertEvidence(fact, "SOURCE_PURGED");
    }

    @Test @DisplayName("원문을 읽는 동안 삭제된 회차도 인용을 재노출하지 않는다")
    void rechecksSourceAfterStorageRead() {
        var fact = fact("10", true);
        when(storage.getText("source-key")).thenAnswer(ignored -> {
            episode.updateStatus(EpisodeStatus.ARCHIVED);
            em.flush();
            return TEXT;
        });
        assertEvidence(fact, "SOURCE_PURGED");
    }

    @Test @DisplayName("조회 도중 설정 version이 바뀌면 과거 값과 새 근거를 혼합하지 않고 재조회를 요구한다")
    void rejectsConcurrentSettingChange() throws Exception {
        var fact = fact("10", true);
        when(storage.getText("source-key")).thenAnswer(ignored -> {
            jdbc.update("update characters set snapshot_version = snapshot_version + 1 where id = ?", character.getId());
            return TEXT;
        });
        postQuery(work, request(SettingTool.CHARACTER_EVIDENCE, fact.getId()), 409);
    }

    @Test @DisplayName("회차 제한은 이력·근거에만 적용하고 현재 시점 복원으로 해석하지 않는다")
    void rangesFilterEvidenceAndRejectCurrentSnapshotRanges() throws Exception {
        var fact = fact("10", true);
        var range = new SettingToolQuery(SettingTool.CHARACTER_HISTORY, character.getId(), null, null, null, 2, 3, 0, 20);
        assertThat(postQuery(work, range, 200).at("/items/content")).isEmpty();
        var evidence = new SettingToolQuery(SettingTool.CHARACTER_EVIDENCE, fact.getId(), null, null, null, 2, 3, 0, 20);
        var outside = postQuery(work, evidence, 200);
        assertThat(outside.at("/items/content/0/evidence/status").asText()).isEqualTo("OUT_OF_RANGE");
        assertThat(outside.at("/items/content/0/value").isNull()).isTrue();
        episode.updateStatus(EpisodeStatus.ARCHIVED);
        var outsidePurged = postQuery(work, evidence, 200);
        assertThat(outsidePurged.at("/items/content/0/evidence/status").asText()).isEqualTo("OUT_OF_RANGE");
        assertThat(outsidePurged.at("/items/content/0/value").isNull()).isTrue();
        postQuery(work, new SettingToolQuery(SettingTool.CHARACTER_CURRENT, character.getId(), null, null, null, 1, 2, 0, 20), 400);
        verifyNoInteractions(storage);
    }

    @Test @DisplayName("미확정 후보·보관 인물·다른 회원·잘못 연결된 source를 조회하지 못한다")
    void protectsAllLookupBoundaries() throws Exception {
        var pending = fact("미확정", false);
        postQuery(work, request(SettingTool.CHARACTER_EVIDENCE, pending.getId()), 404);
        assertThat(run(SettingTool.CHARACTER_HISTORY, character.getId()).items().content()).isEmpty();
        var valid = fact("10", true);
        var foreignEpisode = save(Episode.create(otherWork, null, 1, "다른 원문", "secret-key", null, null, 10));
        ReflectionTestUtils.setField(valid.getSettingCandidate(), "episode", foreignEpisode);
        postQuery(work, request(SettingTool.CHARACTER_EVIDENCE, valid.getId()), 404);
        postQuery(otherWork, request(SettingTool.FIND_CHARACTERS, null), 404);
        character.archive();
        postQuery(work, request(SettingTool.CHARACTER_CURRENT, character.getId()), 404);
        verifyNoInteractions(storage);
    }

    @Test @DisplayName("공개 API의 회원은 JWT로 고정하며 내부 API는 key와 서버 사용자 범위를 모두 요구한다")
    void authenticatesPublicAndInternalAdapters() throws Exception {
        String body = json.writeValueAsString(request(SettingTool.FIND_CHARACTERS, null));
        mvc.perform(post(url(work)).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        String internalUrl = "/api/internal/v1/works/" + work.getId() + "/setting-tools/queries";
        mvc.perform(post(internalUrl).contentType("application/json").content(body)
                .header("X-Setting-Tool-Member-Id", member.getId())).andExpect(status().isUnauthorized());
        mvc.perform(post(internalUrl).contentType("application/json").content(body)
                .header("X-Internal-Api-Key", "setting-tool-test-key")).andExpect(status().isBadRequest());
        mvc.perform(post(internalUrl).contentType("application/json").content(body)
                .header("X-Internal-Api-Key", "setting-tool-test-key")
                .header("X-Setting-Tool-Member-Id", member.getId())).andExpect(status().isOk());
    }

    @Test @DisplayName("조회 상한과 필터 조합을 검증하고 와일드카드를 리터럴 이름으로 검색한다")
    void validatesArgumentsAndLiteralSearch() throws Exception {
        postQuery(work, new SettingToolQuery(SettingTool.FIND_CHARACTERS, null, null, null, null, null, null, 0, 51), 400);
        var literal = new SettingToolQuery(SettingTool.FIND_CHARACTERS, null, "%", null, null, null, null, 0, 20);
        assertThat(postQuery(work, literal, 200).at("/items/totalElements").asInt()).isZero();
        var result = postQuery(work, request(SettingTool.FIND_CHARACTERS, null), 200);
        assertThat(result.at("/items/totalElements").asInt()).isEqualTo(1);
        assertThat(result.at("/items/content/0/subjectName").asText()).isEqualTo("아리아");
    }

    @Test @DisplayName("현재 출처가 20개를 넘으면 표시값은 최신을 유지하고 잘림을 명시한다")
    void sourceReferenceLimitPreservesLatestLegacyValue() throws Exception {
        var facts = new ArrayList<CharacterFact>();
        for (int i = 0; i < 25; i++) facts.add(fact("값" + i, true));
        ReflectionTestUtils.setField(character, "statsJson", nodes.objectNode().put("stats.mana", 25));
        sources.replaceSources(character, SLOT, facts);
        var response = run(SettingTool.CHARACTER_CURRENT, character.getId());
        assertThat(response.items().content().getFirst().value()).isEqualTo("값24");
        assertThat(response.items().content().getFirst().sources()).hasSize(20);
        assertThat(response.partial()).isTrue();
        assertThat(response.items().content().getFirst().sources().getFirst().targetId()).isEqualTo(facts.getLast().getId());
        var screen = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/works/{workId}/characters/{id}", work.getId(), character.getId())
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(json.readTree(screen.getContentAsString(StandardCharsets.UTF_8)).at("/data/stats/0/value").asText())
                .isEqualTo(response.items().content().getFirst().value());
    }

    @Test @DisplayName("OpenAPI에 공개·내부 도구 경로와 독립 응답 schema가 등록된다")
    void publishesOpenApiContract() throws Exception {
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var api = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(api.path("paths").path("/api/v1/works/{workId}/setting-tools/queries").at("/post/operationId").asText())
                .isEqualTo("querySettingTools");
        assertThat(api.path("paths").path("/api/internal/v1/works/{workId}/setting-tools/queries").at("/post/operationId").asText())
                .isEqualTo("queryInternalSettingTools");
        assertThat(api.at("/components/schemas/SettingToolItemV1/properties/sourceEpisodeNo").isMissingNode()).isFalse();
        assertThat(api.at("/components/schemas/SettingToolEvidenceV1/properties/status").isMissingNode()).isFalse();
    }

    @Test @DisplayName("분석 이후 회차 번호가 달라져도 이력의 출처 범위는 분석 당시 번호를 유지한다")
    void historyRangeUsesCapturedSourceNumber() {
        fact("10", true);
        episode.updateMetadata(7, "7화", 20);
        em.flush();
        var query = new SettingToolQuery(SettingTool.CHARACTER_HISTORY, character.getId(), null, null, null, 1, 1, 0, 20);
        var history = tools.querySettings(member.getId(), work.getId(), query);
        assertThat(history.items().content()).hasSize(1);
        assertThat(history.items().content().getFirst().sourceEpisodeNo()).isEqualTo(1);
    }

    @Test @DisplayName("기존 source key라도 저장된 본문이 달라지면 hash 불일치로 인용을 차단한다")
    void detectsChangedBodyWithoutMetadataUpdate() {
        var fact = fact("10", true);
        when(storage.getText("source-key")).thenReturn("수정된 원문이다.");
        assertEvidence(fact, "SOURCE_CHANGED");
    }

    @Test @DisplayName("원문 해시가 맞아도 저장된 quote가 실제 위치와 다르면 근거로 반환하지 않는다")
    void rejectsFabricatedOrMisalignedQuote() {
        var fact = fact("10", true);
        ReflectionTestUtils.setField(fact.getSettingCandidate(), "evidenceSpans", nodes.arrayNode().add(nodes.objectNode()
                .put("quote", "없는 문장").put("startOffset", 2).put("endOffset", 6)));
        assertEvidence(fact, "INVALID_QUOTE");
    }

    @Test @DisplayName("레거시 출처와 출처 없는 수동 Fact는 확인 불가 상태를 명시한다")
    void keepsLegacyAndMissingProvenanceExplicit() {
        var fact = fact("10", true);
        ReflectionTestUtils.setField(job, "sourceEpisodeNo", null);
        assertThat(run(SettingTool.CHARACTER_EVIDENCE, fact.getId()).items().content().getFirst().evidence().status().name())
                .isEqualTo("LEGACY_UNVERIFIED");
        var manual = save(CharacterFact.createManual(character, CharacterFactType.STAT, "stats.manual", "30", null));
        assertEvidence(manual, "NO_SOURCE");
    }

    private <T> T save(T entity) { em.persist(entity); return entity; }
    private String url(Work target) { return "/api/v1/works/" + target.getId() + "/setting-tools/queries"; }
    private SettingToolQuery request(SettingTool tool, UUID id) { return new SettingToolQuery(tool, id, null, null, null, null, null, 0, 20); }
    private SettingToolResponse run(SettingTool tool, UUID id) { em.flush(); return tools.querySettings(member.getId(), work.getId(), request(tool, id)); }
    private JsonNode postQuery(Work target, SettingToolQuery request, int status) throws Exception {
        em.flush();
        var response = mvc.perform(post(url(target)).header("Authorization", "Bearer " + token)
                .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().is(status)).andReturn().getResponse();
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("data");
    }
    private CharacterFact fact(String value, boolean confirmed) {
        var spans = nodes.arrayNode().add(nodes.objectNode().put("quote", "마력은 10이다.").put("startOffset", 2).put("endOffset", 11));
        var candidate = save(SettingCandidate.create(work, episode, null, job, SettingEntityType.CHARACTER,
                character.getName(), SLOT.factKey(), value, SettingValueType.STRING, null, spans, BigDecimal.ONE, null));
        if (confirmed) candidate.confirm();
        return save(CharacterFact.create(character, candidate, SLOT.factType(), SLOT.factKey(), value, value,
                null, episode, null, job, BigDecimal.ONE, episode.getEpisodeNo()));
    }
    private void snapshot(String value, List<CharacterFact> provenance) {
        snapshots.replace(character, Map.of(SLOT, snapshots.entry(SLOT.factType(), SLOT.factKey(), value, nodes.textNode(value))));
        sources.replaceSources(character, SLOT, provenance);
    }
    private WorldSettingCandidate worldCandidate(WorldSetting world, String scope, String value, boolean history, WorldSettingOperation operation) {
        var candidate = save(WorldSettingCandidate.create(work, episode, job, world.getCategory(), world.getSubjectName(), scope,
                "소모 마력", value, nodes.arrayNode().add(nodes.objectNode().put("quote", "마력은 10이다.")), BigDecimal.ONE, null));
        candidate.startComparison();
        candidate.completeComparison(world, WorldSettingConsolidationStatus.SINGLE, operation, scope, "소모 마력", null,
                value, "비교 완료", null, LocalDateTime.now());
        if (history) candidate.markHistoryOnly();
        candidate.confirm(operation, world.getCategory(), world.getSubjectName(), scope, "소모 마력", value, null, member, world);
        return candidate;
    }
    private void assertEvidence(CharacterFact fact, String status) {
        var evidence = run(SettingTool.CHARACTER_EVIDENCE, fact.getId()).items().content().getFirst().evidence();
        assertThat(evidence.status().name()).isEqualTo(status);
        assertThat(evidence.quotes()).isEmpty();
    }
    private String hash(String text) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
}
