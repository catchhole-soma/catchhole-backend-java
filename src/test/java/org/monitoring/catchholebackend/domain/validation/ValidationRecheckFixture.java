package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadRequest;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.service.EpisodeService;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeUploadType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.monitoring.catchholebackend.domain.validation.service.ValidationBaselineService;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.global.storage.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

abstract class ValidationRecheckFixture {
    @Autowired EntityManager em;
    @Autowired EpisodeService episodes;
    @Autowired JwtTokenProvider tokens;
    @Autowired MockMvc mvc;
    @Autowired AnalysisStateJournal journal;
    @Autowired ValidationBaselineService baselines;
    @MockitoBean ObjectStorage storage;
    final ObjectMapper json = new ObjectMapper();
    Member member;
    Work work;
    Episode target;
    Episode prior;
    WorkCharacter character;
    WorldSetting world;
    UUID batchId;
    String token;

    @BeforeEach void prepare() {
        when(storage.putBytes(anyString(), any(byte[].class), anyString())).thenAnswer(c -> new StoredObject(c.getArgument(0), "synthetic-v1"));
        when(storage.putText(anyString(), anyString())).thenAnswer(c -> new StoredObject(c.getArgument(0), "synthetic-v1"));
        when(storage.purgePrefixes(anyCollection())).thenReturn(new ObjectStoragePurgeResult(1, 1, 0));
        when(storage.purgePrefixesExcluding(anyCollection(), anyCollection())).thenReturn(new ObjectStoragePurgeResult(1, 1, 0));
        em.createNativeQuery("create table if not exists episode_chunks (id uuid primary key, episode_id uuid)").executeUpdate();
        member = Member.register("recheck-" + UUID.randomUUID() + "@example.invalid", "test-only", "01012345678", "합성 작가"); em.persist(member);
        work = Work.create(member, "재검수 합성 자료", WorkGenre.FANTASY, null); em.persist(work);
        prior = Episode.create(work, null, 100, "앞 원고", "synthetic/100", "v1", "a".repeat(64), 2); em.persist(prior);
        work.updateLatestEpisodeNo(100);
        character = WorkCharacter.create(work, "합성 인물", null, 20, 1, null, null, null, null, null, null); em.persist(character);
        world = WorldSetting.create(work, WorldSettingCategory.RACE, "합성 종족", "서식지", "북부"); em.persist(world);
        var uploaded = episodes.uploadEpisodes(member.getId(), work.getId(), new EpisodeUploadRequest(EpisodeUploadType.SINGLE_EPISODE, 101, "합성", null),
                List.of(new MockMultipartFile("episodeFiles", "101.txt", "text/plain", "처음 합성 원고".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        batchId = uploaded.batchId();
        target = em.createQuery("select e from Episode e where e.work=:work and e.episodeNo=101", Episode.class).setParameter("work", work).getSingleResult();
        token = tokens.generateAccessToken(member); em.flush();
    }
    String base() { return "/api/v1/works/" + work.getId() + "/episode-validations"; }
    JsonNode start() throws Exception {
        return postOk("", Map.of("idempotencyKey", "initial", "batchId", batchId, "episodeId", target.getId(),
                "expectedContentHash", target.getContentHash(), "expectedContentS3Key", target.getContentS3Key(), "expectedContentS3Version", target.getContentS3Version()));
    }
    JsonNode postOk(String path, Object input) throws Exception {
        var result = mvc.perform(post(base()+path).header("Authorization", "Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }
    void conflict(String path, Object input, String code) throws Exception {
        mvc.perform(post(base()+path).header("Authorization", "Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value(code));
    }
    ValidationReport stored(JsonNode response) { return em.find(ValidationReport.class, UUID.fromString(response.path("reportId").asText())); }
    JsonNode get(UUID id) throws Exception {
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base()+"/"+id).header("Authorization", "Bearer "+token)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }
    void replace(String text) {
        episodes.replaceEpisodeFile(member.getId(), work.getId(), target.getId(), new MockMultipartFile("file", "new.txt", "text/plain", text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); em.flush();
    }
    Map<String, Object> recheck(JsonNode original, String key) throws Exception {
        var current = get(UUID.fromString(original.path("reportId").asText()));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("idempotencyKey", key); request.put("expectedReportVersion", current.path("reportVersion").asLong());
        request.put("expectedContentHash", target.getContentHash()); request.put("expectedContentS3Key", target.getContentS3Key());
        request.put("expectedContentS3Version", target.getContentS3Version()); request.put("expectedBaselineHash", original.path("baselineHash").asText());
        request.put("expectedContentRevision",target.getContentRevision());
        return request;
    }
    String recheckPath(JsonNode r) { return "/"+r.path("reportId").asText()+"/rechecks"; }
    String correctionPath(JsonNode r) { return "/"+r.path("reportId").asText()+"/corrections"; }
    Map<String, Object> correction(JsonNode response, String domain, String key) {
        var report = stored(response); JsonNode entry = null; int index = 0;
        for (var candidate : report.getBaselineEntries()) { index++; if (candidate.path("domain").asText().equals(domain)) { entry = candidate; break; } }
        assertThat(entry).isNotNull();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("idempotencyKey", key); input.put("expectedReportVersion", response.path("reportVersion").asLong());
        input.put("baselineId", report.getId()); input.put("expectedBaselineHash", report.getBaselineHash());
        input.put("expectedBaselineRevision", response.path("baselineRevision").asLong());
        input.put("entryRef", "B"+index); input.put("expectedEntryHash", journal.hash(entry)); return input;
    }
    Map<String, Object> characterEdit(int age, int level) {
        Map<String, Object> edit = new LinkedHashMap<>(); edit.put("name", character.getName()); edit.put("currentAge", age); edit.put("currentLevel", level);
        for (String group : List.of("profile", "stats", "skills", "items", "statuses")) edit.put(group, List.of());
        return edit;
    }
    void noExtraction() {
        for (String entity : List.of("AnalysisJob", "SettingCandidate", "WorldSettingCandidate"))
            assertThat(em.createQuery("select count(x) from "+entity+" x where x.work.id=:id", Long.class).setParameter("id", work.getId()).getSingleResult()).as(entity).isZero();
    }
}
