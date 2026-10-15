package org.monitoring.catchholebackend.domain.upload.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFactComparisonBatch;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonBatch;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecision;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecisionSource;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingConsolidationStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.storage.ObjectStorage;
import org.monitoring.catchholebackend.global.storage.StoredObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "internal.api-key=test-internal-api-key")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SettingBookAnalysisIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter mvcAdapter;
    @Autowired MemberRepository members;
    @Autowired WorkRepository works;
    @Autowired JwtTokenProvider jwt;
    @Autowired JdbcTemplate sql;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository jobs;
    @Autowired org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService states;
    @Autowired org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobWorkerService worker;
    @Autowired org.monitoring.catchholebackend.domain.character.service.CharacterAnalysisStateService characterStates;
    @Autowired org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingWorkerService worldWorker;
    @MockitoBean ObjectStorage storage;
    final ObjectMapper json = new ObjectMapper();
    final Map<String, byte[]> objects = new HashMap<>();
    Work work;
    String bearer;

    @BeforeEach void setup() {
        sql.execute("create table if not exists episode_chunks(id uuid primary key,episode_id uuid not null)");
        Member member = members.save(Member.register(UUID.randomUUID()+"@example.invalid", "test-only", null, "설정 작가"));
        work = works.save(Work.create(member, "설정집만 있는 작품", WorkGenre.FANTASY, null));
        bearer = "Bearer "+jwt.generateAccessToken(member);
        when(storage.putBytes(anyString(), any(byte[].class), any())).thenAnswer(call -> {
            objects.put(call.getArgument(0), call.getArgument(1));
            return new StoredObject(call.getArgument(0), "v1");
        });
        when(storage.putText(anyString(), anyString())).thenAnswer(call -> {
            objects.put(call.getArgument(0), call.<String>getArgument(1).getBytes(StandardCharsets.UTF_8));
            return new StoredObject(call.getArgument(0), "v1");
        });
        when(storage.getBytes(anyString())).thenAnswer(call -> objects.get(call.getArgument(0)));
        when(storage.getText(anyString())).thenAnswer(call -> new String(objects.get(call.getArgument(0)),StandardCharsets.UTF_8));
        when(storage.purgePrefixes(org.mockito.ArgumentMatchers.anyCollection())).thenAnswer(call -> {
            java.util.Collection<String> prefixes=call.getArgument(0);
            int before=objects.size(); objects.keySet().removeIf(k -> prefixes.stream().anyMatch(k::startsWith));
            int deleted=before-objects.size();return new org.monitoring.catchholebackend.global.storage.ObjectStoragePurgeResult(deleted,deleted,0);
        });
    }


    @Test
    @org.junit.jupiter.api.DisplayName("새 Worker claim은 동명 인물·모든 슬롯·세계관 범주를 같은 고정 입력에서 전달하고 재개 때 보존한다")
    void semanticCurrentClaimExportsFullFrozenStateAndFreshLease() throws Exception {
        var book = upload("하린은 기사다.");
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var input = semanticFrozenState();
        origin.captureAutomaticInput(input); em.flush();
        JsonNode claimed = semanticClaim("SETTING_BOOK", 200);
        assertSemanticCarrier(claimed, input);
        assertThat(claimed.path("semanticCurrentContext").path("characters")).hasSize(3);
        assertThat(claimed.path("knownCharacters")).hasSize(2);
        assertThat(claimed.path("knownCharacters")).allSatisfy(c -> assertThat(c.path("name").asText()).isEqualTo("한서린"));
        assertThat(claimed.path("semanticCurrentContext").path("worldSubjects")).hasSize(WorldSettingCategory.values().length);
        assertThat(claimed.path("analysisContext").has("semanticCurrentContext")).isFalse();
        String lease = claimed.path("leaseToken").asText();
        mvc.perform(patch("/api/internal/v1/analysis-jobs/{id}/progress",origin.getId())
                .header("X-Internal-Api-Key","test-internal-api-key").header("X-Worker-Lease-Token",lease)
                .contentType(MediaType.APPLICATION_JSON).content("{\"currentStep\":\"완료한 청크 보존\",\"checkpointStage\":\"CHUNKS_READY\"}")).andExpect(status().isOk());
        UUID chunkId = UUID.randomUUID();
        em.flush();
        sql.update("insert into setting_book_chunks(id,analysis_job_id,setting_book_revision_id,chunk_index,chunk_text,start_offset,end_offset,paragraph_start_index,paragraph_end_index,created_at,updated_at) values(?,?,?,0,?,0,8,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                chunkId,origin.getId(),UUID.fromString(book.path("revisionId").asText()),"하린은 기사다.");
        mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/fail",origin.getId())
                .header("X-Internal-Api-Key","test-internal-api-key").header("X-Worker-Lease-Token",lease)
                .contentType(MediaType.APPLICATION_JSON).content("{\"failureCode\":\"LLM_NETWORK_ERROR\",\"errorMessage\":\"synthetic checkpoint interruption\"}")).andExpect(status().isOk());
        analyze(book.path("id").asText());
        JsonNode resumed = semanticClaim("SETTING_BOOK", 200);
        assertSemanticCarrier(resumed,input);
        assertThat(resumed.path("semanticCurrentContext")).isEqualTo(claimed.path("semanticCurrentContext"));
        assertThat(resumed.path("leaseToken").asText()).isNotEqualTo(lease);
        assertThat(resumed.path("checkpointStage").asText()).isEqualTo("CHUNKS_READY");
        assertThat(sql.queryForList("select id from setting_book_chunks where analysis_job_id=?",UUID.class,origin.getId())).containsExactly(chunkId);
        mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/heartbeat",origin.getId())
                .header("X-Internal-Api-Key","test-internal-api-key").header("X-Worker-Lease-Token",lease)).andExpect(status().isConflict());

        work = works.save(Work.create(work.getMember(),"고정 회차 CURRENT",WorkGenre.FANTASY,null));
        var episodeOrigin = episodeOrderedOrigin(3); episodeOrigin.captureAutomaticInput(input);
        JsonNode episodeClaim = semanticClaim("EPISODE",200); assertSemanticCarrier(episodeClaim,input);
        work = works.save(Work.create(work.getMember(),"빈 현재 설정",WorkGenre.FANTASY,null));
        upload("빈 현재 설정의 첫 원문");
        JsonNode emptyClaim = semanticClaim("SETTING_BOOK",200);
        assertThat(emptyClaim.path("semanticCurrentContext").path("complete").asBoolean()).isTrue();
        assertThat(emptyClaim.path("semanticCurrentContext").path("characters")).isEmpty();
        assertThat(emptyClaim.path("semanticCurrentContext").path("worldSubjects")).isEmpty();
        String export = System.getenv("GH221_EXPORT_DIR");
        if (export != null) {
            var vectors = new java.util.LinkedHashMap<String,Object>();
            vectors.put("bookClaim",json.convertValue(claimed,Map.class));
            vectors.put("episodeClaim",json.convertValue(episodeClaim,Map.class));
            vectors.put("emptyClaim",json.convertValue(emptyClaim,Map.class));
            vectors.put("resumedBookClaim",json.convertValue(resumed,Map.class));
            vectors.put("originalInputState",json.convertValue(input,Map.class));
            var converter = mvcAdapter.getMessageConverters().stream().filter(c -> c.canWrite(vectors.getClass(),MediaType.APPLICATION_JSON)).findFirst().orElseThrow();
            var output = new org.springframework.mock.http.MockHttpOutputMessage();
            @SuppressWarnings("unchecked") var writer = (org.springframework.http.converter.HttpMessageConverter<Object>)converter;
            writer.write(vectors,MediaType.APPLICATION_JSON,output);
            java.nio.file.Files.writeString(java.nio.file.Path.of(export,"java-semantic-current-wire.json"),output.getBodyAsString(StandardCharsets.UTF_8));
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"COUNT","BYTES"})
    @org.junit.jupiter.api.DisplayName("상한을 넘은 첫 작업은 실패·lease 및 토큰 정리 후 다른 작품의 정상 claim을 막지 않는다")
    void semanticCurrentOverflowFailsTerminallyWithoutPoisoningQueue(String bound) throws Exception {
        var first = upload("첫 원문"); var origin = jobs.findById(UUID.fromString(first.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var input = new org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal().emptyState();
        var ownerId = UUID.randomUUID(); var owner = input.withObject("characters").putObject("character:"+ownerId);
        owner.put("actualCharacterId",ownerId.toString()).putNull("provisionalSubjectKey").put("name","하린");
        var slots = owner.putObject("slots");
        if (bound.equals("COUNT")) {
            for (int i=0;i<4097;i++) slots.putObject("PROFILE:key"+i).put("factType","PROFILE").put("factKey","key"+i).put("factValue","value").putNull("valueJson");
        } else {
            slots.putObject("PROFILE:key").put("factType","PROFILE").put("factKey","key").put("factValue","한".repeat(350000)).putNull("valueJson");
        }
        origin.captureAutomaticInput(input); em.flush();
        JsonNode oldClaim = claimBook();
        UUID reserved = UUID.randomUUID();
        reserveTokens(origin.getId(),oldClaim.path("leaseToken").asText(),reserved,1000,200);
        em.flush();
        assertThat(sql.queryForObject("select reserved_tokens from ai_token_accounts where member_id=?",Long.class,work.getMember().getId())).isEqualTo(1000L);
        // Recovery fixture: a requeued job still has an orphan reservation from its previous attempt.
        origin.requeueExpiredLease(); em.flush();
        semanticClaim("SETTING_BOOK",204);
        em.flush();
        assertThat(origin.getStatus().name()).isEqualTo("FAILED");
        assertThat(origin.getFailureCode().name()).isEqualTo("UNEXPECTED_ERROR");
        assertThat(origin.getErrorMessage()).startsWith("SEMANTIC_CURRENT_CONTEXT_INVALID");
        assertThat(origin.getLeaseToken()).isNull(); assertThat(origin.getLeaseExpiresAt()).isNull();
        assertThat(origin.getJournalStatus().name()).isEqualTo("INCOMPLETE");
        assertThat(sql.queryForObject("select count(*) from setting_candidates where analysis_job_id=?",Integer.class,origin.getId())).isZero();
        assertThat(sql.queryForObject("select count(*) from world_setting_candidates where analysis_job_id=?",Integer.class,origin.getId())).isZero();
        assertThat(sql.queryForObject("select status from ai_token_usages where request_id=?",String.class,reserved)).isEqualTo("RELEASED");
        assertThat(sql.queryForObject("select outcome from ai_token_usages where request_id=?",String.class,reserved)).isEqualTo("USAGE_UNAVAILABLE");
        assertThat(sql.queryForObject("select reserved_tokens+used_tokens from ai_token_accounts where member_id=?",Long.class,work.getMember().getId())).isZero();
        work = works.save(Work.create(work.getMember(),"다음 정상 작품",WorkGenre.FANTASY,null)); var next = upload("다음 원문");
        JsonNode claimed = semanticClaim("SETTING_BOOK",200);
        assertThat(claimed.path("analysisJobId").asText()).isEqualTo(next.path("latestAnalysisJob").path("id").asText());
        assertThat(claimed.path("semanticCurrentContext").path("complete").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"MISSING_DOMAIN","MISSING_VALUE","UNKNOWN_TYPE","BOTH_IDENTITIES","WRONG_REF","DUPLICATE_OWNER","WORLD_NULL","WORLD_NUMBER","DUPLICATE_PATH"})
    @org.junit.jupiter.api.DisplayName("부분·잘못된 고정 현재 설정은 complete로 전달하지 않고 해당 작업만 실패시킨다")
    void semanticCurrentMalformedStateIsNeverClaimedAsComplete(String defect) throws Exception {
        var book = upload("원문"); var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var input = semanticFrozenState();
        var characters = input.withObject("characters"); var first = (com.fasterxml.jackson.databind.node.ObjectNode)characters.elements().next();
        var slots = first.withObject("slots"); var slot = (com.fasterxml.jackson.databind.node.ObjectNode)slots.elements().next();
        switch (defect) {
            case "MISSING_DOMAIN" -> input.remove("worldSettings");
            case "MISSING_VALUE" -> slot.remove("factValue");
            case "UNKNOWN_TYPE" -> slot.put("factType","UNRECOGNIZED");
            case "BOTH_IDENTITIES" -> first.put("provisionalSubjectKey","provisional-character:"+UUID.randomUUID());
            case "WRONG_REF" -> first.put("actualCharacterId",UUID.randomUUID().toString());
            case "DUPLICATE_OWNER" -> characters.set("character:duplicate",first.deepCopy());
            case "WORLD_NULL" -> ((com.fasterxml.jackson.databind.node.ObjectNode)input.path("worldSettings").elements().next().path("propertiesJson")).putNull("통행료");
            case "WORLD_NUMBER" -> ((com.fasterxml.jackson.databind.node.ObjectNode)input.path("worldSettings").elements().next().path("propertiesJson")).put("통행료",4);
            case "DUPLICATE_PATH" -> ((com.fasterxml.jackson.databind.node.ObjectNode)input.path("worldSettings").elements().next().path("propertiesJson")).put(" 통행료 ","다른 값");
            default -> throw new AssertionError(defect);
        }
        origin.captureAutomaticInput(input); em.flush();
        semanticClaim("SETTING_BOOK",204);
        assertThat(origin.getStatus().name()).isEqualTo("FAILED");
        assertThat(origin.getLeaseToken()).isNull();
        assertThat(origin.getFailureCode().name()).isEqualTo("UNEXPECTED_ERROR");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("구 Worker 및 CONFIRMED_ONLY 응답에는 새로운 현재 문맥을 추가하지 않는다")
    void semanticCurrentCapabilityOmissionKeepsLegacyContract() throws Exception {
        upload("하린은 기사다."); assertThat(claimBook().has("semanticCurrentContext")).isFalse();
        work = works.save(Work.create(work.getMember(),"기존 직접 검토 회차",WorkGenre.FANTASY,null));
        var episode = org.monitoring.catchholebackend.domain.episode.entity.Episode.create(work,null,1,"1화","synthetic/1","v1","d".repeat(64),1); em.persist(episode);
        var legacy = AnalysisJob.create(work,null,episode,org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION); em.persist(legacy); em.flush();
        JsonNode claimed = response(mvc.perform(post("/api/internal/v1/analysis-jobs/claim").header("X-Internal-Api-Key","test-internal-api-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"allowedJobTypes\":[\"SETTING_EXTRACTION\"],\"supportedAnalysisModes\":[\"CONFIRMED_ONLY\"],\"supportsSemanticCurrentContext\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(claimed.path("analysisJobId").asText()).isEqualTo(legacy.getId().toString());
        assertThat(claimed.has("semanticCurrentContext")).isFalse();
        assertThat(claimed.has("analysisContext")).isFalse();
    }


    @Test
    @org.junit.jupiter.api.DisplayName("실제 저장된 현재 설정의 모든 fact type과 scope 값은 claim 시 같은 input snapshot으로 결속된다")
    void semanticCurrentClaimUsesPersistedCanonicalSnapshots() throws Exception {
        var j=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var profile=j.objectNode().put("profile.직책","기록관");
        var stats=j.objectNode().put("stats.감각",43);
        var skills=j.objectNode().put("skills.항해","숙련");
        var items=j.objectNode().putNull("items.도구");
        var statuses=j.objectNode(); statuses.set("status.부상",j.objectNode().put("active",false).put("value","완치"));
        statuses.set("time.항해",j.objectNode().put("value","밤"));
        var first=WorkCharacter.create(work,"한서린",null,27,5,profile,stats,skills,items,statuses,null); em.persist(first);
        var second=WorkCharacter.create(work,"한서린",null,34,7,j.objectNode().put("profile.직책","선원"),null,null,null,null,null); em.persist(second);
        for(var category:WorldSettingCategory.values()) {
            var world=WorldSetting.create(work,category,"동명 대상","통행료","2전");world.addProperty("북문","통행료","3전");em.persist(world);
        }
        em.flush(); var book=upload("원문이 명시한 현재 설정");
        JsonNode claim=semanticClaim("SETTING_BOOK",200);
        var origin=jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        JsonNode input=states.getInputState(origin);
        assertSemanticCarrier(claim,input);
        var typeSet=new java.util.HashSet<String>();
        claim.path("semanticCurrentContext").path("characters").forEach(owner -> owner.path("currentSlots").forEach(slot -> typeSet.add(slot.path("factType").asText())));
        assertThat(typeSet).containsExactlyInAnyOrder(java.util.Arrays.stream(CharacterFactType.values()).map(Enum::name).toArray(String[]::new));
        assertThat(claim.path("semanticCurrentContext").path("characters")).hasSize(2);
        assertThat(claim.path("knownCharacters")).allSatisfy(owner -> assertThat(owner.path("activeStatuses")).isEmpty());
        assertThat(claim.path("semanticCurrentContext").path("worldSubjects")).hasSize(7);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("상한 오류로 실패한 순차 선행 작업의 같은 실행 후속 작업은 claim되지 않는다")
    void semanticCurrentFailureDoesNotBypassOrderedPredecessor() throws Exception {
        var batch=org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.create(work,work.getMember(),
                org.monitoring.catchholebackend.domain.upload.type.UploadType.MULTI_EPISODE_MULTI_FILE,
                org.monitoring.catchholebackend.domain.upload.type.UploadSourceType.FILE); em.persist(batch);
        var chain=new java.util.ArrayList<AnalysisJob>();
        for(int number=1;number<=2;number++) {
            var episode=org.monitoring.catchholebackend.domain.episode.entity.Episode.create(work,null,number,number+"화","synthetic/"+number,"v1","d".repeat(64),1);em.persist(episode);
            var job=AnalysisJob.create(work,batch,episode,org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION);
            job.configureReviewMode(org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC);chain.add(job);
        }
        states.initializeRun(chain); em.flush();
        var input=new org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal().emptyState();
        UUID id=UUID.randomUUID();var owner=input.withObject("characters").putObject("character:"+id);
        owner.put("actualCharacterId",id.toString()).putNull("provisionalSubjectKey").put("name","하린");
        var slots=owner.putObject("slots"); for(int i=0;i<4097;i++) slots.putObject("PROFILE:k"+i).put("factType","PROFILE").put("factKey","k"+i).putNull("factValue").putNull("valueJson");
        chain.getFirst().captureAutomaticInput(input);em.flush();
        semanticClaim("EPISODE",204);
        assertThat(chain.getFirst().getStatus().name()).isEqualTo("FAILED");
        semanticClaim("EPISODE",204);
        assertThat(chain.getLast().getStatus().name()).isEqualTo("PENDING");
        assertThat(chain.getLast().getClaimAttemptCount()).isZero();
        assertThat(chain.getLast().getPredecessorJobId()).isEqualTo(chain.getFirst().getId());
        assertThat(chain.getLast().getLeaseToken()).isNull();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("새 capability도 기존 Boolean 역직렬화 규약을 따르며 false는 문맥을 생략한다")
    void semanticCurrentCapabilityUsesExistingBooleanTransport() throws Exception {
        upload("문맥 원문");
        JsonNode claim=response(mvc.perform(post("/api/internal/v1/analysis-jobs/claim").header("X-Internal-Api-Key","test-internal-api-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"allowedJobTypes\":[\"SETTING_EXTRACTION\"],\"supportsFrozenComparisonEvidence\":true,\"supportedAnalysisModes\":[\"ORDERED_PROVISIONAL\"],\"supportedSourceKinds\":[\"SETTING_BOOK\"],\"supportsSettingBookReferenceTime\":true,\"supportsSemanticCurrentContext\":\"true\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(claim.path("semanticCurrentContext").path("complete").asBoolean()).isTrue();
        work=works.save(Work.create(work.getMember(),"false capability",WorkGenre.FANTASY,null));upload("다음 원문");
        JsonNode legacy=response(mvc.perform(post("/api/internal/v1/analysis-jobs/claim").header("X-Internal-Api-Key","test-internal-api-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"allowedJobTypes\":[\"SETTING_EXTRACTION\"],\"supportsFrozenComparisonEvidence\":true,\"supportedAnalysisModes\":[\"ORDERED_PROVISIONAL\"],\"supportedSourceKinds\":[\"SETTING_BOOK\"],\"supportsSettingBookReferenceTime\":true,\"supportsSemanticCurrentContext\":false}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(legacy.has("semanticCurrentContext")).isFalse();
    }

    private JsonNode semanticClaim(String source, int expected) throws Exception {
        return response(mvc.perform(post("/api/internal/v1/analysis-jobs/claim").header("X-Internal-Api-Key","test-internal-api-key")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "allowedJobTypes",java.util.List.of("SETTING_EXTRACTION"),"supportedAnalysisModes",java.util.List.of("ORDERED_PROVISIONAL"),
                        "supportedSourceKinds",java.util.List.of(source),"supportsSettingBookReferenceTime",true,"supportsSemanticCurrentContext",true,"supportsFrozenComparisonEvidence",true))))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }

    private com.fasterxml.jackson.databind.node.ObjectNode semanticFrozenState() {
        var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var input = new org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal().emptyState();
        for (int i=0;i<3;i++) {
            UUID id = UUID.randomUUID(); String ref = (i==2?"provisional-character:":"character:")+id;
            var owner = input.withObject("characters").putObject(ref);
            owner.put("name","한서린").putArray("aliases"); owner.putArray("protectedSlots"); owner.putArray("identityEvidence");
            owner.put("actualCharacterId",i==2?null:id.toString()).put("provisionalSubjectKey",i==2?ref:null);
            var provenance = owner.putObject("provenance"); provenance.put("confirmationStatus",i==2?"PROVISIONAL":"CONFIRMED");
            provenance.put("sourceEpisodeNo",1); provenance.putArray("sourceCandidateIds");
            var slots = owner.putObject("slots");
            for (var type: CharacterFactType.values()) {
                String key = type.name().toLowerCase(java.util.Locale.ROOT)+".키";
                var slot = slots.putObject(type.name()+":"+key).put("factType",type.name()).put("factKey",key);
                slot.put("factValue",type==CharacterFactType.ITEM?null:"고정 "+i+" "+type.name()+" 🪶");
                if (type==CharacterFactType.ITEM) slot.putNull("valueJson");
                else slot.set("valueJson",j.objectNode().put("value",i).put("active",false).putArray("labels").add("frozen"));
            }
        }
        int n=0;
        for (var category:WorldSettingCategory.values()) {
            UUID id=UUID.randomUUID(); boolean provisional=n++==6;
            String ref=(provisional?"provisional-world:":"world:")+id;
            var owner=input.withObject("worldSettings").putObject(ref);
            owner.put("actualWorldSettingId",provisional?null:id.toString()).put("provisionalSubjectKey",provisional?ref:null);
            owner.put("category",category.name()).put("subjectName","동명 대상");
            var properties=owner.putObject("propertiesJson"); properties.put("통행료","근거 원본 2전");
            properties.putObject("북문").put("통행료","근거 원본 3전");
        }
        return input;
    }

    private void assertSemanticCarrier(JsonNode claim,JsonNode input) {
        JsonNode carrier=claim.path("semanticCurrentContext"), context=claim.path("analysisContext");
        assertThat(carrier.isObject()).isTrue(); assertThat(carrier.path("complete").asBoolean()).isTrue();
        for(String field:java.util.List.of("formatVersion","runId","generation","inputStateHash","sourceHash")) assertThat(carrier.path(field)).isEqualTo(context.path(field));
        assertThat(carrier.path("inputStateHash").asText()).isEqualTo(new org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal().hash(input));
        var expected = new java.util.LinkedHashMap<String,JsonNode>();
        input.path("characters").forEach(owner -> owner.path("slots").forEach(slot -> expected.put(
                (owner.path("actualCharacterId").isNull()?owner.path("provisionalSubjectKey").asText():owner.path("actualCharacterId").asText())+":"+slot.path("factType").asText()+":"+slot.path("factKey").asText(),slot)));
        int count=0;
        for(JsonNode owner:carrier.path("characters")) {
            assertThat(owner.has("characterId")).isTrue();assertThat(owner.has("provisionalSubjectKey")).isTrue();
            for(JsonNode slot:owner.path("currentSlots")) {
                String key=(owner.path("characterId").isNull()?owner.path("provisionalSubjectKey").asText():owner.path("characterId").asText())+":"+slot.path("factType").asText()+":"+slot.path("factKey").asText();
                JsonNode original=expected.get(key); assertThat(original).isNotNull();
                assertThat(slot.has("factValue")).isTrue();assertThat(slot.has("valueJson")).isTrue();
                assertThat(slot.path("factValue")).isEqualTo(original.path("factValue"));assertThat(slot.path("valueJson")).isEqualTo(original.path("valueJson"));
                assertThat(slot.has("valueType")).isFalse();assertThat(slot.has("provenance")).isFalse();count++;
            }
        }
        assertThat(count).isEqualTo(expected.size());
        var claimActual=new java.util.HashSet<String>();claim.path("knownCharacters").forEach(owner -> claimActual.add(owner.path("characterId").asText()));
        var claimProvisional=new java.util.HashSet<String>();claim.path("provisionalCharacters").forEach(owner -> claimProvisional.add(owner.path("provisionalSubjectKey").asText()));
        for(JsonNode owner:carrier.path("characters")) {
            if(owner.path("characterId").isNull()) assertThat(claimProvisional).contains(owner.path("provisionalSubjectKey").asText());
            else assertThat(claimActual).contains(owner.path("characterId").asText());
        }
        for(JsonNode owner:carrier.path("worldSubjects")) {
            assertThat(owner.has("subjectId")).isTrue();assertThat(owner.has("provisionalSubjectKey")).isTrue();
            String ref=owner.path("subjectId").isNull()?owner.path("provisionalSubjectKey").asText():"world:"+owner.path("subjectId").asText();
            JsonNode original=input.path("worldSettings").path(ref);
            assertThat(owner.path("category")).isEqualTo(original.path("category"));assertThat(owner.path("subjectName")).isEqualTo(original.path("subjectName"));
            for(JsonNode property:owner.path("properties")) {
                JsonNode values=original.path("propertiesJson");
                if(!property.path("scopeName").isNull()) values=values.path(property.path("scopeName").asText());
                assertThat(property.path("value")).isEqualTo(values.path(property.path("settingName").asText()));
            }
            assertThat(owner.path("properties")).hasSize(2);
            assertThat(owner.path("properties").get(0).has("scopeName")).isTrue();
        }
    }

    @Test void standaloneUploadQueuesOneFrozenAutomaticSourceWithoutInventingEpisode() throws Exception {
        JsonNode book = upload("등장인물: 하린\n하린의 직업은 기사다.");
        assertThat(book.path("revisionNo").asInt()).isEqualTo(1);
        assertThat(book.path("latestAnalysisJob").path("status").asText()).isEqualTo("PENDING");
        assertThat(book.path("latestAnalysisJob").path("reviewMode").asText()).isEqualTo("AUTOMATIC");
        assertThat(book.path("latestAnalysisJob").path("source").path("sourceKind").asText()).isEqualTo("SETTING_BOOK");
        assertThat(book.path("latestAnalysisJob").path("episodeId").isNull()).isTrue();
        assertThat(book.path("latestAnalysisJob").path("episodes")).isEmpty();
        assertThat(sql.queryForObject("select count(*) from episodes where work_id=?", Integer.class, work.getId())).isZero();

        assertThat(sql.queryForObject("select count(*) from setting_book_revisions where setting_book_id=?", Integer.class, UUID.fromString(book.path("id").asText()))).isEqualTo(1);
        JsonNode batches=response(mvc.perform(get("/api/v1/works/{workId}/analysis-jobs/batches",work.getId()).header("Authorization",bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(batches.path("content")).hasSize(1);
        assertThat(batches.path("content").get(0).path("uploadType").asText()).isEqualTo("SETTING_BOOK");
        assertThat(batches.path("content").get(0).path("jobGroups").get(0).path("currentAnalysisJobIds").get(0).asText())
                .isEqualTo(book.path("latestAnalysisJob").path("id").asText());
    }

    @Test void referenceTimeIsFrozenAndReferenceOnlySaveCreatesRevisionWithoutAnalysis() throws Exception {
        String text = "하린은 기사다.";
        JsonNode book = response(mvc.perform(multipart("/api/v1/works/{workId}/setting-books", work.getId())
                .file(new MockMultipartFile("file", "시점설정.txt", MediaType.TEXT_PLAIN_VALUE, text.getBytes(StandardCharsets.UTF_8)))
                .param("referenceTimeKind", "EPISODE").param("referenceEpisodeNo", "30")
                .header("Authorization", bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(book.path("referenceTimeKind").asText()).isEqualTo("EPISODE");
        assertThat(book.path("referenceEpisodeNo").asInt()).isEqualTo(30);
        assertThat(book.path("latestAnalysisJob").path("source").path("referenceEpisodeNo").asInt()).isEqualTo(30);
        assertThat(sql.queryForObject("select count(*) from episodes where work_id=?", Integer.class, work.getId())).isZero();

        JsonNode originalClaim = claimBook();
        JsonNode originalInputState = jobs.findById(UUID.fromString(originalClaim.path("analysisJobId").asText())).orElseThrow().getAutomaticInputState().deepCopy();
        assertThat(originalClaim.path("analysisContext").path("referenceTimeKind").asText()).isEqualTo("EPISODE");
        assertThat(originalClaim.path("analysisContext").path("referenceEpisodeNo").asInt()).isEqualTo(30);

        JsonNode changed = response(mvc.perform(patch("/api/v1/works/{workId}/setting-books/{id}", work.getId(), book.path("id").asText())
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content(editRequest(book.path("id").asText(), json.writeValueAsString(Map.of("content", text, "referenceTimeKind", "WORK_START")))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(changed.path("revisionNo").asInt()).isEqualTo(2);
        assertThat(changed.path("contentHash").asText()).isEqualTo(book.path("contentHash").asText());
        assertThat(changed.path("referenceTimeKind").asText()).isEqualTo("WORK_START");
        assertThat(changed.path("referenceEpisodeNo").isNull()).isTrue();
        assertThat(changed.path("contentChangedSinceAnalysis").asBoolean()).isTrue();
        assertThat(jobs.count()).isEqualTo(1);
        JsonNode frozen = response(mvc.perform(get("/api/v1/works/{workId}/setting-books/{id}/revisions/{revision}",
                work.getId(), book.path("id").asText(), book.path("revisionId").asText()).header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(frozen.path("referenceTimeKind").asText()).isEqualTo("EPISODE");
        assertThat(frozen.path("referenceEpisodeNo").asInt()).isEqualTo(30);
        JsonNode same = response(mvc.perform(patch("/api/v1/works/{workId}/setting-books/{id}", work.getId(), book.path("id").asText())
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content(editRequest(book.path("id").asText(), json.writeValueAsString(Map.of("content", text, "referenceTimeKind", "WORK_START")))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(same.path("revisionId").asText()).isEqualTo(changed.path("revisionId").asText());
        assertThat(jobs.count()).isEqualTo(1);
        JsonNode analyzed = analyze(book.path("id").asText());
        assertThat(analyzed.path("source").path("referenceTimeKind").asText()).isEqualTo("WORK_START");
        assertThat(analyzed.path("source").path("episodeId").isNull()).isTrue();
        JsonNode claimed = claimBook();
        assertThat(claimed.path("settingBook").path("referenceTimeKind").asText()).isEqualTo("WORK_START");
        assertThat(claimed.path("settingBook").path("referenceEpisodeNo").isNull()).isTrue();
        assertThat(claimed.path("analysisContext").path("referenceTimeKind").asText()).isEqualTo("WORK_START");
        assertThat(claimed.path("analysisContext").path("inputStateHash").asText())
                .isNotEqualTo(originalClaim.path("analysisContext").path("inputStateHash").asText());
        var currentJob = jobs.findById(UUID.fromString(claimed.path("analysisJobId").asText())).orElseThrow();
        assertThat(currentJob.getAutomaticInputState().path("references").path("setting-book-input")
                .path("sourceReferenceTimeKind").asText()).isEqualTo("WORK_START");
        assertThat(currentJob.getStateJournal().path("sourceReferenceTimeKind").asText()).isEqualTo("WORK_START");
        String exportDirectory = System.getenv("GH221_EXPORT_DIR");
        if (exportDirectory != null) {
            java.nio.file.Path directory = java.nio.file.Path.of(exportDirectory);
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.writeString(directory.resolve("java-reference-wire.json"), json.writeValueAsString(Map.of(
                    "originalBookClaim", originalClaim, "referenceChangedBookClaim", claimed,
                    "inputManifest", currentJob.getAutomaticInputState().path("references").path("setting-book-input"),
                    "journal", currentJob.getStateJournal(), "originalInputState", originalInputState,
                    "referenceChangedInputState", currentJob.getAutomaticInputState())));
        }
    }

    @Test void invalidReferencePairsRejectBeforeStoringOrQueueing() throws Exception {
        for (String[] pair : new String[][] {{"EPISODE", null}, {"EPISODE", "0"}, {"WORK_START", "1"}, {"UNSPECIFIED", "1"}}) {
            var request = multipart("/api/v1/works/{workId}/setting-books", work.getId())
                    .file(new MockMultipartFile("file", "시점.txt", MediaType.TEXT_PLAIN_VALUE, "설정집".getBytes(StandardCharsets.UTF_8)))
                    .param("referenceTimeKind", pair[0]).header("Authorization", bearer);
            if (pair[1] != null) request.param("referenceEpisodeNo", pair[1]);
            mvc.perform(request).andExpect(status().isBadRequest());
        }
        assertThat(jobs.count()).isZero();
        assertThat(objects).isEmpty();
    }

    @Test void referenceTimeIsPresentInActualOpenApi() throws Exception {
        String wire = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode document = json.readTree(wire);
        for (String schema : java.util.List.of("SettingBookSummaryResponse", "SettingBookResponse",
                "SettingBookRevisionResponse", "AnalysisSourceResponse", "WorkerAnalysisSettingBookPayload",
                "WorkerAnalysisContextPayload", "SettingBookUpdateRequest")) {
            assertThat(document.path("components").path("schemas").path(schema).path("properties")
                    .has("referenceTimeKind")).as(schema).isTrue();
            assertThat(document.path("components").path("schemas").path(schema).path("properties")
                    .has("referenceEpisodeNo")).as(schema).isTrue();
        }
        assertThat(document.path("components").path("schemas").path("WorkerAnalysisJobClaimRequest")
                .path("properties").has("supportsSettingBookReferenceTime")).isTrue();
        for (var fields : Map.of("SettingCandidateResponse", java.util.List.of("currentSnapshotVersion", "applyToCurrentAvailable"),
                "WorldSettingCandidateResponse", java.util.List.of("currentWorldSettingVersion", "applyToCurrentAvailable"),
                "WorkerAnalysisProvenancePayload", java.util.List.of("currentSourceTimes", "currentValueProtected", "effectiveFromEpisodeNo"),
                "WorkerCharacterCurrentSnapshotEntry", java.util.List.of("provenance"),
                "WorkerAnalysisKnownCharacterPayload", java.util.List.of("protectedSlots")).entrySet()) {
            for (String field : fields.getValue()) assertThat(document.path("components").path("schemas")
                    .path(fields.getKey()).path("properties").has(field)).as(fields.getKey()+"."+field).isTrue();
        }
        String exportDirectory = System.getenv("GH221_EXPORT_DIR");
        if (exportDirectory != null) {
            java.nio.file.Path directory = java.nio.file.Path.of(exportDirectory);
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.writeString(directory.resolve("openapi.json"), wire);
        }
    }

    @Test void bookClaimRequiresReferenceTimeCapabilityEvenWhenSourceKindIsSupported() throws Exception {
        upload("하린은 기사다.");
        mvc.perform(post("/api/internal/v1/analysis-jobs/claim").header("X-Internal-Api-Key", "test-internal-api-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"allowedJobTypes\":[\"SETTING_EXTRACTION\"],\"supportsFrozenComparisonEvidence\":true,\"supportedAnalysisModes\":[\"ORDERED_PROVISIONAL\"],\"supportedSourceKinds\":[\"SETTING_BOOK\"]}"))
                .andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @CsvSource({"SETTING_EXTRACTION,missing", "SETTING_EXTRACTION,false", "SETTING_EXTRACTION,true",
            "CHARACTER_FACT_COMPARISON,missing", "CHARACTER_FACT_COMPARISON,false", "CHARACTER_FACT_COMPARISON,true",
            "WORLD_SETTING_COMPARISON,missing", "WORLD_SETTING_COMPARISON,false", "WORLD_SETTING_COMPARISON,true"})
    void allWorkerTypesGateBookReferenceCapabilityAndStillClaimEpisodes(String type, String capability) throws Exception {
        var jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.valueOf(type);
        var book = uploadReference("하린은 기사다. 엘프는 북부에 산다.", "EPISODE", 30);
        var bookOrigin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var episodeWork = works.save(Work.create(work.getMember(), "기존 회차 능력 협상", WorkGenre.FANTASY, null));
        var episode = org.monitoring.catchholebackend.domain.episode.entity.Episode.create(episodeWork, null, 1, "1화", "synthetic/capability.txt", "v1", "d".repeat(64), 10);
        em.persist(episode);
        var episodeOrigin = AnalysisJob.create(episodeWork, null, episode, org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION);
        em.persist(episodeOrigin);
        var bookJob = bookOrigin;
        var episodeJob = episodeOrigin;
        var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        if (jobType == org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.CHARACTER_FACT_COMPARISON) {
            var bookRow = SettingCandidate.createCharacterDiscovery(work, null, null, bookOrigin, "하린", "하린", null,
                    org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED, j.arrayNode(), java.math.BigDecimal.ONE, null);
            var episodeRow = SettingCandidate.createCharacterDiscovery(episodeWork, episode, null, episodeOrigin, "회차인물", "회차인물", null,
                    org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED, j.arrayNode(), java.math.BigDecimal.ONE, null);
            em.persist(bookRow); em.persist(episodeRow);
            bookJob = AnalysisJob.createCharacterFactComparison(bookRow); episodeJob = AnalysisJob.createCharacterFactComparison(episodeRow);
            em.persist(bookJob); em.persist(episodeJob);
        } else if (jobType == org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.WORLD_SETTING_COMPARISON) {
            var bookRow = WorldSettingCandidate.create(work, null, bookOrigin, WorldSettingCategory.RACE, "엘프", "서식지", "북부", j.arrayNode(), java.math.BigDecimal.ONE, null);
            var episodeRow = WorldSettingCandidate.create(episodeWork, episode, episodeOrigin, WorldSettingCategory.RACE, "회차종족", "서식지", "남부", j.arrayNode(), java.math.BigDecimal.ONE, null);
            em.persist(bookRow); em.persist(episodeRow);
            bookJob = AnalysisJob.createWorldSettingComparison(bookRow); episodeJob = AnalysisJob.createWorldSettingComparison(episodeRow);
            em.persist(bookJob); em.persist(episodeJob);
        }
        em.flush();
        var request = j.objectNode(); request.putArray("allowedJobTypes").add(type);
        request.put("supportsFrozenComparisonEvidence", true);
        request.putArray("supportedAnalysisModes").add("CONFIRMED_ONLY").add("ORDERED_PROVISIONAL");
        request.putArray("supportedSourceKinds").add("EPISODE").add("SETTING_BOOK");
        if (!capability.equals("missing")) request.put("supportsSettingBookReferenceTime", Boolean.parseBoolean(capability));
        var first = response(mvc.perform(post("/api/internal/v1/analysis-jobs/claim").header("X-Internal-Api-Key", "test-internal-api-key")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        if (capability.equals("true")) {
            var second = response(mvc.perform(post("/api/internal/v1/analysis-jobs/claim").header("X-Internal-Api-Key", "test-internal-api-key")
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(java.util.Set.of(first.path("analysisJobId").asText(), second.path("analysisJobId").asText()))
                    .containsExactlyInAnyOrder(bookJob.getId().toString(), episodeJob.getId().toString());
            assertThat(java.util.Set.of(first.path("sourceKind").asText(), second.path("sourceKind").asText()))
                    .containsExactlyInAnyOrder("SETTING_BOOK", "EPISODE");
        } else {
            assertThat(first.path("analysisJobId").asText()).isEqualTo(episodeJob.getId().toString());
            assertThat(first.path("sourceKind").asText()).isEqualTo("EPISODE");
            mvc.perform(post("/api/internal/v1/analysis-jobs/claim").header("X-Internal-Api-Key", "test-internal-api-key")
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request))).andExpect(status().isNoContent());
            assertThat(bookJob.getStatus()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING);
            request.put("supportsSettingBookReferenceTime", true);
            var supported = response(mvc.perform(post("/api/internal/v1/analysis-jobs/claim").header("X-Internal-Api-Key", "test-internal-api-key")
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(supported.path("analysisJobId").asText()).isEqualTo(bookJob.getId().toString());
            assertThat(supported.path("sourceKind").asText()).isEqualTo("SETTING_BOOK");
        }
        assertThat(bookJob.getStatus()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING);
        assertThat(episodeJob.getStatus()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING);
    }

    @Test void unscopedMissingCandidatesKeepTheirExistingDomainErrorCodes() throws Exception {
        JsonNode book=upload("하린은 기사다.");
        String batch=jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow().getBatch().getId().toString();
        mvc.perform(get("/api/v1/works/{workId}/setting-candidates/{id}",work.getId(),UUID.randomUUID())
                .header("Authorization",bearer).param("batchId",batch)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SETTING_CANDIDATE_NOT_FOUND"));
        mvc.perform(get("/api/v1/works/{workId}/world-setting-candidates/{id}",work.getId(),UUID.randomUUID())
                .header("Authorization",bearer).param("batchId",batch)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORLD_SETTING_CANDIDATE_NOT_FOUND"));
    }

    @Test void workerMustAdvertiseBookCapabilityAndReceivesFrozenManifest() throws Exception {
        JsonNode book = upload("하린은 기사다.");
        mvc.perform(post("/api/internal/v1/analysis-jobs/claim").header("X-Internal-Api-Key","test-internal-api-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"allowedJobTypes\":[\"SETTING_EXTRACTION\"],\"supportsFrozenComparisonEvidence\":true,\"supportedAnalysisModes\":[\"ORDERED_PROVISIONAL\"]}"))
                .andExpect(status().isNoContent());
        for (String capability : java.util.List.of("null","[]")) {
            mvc.perform(post("/api/internal/v1/analysis-jobs/claim").header("X-Internal-Api-Key","test-internal-api-key")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"allowedJobTypes\":[\"SETTING_EXTRACTION\"],\"supportsFrozenComparisonEvidence\":true,\"supportedAnalysisModes\":[\"ORDERED_PROVISIONAL\"],\"supportedSourceKinds\":"+capability+"}"))
                    .andExpect(status().isNoContent());
        }
        JsonNode claimed = response(mvc.perform(post("/api/internal/v1/analysis-jobs/claim").header("X-Internal-Api-Key","test-internal-api-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"allowedJobTypes\":[\"SETTING_EXTRACTION\"],\"supportsFrozenComparisonEvidence\":true,\"supportedAnalysisModes\":[\"ORDERED_PROVISIONAL\"],\"supportedSourceKinds\":[\"EPISODE\",\"SETTING_BOOK\"],\"supportsSettingBookReferenceTime\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(claimed.path("sourceKind").asText()).isEqualTo("SETTING_BOOK");
        assertThat(claimed.path("episode").isNull()).isTrue();
        assertThat(claimed.path("settingBook").path("settingBookId").asText()).isEqualTo(book.path("id").asText());
        assertThat(claimed.path("settingBook").path("revisionId").asText()).isEqualTo(book.path("revisionId").asText());
        assertThat(claimed.path("analysisContext").path("sourceHash").asText()).isEqualTo(book.path("contentHash").asText());
        var job = jobs.findById(java.util.UUID.fromString(claimed.path("analysisJobId").asText())).orElseThrow();
        JsonNode record = job.getStateJournal();
        assertThat(record.path("sourceKind").asText()).isEqualTo("SETTING_BOOK");
        assertThat(record.path("episodeNo").isNull()).isTrue();
        assertThat(record.path("sourceSettingBookId").asText()).isEqualTo(book.path("id").asText());
        assertThat(record.path("sourceSettingBookRevisionId").asText()).isEqualTo(book.path("revisionId").asText());
        assertThat(record.path("sourceSettingBookRevisionNo").asInt()).isEqualTo(book.path("revisionNo").asInt());
    }

    @Test void saveInvalidatesOldRunWithoutQueueingAndExplicitAnalysisUsesLatestRevision() throws Exception {
        JsonNode book = upload("하린은 기사다.");
        String id = book.path("id").asText();
        String firstJob = book.path("latestAnalysisJob").path("id").asText();
        JsonNode second = response(mvc.perform(patch("/api/v1/works/{workId}/setting-books/{id}",work.getId(),id)
                .header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
                .content(editRequest(id, "{\"content\":\"하린은 마법사다.\"}")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(second.path("revisionNo").asInt()).isEqualTo(2);
        assertThat(second.path("contentChangedSinceAnalysis").asBoolean()).isTrue();
        assertThat(sql.queryForObject("select status from analysis_jobs where id=?",String.class,UUID.fromString(firstJob))).isEqualTo("CANCELED");
        assertThat(sql.queryForObject("select count(*) from analysis_jobs where work_id=?",Integer.class,work.getId())).isEqualTo(1);
        JsonNode reanalysis = analyze(id);
        assertThat(reanalysis.path("source").path("revisionId").asText()).isEqualTo(second.path("revisionId").asText());
        assertThat(analyze(id).path("id").asText()).isEqualTo(reanalysis.path("id").asText());
        assertThat(sql.queryForObject("select count(*) from analysis_jobs where work_id=?",Integer.class,work.getId())).isEqualTo(2);
        assertThat(objects.values()).anySatisfy(value -> assertThat(new String(value,StandardCharsets.UTF_8)).isEqualTo("하린은 기사다."));
    }

    @Test void completedBookCreatesCharacterAndWorldAndEpisodeOneReadsKnownBookProvenance() throws Exception {
        seedCompletedBookStateAndVerifyEpisodeInput();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("검증된 설정집의 별도 동일명 프로필과 자식 근거는 두 인물로 자동 승격한다")
    void validatedBookProfilesCreateSeparateSameNameCharactersWithOwnedFacts() throws Exception {
        var fixture = sameNameBookFixture(true);
        finishIdentityFixture(fixture);
        assertThat(sql.queryForObject("select count(*) from characters where work_id=?", Integer.class, work.getId())).isEqualTo(2);
        assertThat(fixture.discoveries()).allSatisfy(row -> {
            assertThat(row.getReviewStatus().name()).isEqualTo("CONFIRMED");
            assertThat(row.isReviewedAutomatically()).isTrue();
        });
        assertThat(fixture.children()).allSatisfy(row -> assertThat(row.getReviewStatus().name()).isEqualTo("CONFIRMED"));
        UUID first = fixture.discoveries().get(0).getMatchedCharacterId();
        UUID second = fixture.discoveries().get(1).getMatchedCharacterId();
        assertThat(first).isNotEqualTo(second);
        assertThat(em.find(WorkCharacter.class, first).getCurrentAge()).isEqualTo(27);
        assertThat(em.find(WorkCharacter.class, second).getCurrentAge()).isEqualTo(34);
        assertThat(fixture.children().get(0).getMatchedCharacterId()).isEqualTo(first);
        assertThat(fixture.children().get(1).getMatchedCharacterId()).isEqualTo(second);
        assertThat(sql.queryForObject("select count(*) from character_facts where extracted_by_job_id=?", Integer.class, fixture.origin().getId())).isEqualTo(2);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("별도 동일명 증명이 없는 설정집은 기존 이름 충돌 검토를 유지한다")
    void sameNameBookWithoutProofKeepsBothProfilesInReview() throws Exception {
        var fixture = sameNameBookFixture(false);
        finishIdentityFixture(fixture);
        assertThat(sql.queryForObject("select count(*) from characters where work_id=?", Integer.class, work.getId())).isZero();
        java.util.stream.Stream.concat(fixture.discoveries().stream(), fixture.children().stream()).forEach(row -> {
            assertThat(row.getReviewStatus().name()).isEqualTo("PENDING_REVIEW");
            assertThat(row.getAutomaticReviewHoldReason().name()).isEqualTo("SUBJECT_CONFIRMATION_REQUIRED");
        });
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"한서린", "  한서린  "})
    void sameNameBookCharacterFullUpdateChangesOnlySelectedSlotAndPreservesOtherIdentity(String requestedName) throws Exception {
        var fixture = sameNameBookFixture(true); finishIdentityFixture(fixture);
        var selected = em.find(WorkCharacter.class, fixture.discoveries().get(0).getMatchedCharacterId());
        var other = em.find(WorkCharacter.class, fixture.discoveries().get(1).getMatchedCharacterId());
        seedHomonymProfile(selected, "은펜", "은등", "선원 한서린의 동료"); seedHomonymProfile(other, "구리펜", "물새", "기록관 한서린의 동료");
        JsonNode selectedBefore = characterDetail(selected.getId()); JsonNode otherBefore = characterDetail(other.getId());
        long otherVersion = other.getSnapshotVersion(); JsonNode otherMarkers = other.getManuallyEditedSlots().deepCopy();
        var selectedFacts = sql.queryForList("select id from character_facts where character_id=? order by id", UUID.class, selected.getId());
        var otherFacts = sql.queryForList("select id from character_facts where character_id=? order by id", UUID.class, other.getId());
        JsonNode journal = fixture.origin().getStateJournal().deepCopy();
        var body = fullCharacterUpdate(selectedBefore, requestedName);
        body.path("profile").forEach(row -> { if (row.path("key").asText().equals("profile.test_pen")) ((com.fasterxml.jackson.databind.node.ObjectNode) row).put("value", "흑연필"); });
        JsonNode saved = response(mvc.perform(patch("/api/v1/works/{workId}/characters/{id}", work.getId(), selected.getId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(saved.path("id").asText()).isEqualTo(selected.getId().toString()); assertThat(saved.path("name").asText()).isEqualTo("한서린");
        assertThat(saved.path("currentAge")).isEqualTo(selectedBefore.path("currentAge")); assertThat(saved.path("currentAgeSourceFacts")).isEqualTo(selectedBefore.path("currentAgeSourceFacts"));
        assertThat(profileRow(saved, "profile.test_pen").path("value").asText()).isEqualTo("흑연필");
        for (String key : java.util.List.of("profile.test_alias", "profile.test_relation")) assertThat(profileRow(saved, key)).isEqualTo(profileRow(selectedBefore, key));
        for (String field : java.util.List.of("roleLabel", "currentLevel", "currentLevelSourceFacts", "firstAppearanceEpisode", "stats", "skills", "items", "statuses"))
            assertThat(saved.path(field)).isEqualTo(selectedBefore.path(field));
        assertThat(characterDetail(other.getId())).isEqualTo(otherBefore); assertThat(other.getSnapshotVersion()).isEqualTo(otherVersion);
        assertThat(other.getManuallyEditedSlots()).isEqualTo(otherMarkers);
        assertThat(sql.queryForList("select id from character_facts where character_id=? order by id", UUID.class, other.getId())).containsExactlyElementsOf(otherFacts);
        assertThat(sql.queryForList("select id from character_facts where character_id=? order by id", UUID.class, selected.getId())).containsAll(selectedFacts).hasSize(selectedFacts.size()+1);
        assertThat(selected.isManuallyEditedSlot(CharacterFactType.PROFILE, "profile.test_pen")).isTrue();
        assertThat(selected.isManuallyEditedSlot(CharacterFactType.PROFILE, "profile.test_alias")).isFalse();
        assertThat(selected.isManuallyEditedSlot(CharacterFactType.PROFILE, "profile.test_relation")).isFalse();
        assertThat(selected.isManuallyEditedSlot(CharacterFactType.AGE, "age")).isFalse();
        assertThat(fixture.origin().getStateJournal()).isEqualTo(journal);
        assertThat(fixture.discoveries()).allSatisfy(row -> { assertThat(row.getEntityName()).isEqualTo("한서린"); assertThat(row.getReviewStatus().name()).isEqualTo("CONFIRMED"); });
        assertThat(sql.queryForObject("select count(*) from characters where work_id=? and name='한서린'", Integer.class, work.getId())).isEqualTo(2);
        // Sending the unchanged full DTO again is a no-op, including a whitespace-normalized name.
        long selectedVersion = selected.getSnapshotVersion();
        mvc.perform(patch("/api/v1/works/{workId}/characters/{id}", work.getId(), selected.getId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(fullCharacterUpdate(saved, requestedName)))).andExpect(status().isOk());
        assertThat(selected.getSnapshotVersion()).isEqualTo(selectedVersion);
        assertThat(sql.queryForObject("select count(*) from character_facts where character_id=?", Integer.class, selected.getId())).isEqualTo(selectedFacts.size()+1);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"한서린", "  한서린  "})
    void sameNameCharacterRenameStillRejectsNormalizedCollisionWithoutChangingAnyIdentity(String requestedName) throws Exception {
        var fixture = sameNameBookFixture(true); finishIdentityFixture(fixture);
        var renamed = WorkCharacter.create(work, "도윤", "기록관", 42, null, null, null, null, null, null, null); em.persist(renamed); em.flush();
        JsonNode before = characterDetail(renamed.getId());
        var others = fixture.discoveries().stream().map(row -> characterDetailUnchecked(row.getMatchedCharacterId())).toList();
        var facts = sql.queryForList("select id from character_facts where extracted_by_job_id=? order by id", UUID.class, fixture.origin().getId());
        long version = renamed.getSnapshotVersion();
        mvc.perform(patch("/api/v1/works/{workId}/characters/{id}", work.getId(), renamed.getId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(fullCharacterUpdate(before, requestedName))))
                .andExpect(status().isConflict()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("CHARACTER_NAME_DUPLICATED"));
        assertThat(characterDetail(renamed.getId())).isEqualTo(before); assertThat(renamed.getSnapshotVersion()).isEqualTo(version);
        for (int i=0;i<2;i++) assertThat(characterDetail(fixture.discoveries().get(i).getMatchedCharacterId())).isEqualTo(others.get(i));
        assertThat(sql.queryForList("select id from character_facts where extracted_by_job_id=? order by id", UUID.class, fixture.origin().getId())).containsExactlyElementsOf(facts);
        assertThat(sql.queryForObject("select count(*) from characters where work_id=?", Integer.class, work.getId())).isEqualTo(3);
    }

    void seedHomonymProfile(WorkCharacter character, String pen, String alias, String relation) {
        var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var values = Map.of("profile.test_pen", pen, "profile.test_alias", alias, "profile.test_relation", relation);
        var entries = new java.util.HashMap<>(snapshotAccessor.read(character));
        values.forEach((key,value) -> {
            if (sql.queryForObject("select count(*) from character_setting_schemas where work_id=? and schema_key=?", Integer.class, work.getId(), key)==0)
                em.persist(org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema.create(work, key, null, key,
                        CharacterFactType.PROFILE, org.monitoring.catchholebackend.domain.character.type.SettingValueType.STRING,
                        org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics.BASE_VALUE,
                        org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy.REPLACE, j.arrayNode(),
                        org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource.SYSTEM_SEED, true));
            var valueJson = j.objectNode().put("value", value);
            em.persist(org.monitoring.catchholebackend.domain.character.entity.CharacterFact.createManual(character, CharacterFactType.PROFILE, key, value, valueJson));
            var entry = snapshotAccessor.entry(CharacterFactType.PROFILE, key, value, valueJson); entries.put(entry.slot(), entry);
        });
        snapshotAccessor.replace(character, entries, true); em.flush();
    }

    JsonNode characterDetail(UUID id) throws Exception {
        return response(mvc.perform(get("/api/v1/works/{workId}/characters/{id}", work.getId(), id).header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    JsonNode characterDetailUnchecked(UUID id) { try { return characterDetail(id); } catch (Exception error) { throw new IllegalStateException(error); } }
    JsonNode profileRow(JsonNode detail, String key) {
        for (var row : detail.path("profile")) if (key.equals(row.path("key").asText())) return row;
        throw new AssertionError("Expected saved profile slot "+key);
    }
    com.fasterxml.jackson.databind.node.ObjectNode fullCharacterUpdate(JsonNode detail, String requestedName) {
        var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance; var body = j.objectNode().put("name", requestedName);
        for (String field : java.util.List.of("roleLabel", "currentAge", "currentLevel")) body.set(field, detail.path(field).deepCopy());
        body.set("firstAppearanceEpisodeNo", detail.path("firstAppearanceEpisode").path("episodeNo").isMissingNode() ? j.nullNode() : detail.path("firstAppearanceEpisode").path("episodeNo").deepCopy());
        for (String field : java.util.List.of("profile", "stats", "skills", "items", "statuses")) {
            var rows = body.putArray(field);
            detail.path(field).forEach(row -> { var input = j.objectNode(); for (String key : java.util.List.of("key", "value", "valueType", "properties")) input.set(key, row.path(key).deepCopy()); rows.add(input); });
        }
        return body;
    }

    @Test
    @org.junit.jupiter.api.DisplayName("동일명 증명이 정상이어도 후보 자체의 다른 설정집 출처는 승격하지 않는다")
    void bookIdentityProofRejectsForeignCandidateSourceBinding() throws Exception {
        var fixture = sameNameBookFixture(true);
        var foreignBook = uploadReference("다른 설정집", "WORK_START", null);
        var foreign = jobs.findById(UUID.fromString(foreignBook.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var child = fixture.children().get(0);
        org.springframework.test.util.ReflectionTestUtils.setField(child, "sourceSettingBook", foreign.getSourceSettingBook());
        org.springframework.test.util.ReflectionTestUtils.setField(child, "sourceSettingBookRevision", foreign.getSourceSettingBookRevision());
        org.springframework.test.util.ReflectionTestUtils.setField(child, "sourceContentS3Key", foreign.getSourceContentS3Key());
        finishIdentityFixture(fixture);
        assertThat(child.getReviewStatus().name()).isEqualTo("PENDING_REVIEW");
        assertThat(child.getAutomaticReviewHoldReason().name()).isEqualTo("SUBJECT_CONFIRMATION_REQUIRED");
        assertThat(fixture.children().get(1).getReviewStatus().name()).isEqualTo("CONFIRMED");
        assertThat(em.find(WorkCharacter.class, fixture.discoveries().get(0).getMatchedCharacterId()).getCurrentAge()).isNull();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("자동 승격 후 이름 인용만으로는 잃는 구분 프로필을 다음 frozen 문맥에 보존한다")
    void confirmedBookIdentityKeepsDiscriminatingProfileForFutureActualReconnect() throws Exception {
        var fixture = sameNameBookFixture(true);
        for (var discovery : fixture.discoveries()) {
            JsonNode parent = discovery.getRawAiResultJson().path("setting_book_identity_proof").path("members").get(fixture.discoveries().indexOf(discovery)).path("profileEvidence");
            int parentStart = fixture.content().offsetByCodePoints(0, parent.path("startOffset").asInt());
            int nameStart = fixture.content().indexOf("한서린", parentStart);
            int offset = fixture.content().codePointCount(0, nameStart);
            var evidence = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add(
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("quote", "한서린").put("startOffset", offset).put("endOffset", offset + 3));
            org.springframework.test.util.ReflectionTestUtils.setField(discovery, "evidenceSpans", evidence);
        }
        finishIdentityFixture(fixture);
        analyze(fixture.origin().getSourceSettingBook().getId().toString());
        var claim = claimBook();
        assertThat(claim.path("knownCharacters")).hasSize(2);
        assertThat(claim.path("knownCharacters")).allSatisfy(target -> assertThat(target.path("identityEvidence"))
                .anySatisfy(span -> assertThat(span.path("quote").asText()).contains("프로필", "나이:")));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"version", "largeVersion", "producer", "decision", "group", "name", "missingReference", "reference",
            "origin", "revision", "hash", "unknownTarget", "duplicateMember", "overlap", "quote", "noDistinct", "unknownField", "samePerson"})
    @org.junit.jupiter.api.DisplayName("불완전하거나 위조된 동일명 원문 증명은 이름 충돌 검토를 유지한다")
    void invalidBookIdentityProofKeepsWholeSameNameGroupInReview(String invalid) throws Exception {
        var fixture = sameNameBookFixture(true);
        java.util.stream.Stream.concat(fixture.discoveries().stream(), fixture.children().stream()).forEach(candidate -> {
            var proof = (com.fasterxml.jackson.databind.node.ObjectNode) candidate.getRawAiResultJson().path("setting_book_identity_proof");
            var members = (com.fasterxml.jackson.databind.node.ArrayNode) proof.path("members");
            switch (invalid) {
                case "version" -> proof.put("version", 2);
                case "largeVersion" -> proof.put("version", 4294967297L);
                case "producer" -> proof.put("producer", "MODEL");
                case "decision" -> proof.putNull("identityDecision");
                case "group" -> proof.put("groupId", "G0");
                case "name" -> proof.put("normalizedName", "서린");
                case "missingReference" -> proof.remove("referenceEpisodeNo");
                case "reference" -> proof.put("referenceTimeKind", "UNSPECIFIED");
                case "origin" -> proof.put("sourceAnalysisJobId", UUID.randomUUID().toString());
                case "revision" -> proof.put("revisionId", UUID.randomUUID().toString());
                case "hash" -> proof.put("contentHash", "a".repeat(64));
                case "unknownTarget" -> ((com.fasterxml.jackson.databind.node.ObjectNode) members.get(1)).put("targetRef", "character:" + UUID.randomUUID());
                case "duplicateMember" -> members.set(1, members.get(0).deepCopy());
                case "overlap" -> ((com.fasterxml.jackson.databind.node.ObjectNode) members.get(1)).set("profileEvidence", members.get(0).path("profileEvidence").deepCopy());
                case "quote" -> ((com.fasterxml.jackson.databind.node.ObjectNode) members.get(0).path("profileEvidence")).put("quote", "원문에 없는 프로필");
                case "noDistinct" -> proof.putArray("distinctEvidenceSpans");
                case "unknownField" -> proof.put("allowNameCollision", true);
                case "samePerson" -> proof.put("identityDecision", "SAME_IDENTITY");
                default -> throw new IllegalArgumentException(invalid);
            }
        });
        finishIdentityFixture(fixture);
        assertThat(sql.queryForObject("select count(*) from characters where work_id=?", Integer.class, work.getId())).isZero();
        java.util.stream.Stream.concat(fixture.discoveries().stream(), fixture.children().stream()).forEach(candidate -> {
            assertThat(candidate.getReviewStatus().name()).isEqualTo("PENDING_REVIEW");
            assertThat(candidate.getAutomaticReviewHoldReason().name()).isEqualTo("SUBJECT_CONFIRMATION_REQUIRED");
        });
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"wrongParent", "wrongEvidence", "mixedEvidence", "missingEvidence", "userModified"})
    @org.junit.jupiter.api.DisplayName("동일명 그룹 검증은 다른 프로필의 자식이나 사용자 수정 후보를 승인하지 않는다")
    void bookIdentityProofValidatesEachChildOwnershipIndependently(String invalid) throws Exception {
        var fixture = sameNameBookFixture(true);
        var child = fixture.children().get(0);
        switch (invalid) {
            case "wrongParent" -> ((com.fasterxml.jackson.databind.node.ObjectNode) child.getRawAiResultJson().path("setting_book_identity_proof"))
                    .put("parentTargetRef", fixture.discoveries().get(1).getProvisionalSubjectKey());
            case "wrongEvidence" -> org.springframework.test.util.ReflectionTestUtils.setField(child, "evidenceSpans", fixture.children().get(1).getEvidenceSpans().deepCopy());
            case "mixedEvidence" -> ((com.fasterxml.jackson.databind.node.ArrayNode) child.getEvidenceSpans()).add(fixture.children().get(1).getEvidenceSpans().get(0).deepCopy());
            case "missingEvidence" -> org.springframework.test.util.ReflectionTestUtils.setField(child, "evidenceSpans", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode());
            case "userModified" -> org.springframework.test.util.ReflectionTestUtils.setField(child, "userModified", true);
            default -> throw new IllegalArgumentException(invalid);
        }
        finishIdentityFixture(fixture);
        assertThat(child.getReviewStatus().name()).isEqualTo("PENDING_REVIEW");
        assertThat(child.getAutomaticReviewHoldReason()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.SUBJECT_CONFIRMATION_REQUIRED);
        assertThat(fixture.children().get(1).getReviewStatus().name()).isEqualTo("CONFIRMED");
        assertThat(em.find(WorkCharacter.class, fixture.discoveries().get(0).getMatchedCharacterId()).getCurrentAge()).isNull();
        assertThat(em.find(WorkCharacter.class, fixture.discoveries().get(1).getMatchedCharacterId()).getCurrentAge()).isEqualTo(34);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("다음 설정집은 제공된 동일명 실제 인물 둘에 재연결하고 대체 인물을 만들지 않는다")
    void bookIdentityProofReconnectsBothSuppliedActualCharactersWithoutReplacements() throws Exception {
        var first = sameNameBookFixture(true); finishIdentityFixture(first);
        var actual = first.discoveries().stream().map(candidate -> em.find(WorkCharacter.class, candidate.getMatchedCharacterId())).toList();
        var next = sameNameBookFixture(true, actual, "EPISODE", 2); finishIdentityFixture(next);
        assertThat(sql.queryForObject("select count(*) from characters where work_id=?", Integer.class, work.getId())).isEqualTo(2);
        for (int i = 0; i < 2; i++) {
            assertThat(next.discoveries().get(i).getMatchedCharacterId()).isEqualTo(actual.get(i).getId());
            assertThat(next.children().get(i).getMatchedCharacterId()).isEqualTo(actual.get(i).getId());
            assertThat(next.children().get(i).getReviewStatus().name()).isEqualTo("CONFIRMED");
        }
        assertThat(actual.get(0).getCurrentAge()).isEqualTo(28);
        assertThat(actual.get(1).getCurrentAge()).isEqualTo(35);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"manual", "future"})
    @org.junit.jupiter.api.DisplayName("유효한 실제 동일명 재연결도 직접 수정과 미래 계획 보호를 우회하지 않는다")
    void verifiedBookIdentityDoesNotBypassCurrentTimeOrManualProtection(String protectedValue) throws Exception {
        var first = sameNameBookFixture(true); finishIdentityFixture(first);
        var actual = first.discoveries().stream().map(candidate -> em.find(WorkCharacter.class, candidate.getMatchedCharacterId())).toList();
        if (protectedValue.equals("manual")) actual.get(0).protectManualSlot(CharacterFactType.AGE, "age");
        var next = sameNameBookFixture(true, actual, "EPISODE", 2);
        if (protectedValue.equals("future")) org.springframework.test.util.ReflectionTestUtils.setField(next.children().get(0), "temporalScope", CharacterFactTemporalScope.HYPOTHETICAL);
        finishIdentityFixture(next);
        assertThat(actual.get(0).getCurrentAge()).isEqualTo(27);
        assertThat(next.children().get(0).getReviewStatus().name()).isEqualTo("PENDING_REVIEW");
        assertThat(next.children().get(0).getAutomaticReviewHoldReason().name()).isEqualTo(protectedValue.equals("manual")
                ? "HUMAN_CURRENT_SETTING_PROTECTED" : "SETTING_TIME_CONFIRMATION_REQUIRED");
        assertThat(actual.get(1).getCurrentAge()).isEqualTo(35);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"noProof", "newInsteadOfExisting", "missingKnownMember", "staleProof", "mixedTargets"})
    @org.junit.jupiter.api.DisplayName("실제 동일명 인물은 전체 제공된 대상 증명 없이 새 인물 생성이나 재연결을 하지 않는다")
    void bookIdentityReconnectRejectsIncompleteOrReplacementTargets(String invalid) throws Exception {
        var first = sameNameBookFixture(true); finishIdentityFixture(first);
        var actual = first.discoveries().stream().map(candidate -> em.find(WorkCharacter.class, candidate.getMatchedCharacterId())).toList();
        var next = invalid.equals("newInsteadOfExisting") ? sameNameBookFixture(true)
                : sameNameBookFixture(!invalid.equals("noProof"), actual, "EPISODE", 2);
        if (invalid.equals("missingKnownMember") || invalid.equals("staleProof") || invalid.equals("mixedTargets"))
            java.util.stream.Stream.concat(next.discoveries().stream(), next.children().stream()).forEach(candidate -> {
                var proof = (com.fasterxml.jackson.databind.node.ObjectNode) candidate.getRawAiResultJson().path("setting_book_identity_proof");
                if (invalid.equals("missingKnownMember")) ((com.fasterxml.jackson.databind.node.ArrayNode) proof.path("members")).remove(1);
                else if (invalid.equals("staleProof")) proof.set("members", first.discoveries().get(0).getRawAiResultJson().path("setting_book_identity_proof").path("members").deepCopy());
                else ((com.fasterxml.jackson.databind.node.ObjectNode) proof.path("members").get(1)).put("targetRef", "provisional-character:" + next.discoveries().get(1).getId());
            });
        finishIdentityFixture(next);
        assertThat(sql.queryForObject("select count(*) from characters where work_id=?", Integer.class, work.getId())).isEqualTo(2);
        assertThat(actual.get(0).getCurrentAge()).isEqualTo(27);
        assertThat(actual.get(1).getCurrentAge()).isEqualTo(34);
        java.util.stream.Stream.concat(next.discoveries().stream(), next.children().stream()).forEach(candidate -> {
            assertThat(candidate.getReviewStatus().name()).isEqualTo("PENDING_REVIEW");
            assertThat(candidate.getAutomaticReviewHoldReason()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.SUBJECT_CONFIRMATION_REQUIRED);
        });
    }

    @Test
    @org.junit.jupiter.api.DisplayName("프로필 생성 순서를 바꿔도 검증된 부모 키와 자식 귀속은 유지한다")
    void sameNameBookIdentityUsesParentKeysWhenDiscoveryOrderChanges() throws Exception {
        var fixture = sameNameBookFixture(true);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.discoveries().get(0), "createdAt", java.time.LocalDateTime.now().plusSeconds(1));
        finishIdentityFixture(fixture);
        for (int i = 0; i < 2; i++) assertThat(fixture.children().get(i).getMatchedCharacterId()).isEqualTo(fixture.discoveries().get(i).getMatchedCharacterId());
        assertThat(em.find(WorkCharacter.class, fixture.discoveries().get(0).getMatchedCharacterId()).getCurrentAge()).isEqualTo(27);
        assertThat(em.find(WorkCharacter.class, fixture.discoveries().get(1).getMatchedCharacterId()).getCurrentAge()).isEqualTo(34);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("같은 위치의 반복 발견을 일부 증명 그룹 밖에 두고 별도 인물로 승격하지 않는다")
    void bookIdentityProofMustCoverEverySameNameDiscoveryIncludingRepeatedEvidence() throws Exception {
        var fixture = sameNameBookFixture(true);
        var first = fixture.discoveries().get(0);
        var repeated = SettingCandidate.createCharacterDiscovery(work, null, null, fixture.origin(), "한서린", "서린", null,
                org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED,
                first.getEvidenceSpans().deepCopy(), java.math.BigDecimal.ONE, first.getRawAiResultJson().deepCopy());
        em.persist(repeated); em.flush();
        org.springframework.test.util.ReflectionTestUtils.setField(repeated, "provisionalSubjectKey", "provisional-character:" + repeated.getId());
        finishIdentityFixture(fixture);
        assertThat(sql.queryForObject("select count(*) from characters where work_id=?", Integer.class, work.getId())).isZero();
        assertThat(repeated.getReviewStatus().name()).isEqualTo("PENDING_REVIEW");
        assertThat(repeated.getAutomaticReviewHoldReason()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.SUBJECT_CONFIRMATION_REQUIRED);
        assertThat(fixture.children()).allSatisfy(candidate -> assertThat(candidate.getReviewStatus().name()).isEqualTo("PENDING_REVIEW"));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Worker가 보존한 snake case 인용 offset도 Unicode 원문과 부모 귀속을 검증한다")
    void validatedBookIdentityReadsWorkerSnakeCaseCandidateEvidence() throws Exception {
        var fixture = sameNameBookFixture(true);
        java.util.stream.Stream.concat(fixture.discoveries().stream(), fixture.children().stream()).forEach(candidate -> candidate.getEvidenceSpans().forEach(value -> {
            var span = (com.fasterxml.jackson.databind.node.ObjectNode) value;
            span.set("start_offset", span.remove("startOffset")); span.set("end_offset", span.remove("endOffset"));
        }));
        finishIdentityFixture(fixture);
        assertThat(sql.queryForObject("select count(*) from characters where work_id=?", Integer.class, work.getId())).isEqualTo(2);
        assertThat(fixture.children()).allSatisfy(candidate -> assertThat(candidate.getReviewStatus().name()).isEqualTo("CONFIRMED"));
    }

    @Autowired org.monitoring.catchholebackend.domain.character.service.SettingCandidatePromotionService characterPromotion;

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"confirm", "promotion"})
    @org.junit.jupiter.api.DisplayName("증명으로 만든 실제 동명 두 인물은 일반 이름 검색에서 임의 선택하지 않고 충돌로 거절한다")
    void ordinaryNameOnlyResolutionStillRejectsTwoVerifiedActualCharacters(String path) throws Exception {
        var first = sameNameBookFixture(true); finishIdentityFixture(first);
        var origin = completedManualEpisodeOrigin(1);
        var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var candidate = SettingCandidate.create(work, origin.getEpisode(), null, origin,
                org.monitoring.catchholebackend.domain.character.type.SettingEntityType.CHARACTER, "한서린", "한서린", null,
                org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED, "age", "90",
                org.monitoring.catchholebackend.domain.character.type.SettingValueType.NUMBER, j.objectNode().put("value", 90), j.arrayNode(), java.math.BigDecimal.ONE, null);
        em.persist(candidate); em.flush();
        if (path.equals("promotion")) {
            candidate.startComparison(); candidate.recordComparisonContext(0, "name-only");
            candidate.completeComparison(CharacterFactOperation.ADD, CharacterFactType.AGE, "age", "90", j.objectNode().put("value", 90),
                    j.arrayNode(), CharacterFactTemporalScope.PRESENT, "일반 후보", j.objectNode(), java.time.LocalDateTime.now(), "age", j.arrayNode());
            candidate.confirm();
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
            if (path.equals("confirm")) characterCandidates.confirmSettingCandidate(work.getMember().getId(), work.getId(), candidate.getId(), null);
            else characterPromotion.promote(candidate, org.monitoring.catchholebackend.domain.character.type.CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
        }).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class)
                .satisfies(error -> assertThat(((org.monitoring.catchholebackend.global.exception.AppException) error).getResultCode())
                        .isEqualTo(org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED));
        assertThat(sql.queryForObject("select count(*) from characters where work_id=?", Integer.class, work.getId())).isEqualTo(2);
        assertThat(sql.queryForObject("select count(*) from character_facts f join characters c on c.id=f.character_id where c.work_id=?", Integer.class, work.getId())).isEqualTo(2);
    }

    private IdentityFixture sameNameBookFixture(boolean proof) throws Exception {
        return sameNameBookFixture(proof, java.util.List.of(), "WORK_START", null);
    }

    private IdentityFixture sameNameBookFixture(boolean proof, java.util.List<WorkCharacter> actual, String kind, Integer number) throws Exception {
        ensureAgeSchema();
        int firstAge = actual.isEmpty() ? 27 : 28;
        int secondAge = actual.isEmpty() ? 34 : 35;
        String firstProfile = "[기록관 프로필]\n이름: 한서린\n나이: " + firstAge + "\n";
        String secondProfile = "[선원 프로필]\n이름: 한서린\n나이: " + secondAge + "\n";
        String distinct = "기록관 한서린과 선원 한서린은 서로 다른 인물이다.";
        String content = "📚 인물 설정\r\n" + firstProfile + secondProfile + distinct;
        uploadReference(content, kind, number);
        JsonNode claim = claimBook();
        AnalysisJob origin = jobs.findById(UUID.fromString(claim.path("analysisJobId").asText())).orElseThrow();
        var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        java.util.List<SettingCandidate> discoveries = new java.util.ArrayList<>();
        java.util.List<SettingCandidate> children = new java.util.ArrayList<>();
        java.util.List<String> profiles = java.util.List.of(firstProfile, secondProfile);
        for (int i = 0; i < 2; i++) {
            UUID actualId = actual.isEmpty() ? null : actual.get(i).getId();
            var discovery = SettingCandidate.createCharacterDiscovery(work, null, null, origin, "한서린", "한서린", actualId,
                    actual.isEmpty() ? org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED
                            : org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.MATCHED,
                    j.arrayNode().add(identitySpan(content, profiles.get(i))), java.math.BigDecimal.ONE, j.objectNode());
            em.persist(discovery); em.flush();
            if (actual.isEmpty()) org.springframework.test.util.ReflectionTestUtils.setField(discovery, "provisionalSubjectKey", "provisional-character:" + discovery.getId());
            int age = i == 0 ? firstAge : secondAge;
            var child = SettingCandidate.create(work, null, null, origin,
                    org.monitoring.catchholebackend.domain.character.type.SettingEntityType.CHARACTER, "한서린", "한서린", actualId,
                    actual.isEmpty() ? org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED
                            : org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.MATCHED, "age", Integer.toString(age),
                    org.monitoring.catchholebackend.domain.character.type.SettingValueType.NUMBER, j.objectNode().put("value", age),
                    j.arrayNode().add(identitySpan(content, "나이: " + age)), java.math.BigDecimal.ONE, j.objectNode());
            org.springframework.test.util.ReflectionTestUtils.setField(child, "provisionalSubjectKey", discovery.getProvisionalSubjectKey());
            em.persist(child); discoveries.add(discovery); children.add(child);
        }
        if (proof) {
            var common = j.objectNode().put("version", 1).put("producer", "ORDERED_CHARACTER_SUBJECT_RESOLVER")
                    .put("identityDecision", "DISTINCT_IDENTITIES").put("groupId", "G1").put("normalizedName", "한서린")
                    .put("sourceAnalysisJobId", origin.getId().toString()).put("settingBookId", origin.getSourceSettingBook().getId().toString())
                    .put("revisionId", origin.getSourceSettingBookRevision().getId().toString()).put("contentHash", origin.getSourceContentHash())
                    .put("referenceTimeKind", kind).put("referenceEpisodeNo", number);
            var memberNodes = common.putArray("members");
            for (int i = 0; i < 2; i++) memberNodes.add(j.objectNode().put("targetRef", identityTarget(discoveries.get(i)))
                    .set("profileEvidence", identitySpan(content, profiles.get(i))));
            common.putArray("distinctEvidenceSpans").add(identitySpan(content, distinct));
            for (int i = 0; i < 2; i++) {
                var memberProof = common.deepCopy().put("parentTargetRef", identityTarget(discoveries.get(i)));
                ((com.fasterxml.jackson.databind.node.ObjectNode) discoveries.get(i).getRawAiResultJson()).set("setting_book_identity_proof", memberProof.deepCopy());
                ((com.fasterxml.jackson.databind.node.ObjectNode) children.get(i).getRawAiResultJson()).set("setting_book_identity_proof", memberProof.deepCopy());
            }
        }
        em.flush(); characterStates.prepareProvisionalCandidates(origin);
        for (var child : children) {
            var target = child.getMatchedCharacterId() == null ? null : em.find(WorkCharacter.class, child.getMatchedCharacterId());
            var batch = target == null ? CharacterFactComparisonBatch.createProvisional(work, null, origin, child.getProvisionalSubjectKey(), CharacterFactType.AGE, 1)
                    : CharacterFactComparisonBatch.create(work, null, origin, target, CharacterFactType.AGE, 1, target.getSnapshotVersion());
            var context = j.objectNode(); var slots = context.putArray("slots");
            if (target != null) snapshotAccessor.read(target).values().forEach(entry -> slots.add(j.objectNode().put("factType", entry.slot().factType().name())
                    .put("factKey", entry.slot().factKey()).put("factValue", entry.factValue()).set("valueJson", entry.valueJson())));
            batch.recordAnalysisContext(context); em.persist(batch);
            child.startComparison(batch, "C1"); child.recordComparisonContext(target == null ? 0 : target.getSnapshotVersion(), origin.getInputStateHash());
            child.completeComparison(target == null ? CharacterFactOperation.ADD : CharacterFactOperation.UPDATE, CharacterFactType.AGE, "age", child.getAttributeValue(), child.getValueJson(),
                    j.arrayNode(), CharacterFactTemporalScope.PRESENT, "각 프로필의 나이 근거", j.objectNode(), java.time.LocalDateTime.now(), "age", j.arrayNode());
            batch.complete("b".repeat(64), j.objectNode());
            characterStates.recordDecision(origin, child, new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry(
                    new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot(CharacterFactType.AGE, "age"),
                    child.getAttributeValue(), child.getValueJson(), true), java.util.List.of(), java.util.List.of());
        }
        em.flush();
        return new IdentityFixture(origin, UUID.fromString(claim.path("leaseToken").asText()), discoveries, children, content);
    }

    @Test void bookAutomaticHoldsInvalidDiscoveryAndItsChildrenButAppliesIndependentProfile() throws Exception {
        var fixture = bookEvidenceCharacters();
        setEvidence(fixture.discoveries().getFirst(), "하린은 21살이다. unresolved_references: []", null, null);
        finishIdentityFixture(fixture);
        assertThat(fixture.discoveries().getFirst().isPendingReview()).isTrue();
        assertThat(fixture.discoveries().getFirst().getAutomaticReviewHoldReason().name()).isEqualTo("REVIEW_REQUIRED");
        assertThat(fixture.children().getFirst().isPendingReview()).isTrue();
        assertThat(fixture.children().getFirst().getAutomaticReviewHoldReason().name()).isEqualTo("SUBJECT_CONFIRMATION_REQUIRED");
        assertThat(sql.queryForList("select name from characters where work_id=?", String.class, work.getId())).containsExactly("도윤");
        assertThat(fixture.children().getLast().getReviewStatus().name()).isEqualTo("CONFIRMED");
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"noncontiguous", "partialOffset", "wrongSlice", "empty", "overflow", "exclude"})
    void bookAutomaticKeepsInvalidFactEvidenceAndDependentMergeInReview(String invalid) throws Exception {
        var fixture = bookEvidenceCharacters();
        var first = fixture.children().getFirst();
        switch (invalid) {
            case "noncontiguous", "exclude" -> setEvidence(first, "하린은 21살이다. unresolved_references: []", null, null);
            case "partialOffset" -> setEvidence(first, "하린은 21살이다.", 3, null);
            case "wrongSlice" -> setEvidence(first, "하린은 21살이다.", 0, 11);
            case "empty" -> org.springframework.test.util.ReflectionTestUtils.setField(first, "evidenceSpans", json.createArrayNode());
            case "overflow" -> org.springframework.test.util.ReflectionTestUtils.setField(first, "evidenceSpans", json.createArrayNode().add(json.createObjectNode()
                    .put("quote", "하린은 21살이다.").put("startOffset", 4294967297L).put("endOffset", 4294967308L)));
        }
        if (invalid.equals("exclude")) org.springframework.test.util.ReflectionTestUtils.setField(first, "suggestedOperation", CharacterFactOperation.EXCLUDE);
        var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var dependent = SettingCandidate.create(work, null, null, fixture.origin(), org.monitoring.catchholebackend.domain.character.type.SettingEntityType.CHARACTER,
                "하린", "하린", null, org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED,
                "age", "22", org.monitoring.catchholebackend.domain.character.type.SettingValueType.NUMBER, j.objectNode().put("value", 22),
                first.getEvidenceSpans().deepCopy(), java.math.BigDecimal.ONE, null);
        org.springframework.test.util.ReflectionTestUtils.setField(dependent, "provisionalSubjectKey", first.getProvisionalSubjectKey()); em.persist(dependent); em.flush();
        org.springframework.test.util.ReflectionTestUtils.setField(dependent, "evidenceSpans", j.arrayNode().add(identitySpan(fixture.content(), "하린은 21살이다.")));
        dependent.prepareProvisionalComparison(); dependent.startComparison(first.getCharacterComparisonBatch(), "C2");
        dependent.recordComparisonContext(0, fixture.origin().getInputStateHash());
        dependent.completeComparison(CharacterFactOperation.MERGE, CharacterFactType.AGE, "age", "22", j.objectNode().put("value", 22), j.arrayNode(),
                CharacterFactTemporalScope.PRESENT, "강제 선행 후보 의존", j.objectNode(), java.time.LocalDateTime.now(), "age", j.arrayNode().add(first.getId().toString()));
        characterStates.recordDecision(fixture.origin(), dependent, new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry(
                new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot(CharacterFactType.AGE, "age"), "22", j.objectNode().put("value", 22), true),
                java.util.List.of(), java.util.List.of(first.getId()));
        finishIdentityFixture(fixture);
        assertThat(first.isPendingReview()).isTrue(); assertThat(first.getAutomaticReviewHoldReason().name()).isEqualTo("REVIEW_REQUIRED");
        assertThat(dependent.isPendingReview()).isTrue(); assertThat(dependent.getAutomaticReviewHoldReason().name()).isEqualTo("DEPENDENCY_CONFIRMATION_REQUIRED");
        assertThat(sql.queryForObject("select current_age from characters where work_id=? and name='하린'", Integer.class, work.getId())).isNull();
        assertThat(sql.queryForObject("select current_age from characters where work_id=? and name='도윤'", Integer.class, work.getId())).isEqualTo(34);
        assertThat(sql.queryForObject("select count(*) from character_facts where extracted_by_job_id=?", Integer.class, fixture.origin().getId())).isEqualTo(1);
    }

    @Test void bookAutomaticAcceptsExactQuotesWithoutOffsetsAndPreservesProvidedUnicodeSlices() throws Exception {
        var fixture = bookEvidenceCharacters();
        for (var row : java.util.List.of(fixture.discoveries().getFirst(), fixture.children().getFirst()))
            setEvidence(row, row.getEvidenceSpans().get(0).path("quote").asText(), null, null);
        finishIdentityFixture(fixture);
        assertThat(sql.queryForObject("select count(*) from characters where work_id=?", Integer.class, work.getId())).isEqualTo(2);
        assertThat(sql.queryForObject("select count(*) from character_facts where extracted_by_job_id=?", Integer.class, fixture.origin().getId())).isEqualTo(2);
        assertThat(fixture.children()).allSatisfy(row -> assertThat(row.getReviewStatus().name()).isEqualTo("CONFIRMED"));
    }

    @Test void bookAutomaticKeepsInvalidWorldAndSharedDecisionInReviewButAppliesIndependentProperty() throws Exception {
        String text = "📚\r\n엘프의 서식지는 북부다.\r\n엘프의 수명은 700년이다.";
        var actual = WorldSetting.create(work, WorldSettingCategory.RACE, "엘프", Map.of("분류", "장수종족")); em.persist(actual); em.flush();
        var book = uploadReference(text, "WORK_START", null);
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var bad = comparableWorld(origin, null, actual, "북부"); attachAutomaticWorld(bad, actual, WorldSettingSuggestedOperation.ADD);
        var dependent = comparableWorld(origin, null, actual, "서식지", "북부");
        org.springframework.test.util.ReflectionTestUtils.setField(dependent, "comparisonDecision", bad.getComparisonDecision());
        org.springframework.test.util.ReflectionTestUtils.setField(dependent, "comparisonBatch", bad.getComparisonDecision().getComparisonBatch());
        em.persist(WorldSettingComparisonDecisionSource.create(bad.getComparisonDecision().getComparisonBatch(), bad.getComparisonDecision(), dependent, "C2", 1));
        org.springframework.test.util.ReflectionTestUtils.setField(bad, "evidenceSpans", json.createArrayNode().add(json.createObjectNode()
                .put("quote", "엘프의 서식지는 북부다. unresolved_references: []").putNull("startOffset").putNull("endOffset")));
        org.springframework.test.util.ReflectionTestUtils.setField(dependent, "evidenceSpans", json.createArrayNode().add(identitySpan(text, "엘프의 서식지는 북부다.")));
        var valid = comparableWorld(origin, null, actual, "수명", "700년"); attachAutomaticWorld(valid, actual, WorldSettingSuggestedOperation.ADD);
        org.springframework.test.util.ReflectionTestUtils.setField(valid, "evidenceSpans", json.createArrayNode().add(json.createObjectNode()
                .put("quote", "엘프의 수명은 700년이다.").putNull("startOffset").putNull("endOffset")));
        completeFixtureOrigin(origin); worldCandidates.applyAutomatically(origin); em.flush();
        assertThat(actual.hasProperty("서식지")).isFalse(); assertThat(actual.getPropertyValue("분류")).isEqualTo("장수종족");
        assertThat(actual.getPropertyValue("수명")).isEqualTo("700년");
        assertThat(bad.isPendingReview()).isTrue(); assertThat(bad.getAutomaticReviewHoldReason().name()).isEqualTo("REVIEW_REQUIRED");
        assertThat(dependent.isPendingReview()).isTrue(); assertThat(valid.getReviewStatus().name()).isEqualTo("CONFIRMED");
    }

    @Test void bookAutomaticDoesNotDismissWorldExcludeWithCorruptedSourceQuote() throws Exception {
        var actual = WorldSetting.create(work, WorldSettingCategory.RACE, "엘프", "서식지", "북부"); em.persist(actual);
        var book = uploadReference("엘프의 서식지는 북부다.", "WORK_START", null);
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var row = comparableWorld(origin, null, actual, "북부"); attachAutomaticWorld(row, actual, WorldSettingSuggestedOperation.EXCLUDE);
        org.springframework.test.util.ReflectionTestUtils.setField(row, "evidenceSpans", json.createArrayNode().add(json.createObjectNode()
                .put("quote", "엘프의 서식지는 북부다. unresolved_references: []").putNull("startOffset").putNull("endOffset")));
        completeFixtureOrigin(origin); worldCandidates.applyAutomatically(origin); em.flush();
        assertThat(actual.getPropertyValue("서식지")).isEqualTo("북부"); assertThat(row.isPendingReview()).isTrue();
        assertThat(row.getAutomaticReviewHoldReason().name()).isEqualTo("REVIEW_REQUIRED");
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({
            "missing,ADD", "missing,EXCLUDE", "null,ADD", "null,EXCLUDE", "unknown,ADD", "unknown,EXCLUDE",
            "unresolved,ADD", "unresolved,EXCLUDE", "mixed,ADD", "mixed,EXCLUDE", "missingChild,ADD", "missingChild,EXCLUDE",
            "emptySources,ADD", "emptySources,EXCLUDE", "malformedSources,ADD", "malformedSources,EXCLUDE"})
    @org.junit.jupiter.api.DisplayName("설정집 미확정·누락 주장 표지는 자동 현재 반영과 제외를 막고 명확한 형제만 반영한다")
    void unassertedBookWorldClaimsStayInReviewAndDoNotDismiss(String marker, WorldSettingSuggestedOperation operation) throws Exception {
        String text = "엘프의 서식지는 북부다.\n엘프의 수명은 700년이다.";
        var actual = WorldSetting.create(work, WorldSettingCategory.RACE, "엘프", operation == WorldSettingSuggestedOperation.EXCLUDE
                ? Map.of("서식지", "북부") : Map.of("분류", "장수종족")); em.persist(actual); em.flush();
        var book = uploadReference(text, "WORK_START", null);
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var held = comparableWorld(origin, null, actual, "북부"); attachAutomaticWorld(held, actual, operation);
        var raw = json.createObjectNode().put("epistemic_status", "ASSERTED");
        switch (marker) {
            case "missing" -> raw.remove("epistemic_status");
            case "null" -> raw.putNull("epistemic_status");
            case "unknown" -> raw.put("epistemic_status", "FUTURE_STATUS");
            case "unresolved" -> raw.put("epistemic_status", "UNRESOLVED_CLAIM");
            case "mixed" -> raw.putArray("sourceCandidates").add(json.createObjectNode().put("epistemic_status", "ASSERTED"))
                    .add(json.createObjectNode().put("epistemic_status", "UNRESOLVED_CLAIM"));
            case "missingChild" -> raw.putArray("sourceCandidates").add(json.createObjectNode().put("epistemic_status", "ASSERTED"))
                    .add(json.createObjectNode().put("quote", "검증되지 않은 자식"));
            case "emptySources" -> raw.putArray("sourceCandidates");
            case "malformedSources" -> raw.put("sourceCandidates", "ASSERTED");
        }
        org.springframework.test.util.ReflectionTestUtils.setField(held, "rawExtractionJson", raw);
        var valid = comparableWorld(origin, null, actual, "수명", "700년"); attachAutomaticWorld(valid, actual, WorldSettingSuggestedOperation.ADD);
        org.springframework.test.util.ReflectionTestUtils.setField(valid, "rawExtractionJson", json.createObjectNode().put("epistemic_status", "ASSERTED"));
        completeFixtureOrigin(origin); worldCandidates.applyAutomatically(origin); em.flush();
        assertThat(held.isPendingReview()).isTrue(); assertThat(held.getAutomaticReviewHoldReason().name()).isEqualTo("REVIEW_REQUIRED");
        assertThat(actual.getPropertyValue("서식지")).isEqualTo(operation == WorldSettingSuggestedOperation.EXCLUDE ? "북부" : null);
        assertThat(valid.getReviewStatus().name()).isEqualTo("CONFIRMED"); assertThat(actual.getPropertyValue("수명")).isEqualTo("700년");
    }

    @Test @org.junit.jupiter.api.DisplayName("세계관 내부 응답은 저장된 주장 원본 트리를 재시작 후 singleton·주체·batch에 그대로 전달한다")
    void worldWorkerHttpResponsesPreserveRawAssertionTreeAfterReload() throws Exception {
        var actual = WorldSetting.create(work, WorldSettingCategory.RACE, "엘프", "서식지", "북부"); em.persist(actual); em.flush();
        uploadReference("엘프의 서식지는 남부다.", "WORK_START", null); JsonNode claim = claimBook();
        UUID jobId = UUID.fromString(claim.path("analysisJobId").asText()); UUID lease = UUID.fromString(claim.path("leaseToken").asText());
        var origin = jobs.findById(jobId).orElseThrow(); origin.updateCheckpointStage(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED);
        Map<String,Object> raw = Map.of("epistemic_status", "UNRESOLVED_CLAIM", "sourceCandidates", java.util.List.of(
                Map.of("epistemic_status", "ASSERTED", "evidence", "첫 근거"), Map.of("epistemic_status", "UNRESOLVED_CLAIM", "evidence", "소문")));
        var published = worldWorker.publishWorldSettingCandidates(jobId, lease, new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest(java.util.List.of(
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.Candidate(WorldSettingCategory.RACE, "엘프", null, "서식지", "남부",
                        java.util.List.of(new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.EvidenceSpan("엘프의 서식지는 남부다.", 0, 13)),
                        java.math.BigDecimal.ONE, raw, CharacterFactTemporalScope.PRESENT, null))));
        UUID candidateId = published.getFirst().candidateId(); em.flush(); em.clear();
        JsonNode pending = response(mvc.perform(get("/api/internal/v1/analysis-jobs/{id}/world-setting-subject-resolutions/pending", jobId)
                .header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(pending.path("candidates").get(0).path("rawExtractionJson")).isEqualTo(json.valueToTree(raw));
        var singleton = new org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingWorkerMapper().toResponse(worldCandidateRepository.findById(candidateId).orElseThrow());
        assertThat(actualMvcJson(singleton).path("rawExtractionJson")).isEqualTo(json.valueToTree(raw));
        worldWorker.resolveWorldSettingSubjects(jobId, lease, new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest(java.util.List.of(
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput(candidateId, java.util.List.of(actual.getId())))));
        JsonNode batch = response(mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/world-setting-comparison-batches/claim-next", jobId)
                .header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(batch.path("candidates").get(0).path("rawExtractionJson")).isEqualTo(json.valueToTree(raw));
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"UPDATE", "EXCLUDE"})
    @org.junit.jupiter.api.DisplayName("Worker의 강제 미확정 세계관 결정도 후속 누적 현재값을 오염시키거나 근거를 제외하지 않는다")
    void forcedUnresolvedWorldDecisionCannotPolluteProjectedCurrent(String operation) throws Exception {
        var previous = episodeOrderedOrigin(1); var seed = comparableWorld(previous, previous.getEpisode(), null, "북부");
        completeFixtureOrigin(previous); confirmWorld(seed); var actual = seed.getTargetWorldSetting();
        uploadReference("엘프의 서식지는 남부라는 소문이 있다.", "EPISODE", 3); JsonNode claim = claimBook();
        UUID jobId = UUID.fromString(claim.path("analysisJobId").asText()); UUID lease = UUID.fromString(claim.path("leaseToken").asText());
        var origin = jobs.findById(jobId).orElseThrow(); origin.updateCheckpointStage(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED);
        var published = worldWorker.publishWorldSettingCandidates(jobId, lease, new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest(java.util.List.of(
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.Candidate(WorldSettingCategory.RACE, "엘프", null, "서식지", "남부",
                        java.util.List.of(new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.EvidenceSpan("엘프의 서식지는 남부라는 소문이 있다.", null, null)),
                        java.math.BigDecimal.ONE, Map.of("epistemic_status", "UNRESOLVED_CLAIM"), CharacterFactTemporalScope.PRESENT, null))));
        worldWorker.resolveWorldSettingSubjects(jobId, lease, new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest(java.util.List.of(
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput(published.getFirst().candidateId(), java.util.List.of(actual.getId())))));
        var batch = worldWorker.claimNextWorldSettingComparisonBatch(jobId, lease).orElseThrow();
        var context = worldWorker.getWorldSettingComparisonBatchContext(jobId, batch.comparisonBatchId(), lease,
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchContextRequest(batch.resolvedTargetWorldSettingIds(), batch.resolvedProvisionalSubjectKeys()));
        Map<String,Object> decision = new java.util.LinkedHashMap<>(); decision.put("decisionRef", "D1"); decision.put("sourceCandidateRefs", java.util.List.of("C1"));
        decision.put("canonicalSubjectName", "엘프"); decision.put("targetWorldSettingId", actual.getId()); decision.put("matchedPropertyName", "서식지");
        decision.put("consolidationStatus", "SINGLE"); decision.put("suggestedOperation", operation); decision.put("proposedSettingName", "서식지");
        decision.put("proposedValue", "남부"); decision.put("comparisonReason", "미확정 주장을 강제한 결정");
        mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/world-setting-comparison-batches/{batch}/complete", jobId, batch.comparisonBatchId())
                .header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("contextToken", context.contextToken(), "contextVersions", java.util.List.of(Map.of("worldSettingId", actual.getId(), "version", actual.getVersion())), "decisions", java.util.List.of(decision)))))
                .andExpect(status().isOk());
        JsonNode projected = states.getProjectedState(origin);
        assertThat(projected.path("worldSettings").path("world:"+actual.getId()).path("propertiesJson").path("서식지").asText()).isEqualTo("북부");
        assertThat(projected.path("references")).anySatisfy(ref -> assertThat(ref.path("sourceCandidateIds")).contains(json.valueToTree(published.getFirst().candidateId().toString())));
        assertThat(worldCandidateRepository.findById(published.getFirst().candidateId()).orElseThrow().getAutomaticReviewHoldReason().name()).isEqualTo("REVIEW_REQUIRED");
        assertThat(actual.getPropertyValue("서식지")).isEqualTo("북부");
    }

    private JsonNode actualMvcJson(Object value) throws Exception {
        var converter = mvcAdapter.getMessageConverters().stream().filter(c -> c.canWrite(value.getClass(), MediaType.APPLICATION_JSON)).findFirst().orElseThrow();
        var output = new org.springframework.mock.http.MockHttpOutputMessage();
        @SuppressWarnings("unchecked") var writer = (org.springframework.http.converter.HttpMessageConverter<Object>)converter;
        writer.write(value, MediaType.APPLICATION_JSON, output); return json.readTree(output.getBodyAsString(StandardCharsets.UTF_8));
    }

    private void setEvidence(SettingCandidate candidate, String quote, Integer start, Integer end) {
        org.springframework.test.util.ReflectionTestUtils.setField(candidate, "evidenceSpans", json.createArrayNode().add(json.createObjectNode()
                .put("quote", quote).put("startOffset", start).put("endOffset", end)));
    }

    private IdentityFixture bookEvidenceCharacters() throws Exception {
        ensureAgeSchema(); String text = "📚\r\n하린은 21살이다.\r\n도윤은 34살이다.";
        var uploaded = uploadReference(text, "WORK_START", null); var claim = claimBook();
        var origin = jobs.findById(UUID.fromString(claim.path("analysisJobId").asText())).orElseThrow();
        var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var discoveries = new java.util.ArrayList<SettingCandidate>(); var children = new java.util.ArrayList<SettingCandidate>();
        var names = java.util.List.of("하린", "도윤"); var ages = java.util.List.of(21, 34);
        for (int i = 0; i < 2; i++) {
            var discovery = SettingCandidate.createCharacterDiscovery(work, null, null, origin, names.get(i), names.get(i), null,
                    org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED, j.arrayNode().add(identitySpan(text, names.get(i))), java.math.BigDecimal.ONE, null);
            em.persist(discovery); em.flush();
            org.springframework.test.util.ReflectionTestUtils.setField(discovery, "provisionalSubjectKey", "provisional-character:"+discovery.getId());
            var child = SettingCandidate.create(work, null, null, origin, org.monitoring.catchholebackend.domain.character.type.SettingEntityType.CHARACTER,
                    names.get(i), names.get(i), null, org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED,
                    "age", ages.get(i).toString(), org.monitoring.catchholebackend.domain.character.type.SettingValueType.NUMBER, j.objectNode().put("value", ages.get(i)),
                    j.arrayNode().add(identitySpan(text, names.get(i)+"은 "+ages.get(i)+"살이다.")), java.math.BigDecimal.ONE, null);
            org.springframework.test.util.ReflectionTestUtils.setField(child, "provisionalSubjectKey", discovery.getProvisionalSubjectKey()); em.persist(child);
            discoveries.add(discovery); children.add(child);
        }
        em.flush(); characterStates.prepareProvisionalCandidates(origin);
        for (var child : children) {
            var batch = CharacterFactComparisonBatch.createProvisional(work, null, origin, child.getProvisionalSubjectKey(), CharacterFactType.AGE, 1);
            batch.recordAnalysisContext(j.objectNode().set("slots", j.arrayNode())); em.persist(batch);
            child.startComparison(batch, "C1"); child.recordComparisonContext(0, origin.getInputStateHash());
            child.completeComparison(CharacterFactOperation.ADD, CharacterFactType.AGE, "age", child.getAttributeValue(), child.getValueJson(), j.arrayNode(),
                    CharacterFactTemporalScope.PRESENT, "원문 나이 근거", j.objectNode(), java.time.LocalDateTime.now(), "age", j.arrayNode());
            batch.complete("e".repeat(64), j.objectNode());
            characterStates.recordDecision(origin, child, new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry(
                    new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot(CharacterFactType.AGE, "age"), child.getAttributeValue(), child.getValueJson(), true), java.util.List.of(), java.util.List.of());
        }
        return new IdentityFixture(origin, UUID.fromString(claim.path("leaseToken").asText()), discoveries, children, text);
    }

    private String identityTarget(SettingCandidate candidate) {
        return candidate.getProvisionalSubjectKey() != null ? candidate.getProvisionalSubjectKey() : "character:" + candidate.getMatchedCharacterId();
    }

    private com.fasterxml.jackson.databind.node.ObjectNode identitySpan(String text, String quote) {
        int start = text.codePointCount(0, text.indexOf(quote));
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("quote", quote)
                .put("startOffset", start).put("endOffset", start + quote.codePointCount(0, quote.length()));
    }

    private void finishIdentityFixture(IdentityFixture fixture) {
        fixture.origin().updateCheckpointStage(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED);
        worker.completeAnalysisJob(fixture.origin().getId(), fixture.lease(),
                new org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest(null, null, null));
        em.flush();
    }

    private record IdentityFixture(AnalysisJob origin, UUID lease, java.util.List<SettingCandidate> discoveries,
            java.util.List<SettingCandidate> children, String content) { }

    void seedCompletedBookStateAndVerifyEpisodeInput() throws Exception {
        var schemaJson = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        if (sql.queryForObject("select count(*) from character_setting_schemas where schema_key='stats.mental'",Integer.class)==0) em.persist(org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema.create(work,"stats.mental",null,"정신",
                org.monitoring.catchholebackend.domain.character.type.CharacterFactType.STAT,org.monitoring.catchholebackend.domain.character.type.SettingValueType.NUMBER,
                org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics.BASE_VALUE,org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy.REPLACE,
                schemaJson.arrayNode(),org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource.SYSTEM_SEED,true));
        JsonNode book = upload("하린의 정신력은 42다. 엘프의 서식지는 북부다.");
        var payload = worker.claimAnalysisJob(new org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest(
                "test-only", "검증", java.util.Set.of(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION),
                java.util.Set.of(org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode.ORDERED_PROVISIONAL), false,
                java.util.Set.of(org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.SETTING_BOOK), true, null, true)).orElseThrow();
        var job = jobs.findById(payload.analysisJobId()).orElseThrow();
        var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var discovery = org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.createCharacterDiscovery(work,null,null,job,
                "하린","하린",null,org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED,
                j.arrayNode().add(j.objectNode().put("quote","하린").put("startOffset",0).put("endOffset",2)),java.math.BigDecimal.ONE,null);
        em.persist(discovery);em.flush();
        org.springframework.test.util.ReflectionTestUtils.setField(discovery,"provisionalSubjectKey",org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper.provisionalRef(discovery.getId()));
        var stat = org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.create(work,null,null,job,
                org.monitoring.catchholebackend.domain.character.type.SettingEntityType.CHARACTER,"하린","하린",null,
                org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED,"stats.mental","42",
                org.monitoring.catchholebackend.domain.character.type.SettingValueType.NUMBER,j.objectNode().put("value",42),
                j.arrayNode().add(j.objectNode().put("quote","하린의 정신력은 42다.").put("startOffset",0).put("endOffset",13)),java.math.BigDecimal.ONE,null);
        org.springframework.test.util.ReflectionTestUtils.setField(stat,"provisionalSubjectKey",discovery.getProvisionalSubjectKey());em.persist(stat);em.flush();
        characterStates.prepareProvisionalCandidates(job);
        var target = characterStates.getTarget(job,null,discovery.getProvisionalSubjectKey());
        var batch = org.monitoring.catchholebackend.domain.character.entity.CharacterFactComparisonBatch.createProvisional(work,null,job,
                discovery.getProvisionalSubjectKey(),org.monitoring.catchholebackend.domain.character.type.CharacterFactType.STAT,1);
        var ctx = j.objectNode();ctx.putArray("slots");batch.recordAnalysisContext(ctx);em.persist(batch);
        stat.startComparison(batch,"C1");stat.recordComparisonContext(0,job.getInputStateHash());
        stat.completeComparison(org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation.ADD,
                org.monitoring.catchholebackend.domain.character.type.CharacterFactType.STAT,"stats.mental","42",j.objectNode().put("value",42),
                j.arrayNode(),org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope.PRESENT,"원문 근거",j.objectNode(),
                java.time.LocalDateTime.now(),"stats.mental",j.arrayNode());batch.complete("b".repeat(64),j.objectNode());
        characterStates.recordDecision(job,stat,new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry(
                new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot(org.monitoring.catchholebackend.domain.character.type.CharacterFactType.STAT,"stats.mental"),
                "42",j.objectNode().put("value",42),true),java.util.List.of(),java.util.List.of());
        job.updateCheckpointStage(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED);
        var worlds = worldWorker.publishWorldSettingCandidates(job.getId(),payload.leaseToken(),new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest(java.util.List.of(
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.Candidate(
                        org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"엘프",null,"서식지","북부",
                        java.util.List.of(new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.EvidenceSpan("엘프의 서식지는 북부다.",14,27)),java.math.BigDecimal.ONE,Map.of("epistemic_status", "ASSERTED"),CharacterFactTemporalScope.PRESENT,null))));
        assertThat(worlds).singleElement().satisfies(c -> assertThat(c.sourceEpisodeId()).isNull());
        var candidate = em.find(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.class,worlds.getFirst().candidateId());
        String worldKey="provisional-world:"+candidate.getId();
        candidate.resolveOrderedSubject(org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType.NEW,worldKey,"엘프",j.arrayNode(),j.arrayNode().add(worldKey));
        var worldBatch=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonBatch.createOrdered(work,null,job,
                org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,null,
                org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType.NEW,worldKey,"엘프",j.arrayNode(),j.arrayNode().add(worldKey),1);
        em.persist(worldBatch);candidate.startComparison(worldBatch,"C1");
        var decision=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecision.create(worldBatch,"D1","엘프",null,null,null,
                org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingConsolidationStatus.SINGLE,
                org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation.ADD,null,null,"서식지",null,"북부","원문 근거",null);
        decision.bindOrderedTarget(worldKey,0L);em.persist(decision);candidate.completeComparison(decision,java.time.LocalDateTime.now());
        em.persist(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecisionSource.create(worldBatch,decision,candidate,"C1",0));worldBatch.complete("c".repeat(64),j.objectNode());
        var worldTarget=j.objectNode();worldTarget.putNull("actualWorldSettingId");worldTarget.put("provisionalSubjectKey",worldKey);worldTarget.put("category","RACE");
        worldTarget.put("subjectName","엘프");worldTarget.put("normalizedSubjectName","엘프");worldTarget.put("version",1);worldTarget.putObject("propertiesJson").put("서식지","북부");worldTarget.putObject("provenanceByPath");
        states.appendValidatedChanges(job,job.getInputStateHash(),java.util.List.of(new org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange("world-decision:"+decision.getId(),
                java.util.List.of("worldSettings",worldKey),worldTarget,false,"ADD",java.util.List.of(candidate.getId()))));
        job.updateCheckpointStage(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED);
        worker.completeAnalysisJob(job.getId(),payload.leaseToken(),new org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest(null,null,null));
        em.flush();
        assertThat(job.getJournalStatus()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus.SEALED);
        JsonNode sealed = job.getStateJournal();
        assertThat(sealed.path("sourceKind").asText()).isEqualTo("SETTING_BOOK");
        assertThat(sealed.path("episodeNo").isNull()).isTrue();
        assertThat(sealed.path("sourceSettingBookId").asText()).isEqualTo(book.path("id").asText());
        assertThat(sealed.path("sourceSettingBookRevisionId").asText()).isEqualTo(book.path("revisionId").asText());
        assertThat(sealed.path("sourceSettingBookRevisionNo").asInt()).isEqualTo(book.path("revisionNo").asInt());
        assertThat(stat.getReviewStatus()).as("stat automatic application hold=%s",stat.getAutomaticReviewHoldReason())
                .isEqualTo(org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CONFIRMED);
        assertThat(sql.queryForObject("select count(*) from characters where work_id=?",Integer.class,work.getId())).isEqualTo(1);
        assertThat(sql.queryForObject("select first_appearance_episode_id from characters where work_id=?",UUID.class,work.getId())).isNull();
        assertThat(sql.queryForObject("select count(*) from world_settings where work_id=?",Integer.class,work.getId())).isEqualTo(1);
        var episode=org.monitoring.catchholebackend.domain.episode.entity.Episode.create(work,null,1,"1화","works/test/1.txt",null,"d".repeat(64),10);em.persist(episode);
        var next=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.create(work,null,episode,org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION);
        next.configureReviewMode(org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC);states.initializeRun(java.util.List.of(next));
        assertThat(states.prepareInput(next)).isTrue();
        JsonNode input=states.getInputState(next);
        assertThat(input.path("characters").elements().next().path("slots").elements().next().path("provenance").path("sourceKind").asText()).isEqualTo("SETTING_BOOK");
    }

    @Autowired org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobLeaseService leases;

    @Test void editAndArchiveCancelPendingAndRunningComparisonLeasesButKeepCompletedHistory() throws Exception {
        for (boolean archive : java.util.List.of(false,true)) {
            JsonNode book=upload("하린은 기사다.");
            var origin=jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
            var j=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
            var character=org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.createCharacterDiscovery(work,null,null,origin,
                    "하린","하린",null,org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED,j.arrayNode(),java.math.BigDecimal.ONE,null);
            em.persist(character);
            var world=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(work,null,origin,
                    org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"엘프","서식지","북부",j.arrayNode(),java.math.BigDecimal.ONE,null);
            em.persist(world);em.flush();
            var pendingCharacter=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.createCharacterFactComparison(character);
            var runningCharacter=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.createCharacterFactComparison(character);
            var pendingWorld=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.createWorldSettingComparison(world);
            var runningWorld=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.createWorldSettingComparison(world);
            var completed=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.createWorldSettingComparison(world);
            // Real PostgreSQL intentionally permits one active comparison per candidate. Exercise pending and running independently.
            completed.succeed(null,0,0);em.persist(completed);em.persist(pendingCharacter);em.persist(pendingWorld);em.flush();
            if (archive) mvc.perform(delete("/api/v1/works/{workId}/setting-books/{id}",work.getId(),book.path("id").asText()).header("Authorization",bearer)).andExpect(status().isOk());
            else mvc.perform(patch("/api/v1/works/{workId}/setting-books/{id}",work.getId(),book.path("id").asText()).header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content(editRequest(book.path("id").asText(), "{\"content\":\"하린은 마법사다.\"}"))).andExpect(status().isOk());
            assertThat(pendingCharacter.getStatus()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.CANCELED);
            assertThat(pendingWorld.getStatus()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.CANCELED);
            assertThat(completed.getStatus()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.SUCCEEDED);
            assertThat(pendingCharacter.getJournalStatus()).isNull();
            // Stale comparison claims must be refused independently of the ordered-journal mode.
            assertThat(states.prepareInput(runningCharacter)).isFalse();
            assertThat(states.prepareInput(runningWorld)).isFalse();
        }
        JsonNode book=upload("새 인물 하린.");
        var origin=jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var j=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var c=org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.createCharacterDiscovery(work,null,null,origin,"하린","하린",null,
                org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED,j.arrayNode(),java.math.BigDecimal.ONE,null);em.persist(c);
        var w=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(work,null,origin,
                org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"엘프","서식지","북부",j.arrayNode(),java.math.BigDecimal.ONE,null);em.persist(w);
        var cj=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.createCharacterFactComparison(c);em.persist(cj);
        var wj=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.createWorldSettingComparison(w);em.persist(wj);
        UUID charLease=cj.claim(null,null,java.time.LocalDateTime.now().plusMinutes(5));
        UUID worldLease=wj.claim(null,null,java.time.LocalDateTime.now().plusMinutes(5));em.flush();
        mvc.perform(patch("/api/v1/works/{workId}/setting-books/{id}",work.getId(),book.path("id").asText()).header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content(editRequest(book.path("id").asText(), "{\"content\":\"새 내용\"}"))).andExpect(status().isOk());
        for (var comparison : java.util.List.of(cj,wj)) {
            assertThat(comparison.getStatus()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.CANCELED);
            assertThat(comparison.getLeaseToken()).isNull();
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> leases.getRunningAnalysisJobForUpdate(cj.getId(),charLease)).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> leases.getRunningAnalysisJobForUpdate(wj.getId(),worldLease)).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
    }

    @Test void exhaustedQuotaStillReturnsExistingActiveJobAndDeniesNewUploadBeforeStorage() throws Exception {
        JsonNode book=upload("하린은 기사다.");
        int objectCount=objects.size();
        em.flush();sql.update("update ai_token_accounts set used_tokens=granted_tokens, reserved_tokens=0 where member_id=?",work.getMember().getId());em.clear();
        assertThat(analyze(book.path("id").asText()).path("id").asText()).isEqualTo(book.path("latestAnalysisJob").path("id").asText());
        mvc.perform(multipart("/api/v1/works/{workId}/setting-books",work.getId()).file(new MockMultipartFile("file","다른설정.txt",MediaType.TEXT_PLAIN_VALUE,"다른 내용".getBytes(StandardCharsets.UTF_8)))
                .header("Authorization",bearer)).andExpect(status().isConflict());
        assertThat(objects.size()).isEqualTo(objectCount);
        assertThat(sql.queryForObject("select count(*) from analysis_jobs where work_id=?",Integer.class,work.getId())).isEqualTo(1);
    }

    @Test void sameTextSaveCreatesNothingAndAToBToAStillInvalidatesOriginalRevision() throws Exception {
        JsonNode book=upload("하린은 기사다.");
        String id=book.path("id").asText();
        for (String text : java.util.List.of("하린은 기사다.","하린은 마법사다.","하린은 기사다.")) {
            mvc.perform(patch("/api/v1/works/{workId}/setting-books/{id}",work.getId(),id).header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
                    .content(editRequest(id, json.writeValueAsString(java.util.Map.of("content",text))))).andExpect(status().isOk());
        }
        var last=response(mvc.perform(get("/api/v1/works/{workId}/setting-books/{id}",work.getId(),id).header("Authorization",bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(last.path("revisionNo").asInt()).isEqualTo(3);
        assertThat(last.path("contentHash").asText()).isEqualTo(book.path("contentHash").asText());
        assertThat(last.path("contentChangedSinceAnalysis").asBoolean()).isTrue();
        assertThat(sql.queryForObject("select count(*) from setting_book_revisions where setting_book_id=?",Integer.class,UUID.fromString(id))).isEqualTo(3);
    }

    @Test void mixedLegacyBatchScopesCandidatesCountsAndMutationsByOriginExtractionJob() throws Exception {
        JsonNode book=upload("하린은 기사다.");
        UUID book1Id=UUID.fromString(book.path("latestAnalysisJob").path("id").asText());
        var book1=jobs.findById(book1Id).orElseThrow();
        var episode=org.monitoring.catchholebackend.domain.episode.entity.Episode.create(work,null,1,"1화","source/episode.txt","v1","d".repeat(64),10);em.persist(episode);
        var episodeJob=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.create(work,book1.getBatch(),episode,org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION);em.persist(episodeJob);
        var j=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var epCandidate=org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.createCharacterDiscovery(work,episode,null,episodeJob,"회차인물","회차인물",null,
                org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED,j.arrayNode(),java.math.BigDecimal.ONE,null);em.persist(epCandidate);
        var oldCandidate=org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.createCharacterDiscovery(work,null,null,book1,"구설정인물","구설정인물",null,
                org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED,j.arrayNode(),java.math.BigDecimal.ONE,null);em.persist(oldCandidate);
        var epWorld=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(work,episode,episodeJob,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,
                "회차종족","서식지","남부",j.arrayNode(),java.math.BigDecimal.ONE,null);em.persist(epWorld);
        var oldWorld=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(work,null,book1,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,
                "구설정종족","서식지","북부",j.arrayNode(),java.math.BigDecimal.ONE,null);em.persist(oldWorld);em.flush();
        mvc.perform(patch("/api/v1/works/{workId}/setting-books/{id}",work.getId(),book.path("id").asText()).header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
                .content(editRequest(book.path("id").asText(), "{\"content\":\"하린은 마법사다.\"}"))).andExpect(status().isOk());
        JsonNode analyzed=analyze(book.path("id").asText());
        var book2=jobs.findById(UUID.fromString(analyzed.path("id").asText())).orElseThrow();
        var currentCandidate=org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.createCharacterDiscovery(work,null,null,book2,"신설정인물","신설정인물",null,
                org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED,j.arrayNode(),java.math.BigDecimal.ONE,null);em.persist(currentCandidate);
        var currentWorld=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(work,null,book2,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,
                "신설정종족","서식지","동부",j.arrayNode(),java.math.BigDecimal.ONE,null);em.persist(currentWorld);em.flush();
        for (String route : java.util.List.of("setting-candidates","world-setting-candidates")) {
            JsonNode episodeOnly=response(mvc.perform(get("/api/v1/works/{workId}/"+route,work.getId()).param("batchId",book1.getBatch().getId().toString()).param("size","1").header("Authorization",bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(episodeOnly.path("totalCandidateCount").asInt()).isEqualTo(1);
            assertThat(episodeOnly.path("episodeCount").asInt()).isEqualTo(1);
            assertReviewPage(episodeOnly,route.equals("setting-candidates") ? epCandidate.getId() : epWorld.getId());
            for (var source : java.util.List.of(book1,book2)) {
                JsonNode scoped=response(mvc.perform(get("/api/v1/works/{workId}/"+route,work.getId()).param("batchId",book1.getBatch().getId().toString()).param("sourceAnalysisJobId",source.getId().toString()).param("size","1").header("Authorization",bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
                assertThat(scoped.path("totalCandidateCount").asInt()).isEqualTo(1);
                assertThat(scoped.path("episodeCount").asInt()).isZero();
                UUID expected=route.equals("setting-candidates") ? (source==book1 ? oldCandidate.getId() : currentCandidate.getId()) : (source==book1 ? oldWorld.getId() : currentWorld.getId());
                assertReviewPage(scoped,expected);
            }
            mvc.perform(get("/api/v1/works/{workId}/"+route,work.getId()).param("batchId",book1.getBatch().getId().toString()).param("sourceAnalysisJobId",UUID.randomUUID().toString()).header("Authorization",bearer)).andExpect(status().isNotFound());
        }
        JsonNode summaries=response(mvc.perform(get("/api/v1/works/{workId}/analysis-jobs/batches",work.getId()).header("Authorization",bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        java.util.Set<String> currentOrigins=new java.util.HashSet<>();
        summaries.path("content").get(0).path("jobGroups").forEach(group -> group.path("currentAnalysisJobIds").forEach(id -> currentOrigins.add(id.asText())));
        assertThat(currentOrigins).containsExactlyInAnyOrder(episodeJob.getId().toString(),book2.getId().toString());
        mvc.perform(get("/api/v1/works/{workId}/setting-candidates/{id}",work.getId(),oldCandidate.getId()).param("batchId",book1.getBatch().getId().toString()).header("Authorization",bearer)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/works/{workId}/setting-candidates/{id}/dismiss",work.getId(),oldCandidate.getId()).param("sourceAnalysisJobId",book1Id.toString()).header("Authorization",bearer)).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/works/{workId}/setting-candidates/{id}/dismiss",work.getId(),currentCandidate.getId()).header("Authorization",bearer)).andExpect(status().isNotFound());
        var hidden=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.createWorldSettingComparison(currentWorld);em.persist(hidden);em.flush();
        mvc.perform(get("/api/v1/works/{workId}/world-setting-candidates",work.getId()).param("batchId",book1.getBatch().getId().toString()).param("sourceAnalysisJobId",hidden.getId().toString()).header("Authorization",bearer)).andExpect(status().isNotFound());
    }

    @Test void actualClaimRejectsStaleNonorderedBookComparisonAndEditInvalidatesIncompleteSuccess() throws Exception {
        JsonNode book=upload("하린은 기사다.");
        var origin=jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var j=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var candidate=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(work,null,origin,
                org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"엘프","서식지","북부",j.arrayNode(),java.math.BigDecimal.ONE,null);em.persist(candidate);
        var hidden=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.createWorldSettingComparison(candidate);em.persist(hidden);
        var revision=org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision.create(origin.getSourceSettingBook(),2,
                new org.monitoring.catchholebackend.global.storage.StoredTextObject("works/test/revised.txt","v2","b".repeat(64),20));em.persist(revision);
        origin.getSourceSettingBook().linkCurrentRevision(revision);em.flush();
        mvc.perform(post("/api/internal/v1/analysis-jobs/claim").header("X-Internal-Api-Key","test-internal-api-key").contentType(MediaType.APPLICATION_JSON)
                .content("{\"allowedJobTypes\":[\"WORLD_SETTING_COMPARISON\"],\"supportedSourceKinds\":[\"SETTING_BOOK\"],\"supportsSettingBookReferenceTime\":true}"))
                .andExpect(status().isNoContent());
        assertThat(hidden.getStatus()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.CANCELED);
        assertThat(hidden.getLeaseToken()).isNull();
        JsonNode incomplete=upload("다른 설정.");
        var finished=jobs.findById(UUID.fromString(incomplete.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        finished.markJournalIncomplete();finished.succeed(null,0,0);em.flush();
        mvc.perform(patch("/api/v1/works/{workId}/setting-books/{id}",work.getId(),incomplete.path("id").asText()).header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
                .content(editRequest(incomplete.path("id").asText(), "{\"content\":\"새로 저장한 설정.\"}"))).andExpect(status().isOk());
        assertThat(finished.getStatus()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.SUCCEEDED);
        assertThat(finished.getJournalStatus()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus.INVALIDATED);
    }

    @Test void revisionReaderKeepsAnalyzedTextAfterSaveAndArchiveAndRejectsForgedBook() throws Exception {
        JsonNode book=upload("분석 당시 하린은 기사다.");
        mvc.perform(patch("/api/v1/works/{workId}/setting-books/{id}",work.getId(),book.path("id").asText()).header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
                .content(editRequest(book.path("id").asText(), "{\"content\":\"저장한 하린은 마법사다.\"}"))).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/works/{workId}/setting-books/{id}",work.getId(),book.path("id").asText()).header("Authorization",bearer)).andExpect(status().isOk());
        JsonNode evidence=response(mvc.perform(get("/api/v1/works/{workId}/setting-books/{bookId}/revisions/{revisionId}",work.getId(),book.path("id").asText(),book.path("revisionId").asText())
                .header("Authorization",bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(evidence.path("content").asText()).isEqualTo("분석 당시 하린은 기사다.");
        assertThat(evidence.path("source").path("revisionId").asText()).isEqualTo(book.path("revisionId").asText());
        mvc.perform(get("/api/v1/works/{workId}/setting-books/{bookId}/revisions/{revisionId}",work.getId(),UUID.randomUUID(),book.path("revisionId").asText())
                .header("Authorization",bearer)).andExpect(status().isNotFound());
    }

    @Autowired org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository worldCandidateRepository;
    @Autowired org.monitoring.catchholebackend.domain.character.service.CharacterFactComparisonWorkerService characterWorker;

    @Test
    void exportActualOpenApiAndSerializedSyntheticWorkerConformance() throws Exception {
        String exportDirectory=System.getenv("GH221_EXPORT_DIR");
        java.nio.file.Path dir=exportDirectory == null ? null : java.nio.file.Path.of(exportDirectory);
        if (dir != null) java.nio.file.Files.createDirectories(dir);
        String openApi=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        if (dir != null) java.nio.file.Files.writeString(dir.resolve("backend-openapi.json"),openApi);
        seedCompletedBookStateAndVerifyEpisodeInput();
        UUID knownCharacter=sql.queryForObject("select id from characters where work_id=?",UUID.class,work.getId());
        var knownCharacterEntity = em.find(WorkCharacter.class, knownCharacter);
        knownCharacterEntity.protectManualSlot(CharacterFactType.AGE, "age");
        var knownWorld=em.find(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.class,
                sql.queryForObject("select id from world_settings where work_id=?",UUID.class,work.getId()));
        knownWorld.protectManualProperty(null, "서식지");
        knownWorld.protectManualProperty(null, "과거의 관습");
        JsonNode book=upload("하린의 정신력은 43다. 엘프의 서식지는 남부다.");
        var claim=worker.claimAnalysisJob(new org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest("synthetic-only","검증",
                java.util.Set.of(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION),
                java.util.Set.of(org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode.ORDERED_PROVISIONAL),true,
                java.util.Set.of(org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.SETTING_BOOK), true, null, true)).orElseThrow();
        var job=jobs.findById(claim.analysisJobId()).orElseThrow();
        var j=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var discovery=org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.createCharacterDiscovery(work,null,null,job,"하린","하린",null,
                org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED,j.arrayNode().add(j.objectNode().put("quote","하린").put("startOffset",0).put("endOffset",2)),java.math.BigDecimal.ONE,
                j.objectNode().put("candidate_kind","CHARACTER_DISCOVERY").put("entity_type","CHARACTER").put("entity_name","하린").put("raw_entity_mention","하린"));
        em.persist(discovery);em.flush();
        org.springframework.test.util.ReflectionTestUtils.setField(discovery,"provisionalSubjectKey",org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper.provisionalRef(discovery.getId()));
        var stat=org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.create(work,null,null,job,org.monitoring.catchholebackend.domain.character.type.SettingEntityType.CHARACTER,"하린","하린",knownCharacter,
                org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.MATCHED,"stats.mental","43",org.monitoring.catchholebackend.domain.character.type.SettingValueType.NUMBER,j.objectNode().put("value",43),
                j.arrayNode().add(j.objectNode().put("quote","하린의 정신력은 43다.").put("startOffset",0).put("endOffset",13)),java.math.BigDecimal.ONE,null);
        em.persist(stat);em.flush();
        characterStates.prepareProvisionalCandidates(job);
        job.updateCheckpointStage(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage.CHARACTER_CANDIDATES_SAVED);
        var charBatch=characterWorker.claimNextCharacterFactComparisonBatch(job.getId(),claim.leaseToken()).orElseThrow();
        var charContext=characterWorker.getCharacterFactComparisonBatchContext(job.getId(),charBatch.comparisonBatchId(),claim.leaseToken());
        job.updateCheckpointStage(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED);
        var worldCandidates=worldWorker.publishWorldSettingCandidates(job.getId(),claim.leaseToken(),new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest(java.util.List.of(
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.Candidate(org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"엘프",null,"서식지","남부",
                        java.util.List.of(new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.EvidenceSpan("엘프의 서식지는 남부다.",14,27)),java.math.BigDecimal.ONE,Map.of("epistemic_status", "ASSERTED", "sourceCandidates", java.util.List.of(Map.of("epistemic_status", "ASSERTED", "subject_name", "엘프"))),CharacterFactTemporalScope.PRESENT,null))));
        var pending=worldWorker.getPendingWorldSettingSubjectResolutions(job.getId(),claim.leaseToken());
        worldWorker.resolveWorldSettingSubjects(job.getId(),claim.leaseToken(),new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest(java.util.List.of(
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput(worldCandidates.getFirst().candidateId(),java.util.List.of(knownWorld.getId())))));
        assertThat(sql.queryForObject("select count(*) from world_setting_candidates where analysis_job_id=? and comparison_status='PENDING'",Integer.class,job.getId())).isEqualTo(1);
        assertThat(worldCandidateRepository.findComparisonBatchCandidatesForUpdate(job.getId(),null,
                worldCandidates.getFirst().category(), worldCandidateRepository.findById(worldCandidates.getFirst().candidateId()).orElseThrow().getCanonicalSubjectKey(),
                org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.PENDING_REVIEW,
                org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus.PENDING)).hasSize(1);
        var worldBatch=worldWorker.claimNextWorldSettingComparisonBatch(job.getId(),claim.leaseToken()).orElseThrow();
        var worldContext=worldWorker.getWorldSettingComparisonBatchContext(job.getId(),worldBatch.comparisonBatchId(),claim.leaseToken(),
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchContextRequest(worldBatch.resolvedTargetWorldSettingIds(),worldBatch.resolvedProvisionalSubjectKeys()));
        assertThat(charContext.snapshotEntries()).anySatisfy(entry -> assertThat(entry.provenance()).isNotNull());
        assertThat(claim.knownCharacters()).anySatisfy(character -> assertThat(character.protectedSlots()).isNotEmpty());
        assertThat(charContext.protectedSlots()).contains(new org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterProtectedSlotPayload(CharacterFactType.AGE, "age"));
        assertThat(worldContext.targets()).anySatisfy(target -> assertThat(target.protectedPaths()).hasSize(2));
        assertThat(charContext.snapshotEntries()).anySatisfy(entry -> assertThat(entry.provenance().currentSourceTimes()).isNotEmpty());
        assertThat(charContext.snapshotEntries().stream().filter(entry -> entry.provenance()!=null
                && entry.provenance().sourceKind()==org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.SETTING_BOOK).count()).isGreaterThan(0);
        assertThat(worldContext.targets().stream().flatMap(target -> target.properties().stream()).filter(property -> property.provenance()!=null
                && "SETTING_BOOK".equals(json.valueToTree(property.provenance()).path("sourceKind").asText())).count()).isGreaterThan(0);
        var completion=java.util.Map.of("contextToken",worldContext.contextToken(),"contextVersions",java.util.List.of(java.util.Map.of("worldSettingId",knownWorld.getId(),"version",knownWorld.getVersion())),
                "decisions",java.util.List.of(java.util.Map.ofEntries(java.util.Map.entry("decisionRef","D1"),java.util.Map.entry("sourceCandidateRefs",java.util.List.of("C1")),
                        java.util.Map.entry("canonicalSubjectName","엘프"),java.util.Map.entry("targetWorldSettingId",knownWorld.getId()),java.util.Map.entry("matchedPropertyName","서식지"),java.util.Map.entry("beforeValue","북부"),
                        java.util.Map.entry("consolidationStatus","SINGLE"),java.util.Map.entry("suggestedOperation","UPDATE"),java.util.Map.entry("proposedSettingName","서식지"),
                        java.util.Map.entry("proposedValue","남부"),java.util.Map.entry("comparisonReason","합성 원문 근거"))));
        mvc.perform(post("/api/internal/v1/analysis-jobs/{jobId}/world-setting-comparison-batches/{batchId}/complete",job.getId(),worldBatch.comparisonBatchId())
                .header("X-Internal-Api-Key","test-internal-api-key").header("X-Worker-Lease-Token",claim.leaseToken()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(completion))).andExpect(status().isOk());
        assertThat(worldWorker.claimNextWorldSettingComparisonBatch(job.getId(),claim.leaseToken())).isEmpty();
        var hiddenCandidate=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(work,null,job,
                org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"드워프","서식지","남부",j.arrayNode(),java.math.BigDecimal.ONE,null);em.persist(hiddenCandidate);
        var hidden=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.createWorldSettingComparison(hiddenCandidate);em.persist(hidden);em.flush();
        var hiddenClaim=worker.claimAnalysisJob(new org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest("synthetic-only","검증",
                java.util.Set.of(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.WORLD_SETTING_COMPARISON),java.util.Set.of(org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode.CONFIRMED_ONLY),true,
                java.util.Set.of(org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.SETTING_BOOK), true)).orElseThrow();
        var singleton = org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.create(work,null,null,job,
                org.monitoring.catchholebackend.domain.character.type.SettingEntityType.CHARACTER,"하린","하린",knownCharacter,
                org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.MATCHED,"stats.mental","44",
                org.monitoring.catchholebackend.domain.character.type.SettingValueType.NUMBER,j.objectNode().put("value",44),j.arrayNode(),java.math.BigDecimal.ONE,null);
        em.persist(singleton);
        var singletonJob = org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.createCharacterFactComparison(singleton); em.persist(singletonJob); em.flush();
        var singletonClaim = worker.claimAnalysisJob(new org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest("synthetic-only","검증",
                java.util.Set.of(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.CHARACTER_FACT_COMPARISON), java.util.Set.of(org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode.CONFIRMED_ONLY),true,
                java.util.Set.of(org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.SETTING_BOOK),true)).orElseThrow();
        characterWorker.claimNextCharacterFactComparison(singletonJob.getId(), singletonClaim.leaseToken()).orElseThrow();
        JsonNode singletonContext = response(mvc.perform(post("/api/internal/v1/analysis-jobs/{jobId}/setting-candidates/{id}/character-fact-comparison-context", singletonJob.getId(), singleton.getId())
                .header("X-Internal-Api-Key","test-internal-api-key").header("X-Worker-Lease-Token",singletonClaim.leaseToken()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(singletonContext.path("protectedSlots")).hasSize(1);
        assertThat(singletonContext.path("snapshotEntries")).anySatisfy(entry -> assertThat(entry.path("provenance").path("currentSourceTimes")).isNotEmpty());
        var episodeWork=works.save(org.monitoring.catchholebackend.domain.work.entity.Work.create(work.getMember(),"회차 합성 검증",WorkGenre.FANTASY,null));
        var episode=org.monitoring.catchholebackend.domain.episode.entity.Episode.create(episodeWork,null,1,"1화","synthetic/episode.txt","v1","d".repeat(64),10);em.persist(episode);
        var episodeJob=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.create(episodeWork,null,episode,org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION);em.persist(episodeJob);em.flush();
        var episodeClaim=worker.claimAnalysisJob(new org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest("synthetic-only","검증",
                java.util.Set.of(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION))).orElseThrow();
        Map<String,Object> payload=new java.util.LinkedHashMap<>();
        payload.put("bookClaim",claim);payload.put("episodeClaim",episodeClaim);payload.put("hiddenBookComparisonClaim",hiddenClaim);
        payload.put("worldCandidates",worldCandidates);payload.put("worldSubjectPending",pending);payload.put("worldBatch",worldBatch);payload.put("worldBatchContext",worldContext);
        payload.put("characterBatch",charBatch);payload.put("characterBatchContext",charContext);payload.put("bookSource",org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisSourceResponse.from(job));
        payload.put("characterSingletonContext", json.convertValue(singletonContext, Map.class));
        var converter = mvcAdapter.getMessageConverters().stream().filter(c -> c.canWrite(payload.getClass(),MediaType.APPLICATION_JSON)).findFirst().orElseThrow();
        var output = new org.springframework.mock.http.MockHttpOutputMessage();
        @SuppressWarnings("unchecked") var writer = (org.springframework.http.converter.HttpMessageConverter<Object>)converter;
        writer.write(payload,MediaType.APPLICATION_JSON,output);
        if (dir != null) java.nio.file.Files.writeString(dir.resolve("java-worker-conformance.json"),output.getBodyAsString(StandardCharsets.UTF_8));
        assertThat(new ObjectMapper().readTree(output.getBodyAsString()).path("bookClaim").path("leaseExpiresAt").isTextual()).isTrue();
        assertThat(new ObjectMapper().readTree(output.getBodyAsString()).path("characterSingletonContext").has("contextToken")).isTrue();
    }

    @Test void bookProgressTokenLedgerAndFailedCheckpointResumeKeepChunksAndFreshLease() throws Exception {
        var book=upload("하린은 기사다.");
        JsonNode claim=claimBook();
        UUID jobId=UUID.fromString(claim.path("analysisJobId").asText());
        String firstLease=claim.path("leaseToken").asText();
        mvc.perform(patch("/api/internal/v1/analysis-jobs/{id}/progress",jobId)
                .header("X-Internal-Api-Key","test-internal-api-key").header("X-Worker-Lease-Token",firstLease)
                .contentType(MediaType.APPLICATION_JSON).content("{\"currentStep\":\"청크 준비 완료\",\"checkpointStage\":\"CHUNKS_READY\",\"episodeStatus\":null}"))
                .andExpect(status().isOk());
        em.flush();
        UUID chunkId=UUID.randomUUID();
        sql.update("insert into setting_book_chunks(id,analysis_job_id,setting_book_revision_id,chunk_index,chunk_text,start_offset,end_offset,paragraph_start_index,paragraph_end_index,created_at,updated_at) values(?,?,?,0,?,0,9,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",chunkId,jobId,UUID.fromString(book.path("revisionId").asText()),"하린은 기사다.");
        UUID reserved=UUID.randomUUID(); reserveTokens(jobId,firstLease,reserved,1000,200);em.flush();
        assertThat(sql.queryForObject("select reserved_tokens from ai_token_accounts where member_id=?",Long.class,work.getMember().getId())).isEqualTo(1000L);
        reserveTokens(jobId,firstLease,UUID.randomUUID(),2000001,409);
        mvc.perform(post("/api/internal/v1/ai-token-usages/{id}/release",reserved).header("X-Internal-Api-Key","test-internal-api-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"USAGE_UNAVAILABLE\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/fail",jobId).header("X-Internal-Api-Key","test-internal-api-key").header("X-Worker-Lease-Token",firstLease)
                .contentType(MediaType.APPLICATION_JSON).content("{\"failureCode\":\"AI_TOKEN_QUOTA_EXHAUSTED\",\"errorMessage\":\"합성 quota 실패\"}")).andExpect(status().isOk());
        assertThat(analyze(book.path("id").asText()).path("id").asText()).isEqualTo(jobId.toString());
        JsonNode resumed=claimBook();String lease=resumed.path("leaseToken").asText();
        assertThat(resumed.path("checkpointStage").asText()).isEqualTo("CHUNKS_READY");
        assertThat(lease).isNotEqualTo(firstLease);
        assertThat(sql.queryForList("select id from setting_book_chunks where analysis_job_id=?",UUID.class,jobId)).containsExactly(chunkId);
        mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/heartbeat",jobId).header("X-Internal-Api-Key","test-internal-api-key").header("X-Worker-Lease-Token",firstLease)).andExpect(status().isConflict());
        reserveTokens(jobId,firstLease,UUID.randomUUID(),1000,409);
        UUID settled=UUID.randomUUID();reserveTokens(jobId,lease,settled,1000,200);
        mvc.perform(post("/api/internal/v1/ai-token-usages/{id}/settle",settled).header("X-Internal-Api-Key","test-internal-api-key").contentType(MediaType.APPLICATION_JSON)
                .content("{\"inputTokens\":120,\"cachedInputTokens\":20,\"outputTokens\":30,\"outcome\":\"SUCCESS\"}")).andExpect(status().isOk());
        em.flush();
        assertThat(sql.queryForObject("select used_tokens from ai_token_accounts where member_id=?",Long.class,work.getMember().getId())).isEqualTo(150L);
        UUID canceled=UUID.randomUUID();reserveTokens(jobId,lease,canceled,1000,200);
        mvc.perform(patch("/api/v1/works/{workId}/setting-books/{id}",work.getId(),book.path("id").asText()).header("Authorization",bearer)
                .contentType(MediaType.APPLICATION_JSON).content(editRequest(book.path("id").asText(), "{\"content\":\"하린은 마법사다.\"}"))).andExpect(status().isOk());
        em.flush();
        assertThat(sql.queryForObject("select reserved_tokens from ai_token_accounts where member_id=?",Long.class,work.getMember().getId())).isZero();
        assertThat(sql.queryForObject("select status from ai_token_usages where request_id=?",String.class,canceled)).isEqualTo("RELEASED");
    }

    @Autowired org.monitoring.catchholebackend.domain.analysis.service.AnalysisHumanRejectionPolicy rejectionPolicy;
    @Autowired org.monitoring.catchholebackend.domain.work.repository.WorkPurgeDataRepository purgeData;

    @Test void humanRejectedBookDiscoveryAndWorldClaimSurviveSameContentAttemptButChangedEvidenceReevaluates() throws Exception {
        JsonNode book=upload("하린은 기사다. 엘프의 서식지는 북부다.");
        var first=jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var j=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var evidence=j.arrayNode().add(j.objectNode().put("quote","하린은 기사다.").put("startOffset",0).put("endOffset",9));
        var discovery=org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.createCharacterDiscovery(work,null,null,first,"하린","하린",null,
                org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED,evidence,java.math.BigDecimal.ONE,
                j.objectNode().put("candidate_kind","CHARACTER_DISCOVERY").put("entity_type","CHARACTER").put("entity_name","하린").put("raw_entity_mention","하린"));em.persist(discovery);
        var target=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.create(work,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"엘프","서식지","북부");em.persist(target);
        var world=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(work,null,first,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"엘프","서식지","북부",evidence,java.math.BigDecimal.ONE,null);
        org.springframework.test.util.ReflectionTestUtils.setField(world,"targetWorldSetting",target);em.persist(world);em.flush();
        completeFixtureOrigin(first);
        mvc.perform(post("/api/v1/works/{workId}/setting-candidates/{id}/dismiss",work.getId(),discovery.getId()).header("Authorization",bearer).param("sourceAnalysisJobId",first.getId().toString())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{id}/dismiss",work.getId(),world.getId()).header("Authorization",bearer).param("sourceAnalysisJobId",first.getId().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        JsonNode state=j.objectNode().set("references",rejectionPolicy.capture(work));
        assertThat(state.path("references")).hasSize(2);
        var next=jobs.findById(UUID.fromString(analyze(book.path("id").asText()).path("id").asText())).orElseThrow();
        var repeated=org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.createCharacterDiscovery(work,null,null,next,"하린","하린",null,
                org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED,evidence.deepCopy(),java.math.BigDecimal.ONE,discovery.getRawAiResultJson().deepCopy());em.persist(repeated);
        var repeatedWorld=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(work,null,next,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"엘프","서식지","북부",evidence.deepCopy(),java.math.BigDecimal.ONE,null);
        org.springframework.test.util.ReflectionTestUtils.setField(repeatedWorld,"targetWorldSetting",target);em.persist(repeatedWorld);em.flush();
        assertThat(rejectionPolicy.match(repeated,state)).isPresent();
        assertThat(rejectionPolicy.match(repeatedWorld,state)).isPresent();
        org.springframework.test.util.ReflectionTestUtils.setField(repeatedWorld,"evidenceSpans",j.arrayNode().add(j.objectNode().put("quote","하린은 기사다.").put("startOffset",1).put("endOffset",10)));
        assertThat(rejectionPolicy.match(repeatedWorld,state)).isEmpty();
        mvc.perform(patch("/api/v1/works/{workId}/setting-books/{id}",work.getId(),book.path("id").asText()).header("Authorization",bearer)
                .contentType(MediaType.APPLICATION_JSON).content(editRequest(book.path("id").asText(), "{\"content\":\"하린은 기사다. 엘프의 서식지는 남부다.\"}"))).andExpect(status().isOk());
        var changed=jobs.findById(UUID.fromString(analyze(book.path("id").asText()).path("id").asText())).orElseThrow();
        var changedClaim=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(work,null,changed,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"엘프","서식지","북부",evidence,java.math.BigDecimal.ONE,null);
        org.springframework.test.util.ReflectionTestUtils.setField(changedClaim,"targetWorldSetting",target);em.persist(changedClaim);
        assertThat(rejectionPolicy.match(changedClaim,state)).isEmpty();
    }

    @Test void humanBookExclusionsAreBookScopedAndReferenceRoundTripReuseDoesNotRestoreOldLease() throws Exception {
        String text = "하린은 기사다. 엘프의 서식지는 북부다.";
        var book = uploadReference(text, "WORK_START", null);
        var first = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var characterEvidence = j.arrayNode().add(j.objectNode().put("quote", "하린은 기사다.").put("startOffset", 0).put("endOffset", 9));
        var worldEvidence = j.arrayNode().add(j.objectNode().put("quote", "엘프의 서식지는 북부다.").put("startOffset", 10).put("endOffset", 24));
        var discovery = rejectionDiscovery(first, characterEvidence);
        var actual = WorldSetting.create(work, WorldSettingCategory.RACE, "엘프", "서식지", "북부"); em.persist(actual);
        var world = rejectionWorld(first, actual, worldEvidence);
        em.persist(discovery); em.persist(world); completeFixtureOrigin(first);
        mvc.perform(post("/api/v1/works/{workId}/setting-candidates/{id}/dismiss", work.getId(), discovery.getId()).header("Authorization", bearer)
                .param("sourceAnalysisJobId", first.getId().toString())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{id}/dismiss", work.getId(), world.getId()).header("Authorization", bearer)
                .param("sourceAnalysisJobId", first.getId().toString()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        var state = j.objectNode().set("references", rejectionPolicy.capture(work));
        assertThat(state.path("references")).hasSize(2);

        var otherBook = uploadReference(text, "WORK_START", null);
        var other = jobs.findById(UUID.fromString(otherBook.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        assertThat(other.getSourceContentHash()).isEqualTo(first.getSourceContentHash());
        assertThat(other.getSourceSettingBook().getId()).isNotEqualTo(first.getSourceSettingBook().getId());
        assertThat(rejectionPolicy.match(rejectionDiscovery(other, characterEvidence.deepCopy()), state)).isEmpty();
        assertThat(rejectionPolicy.match(rejectionWorld(other, actual, worldEvidence.deepCopy()), state)).isEmpty();

        mvc.perform(patch("/api/v1/works/{workId}/setting-books/{id}", work.getId(), book.path("id").asText()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(editRequest(book.path("id").asText(), json.writeValueAsString(Map.of("content", text, "referenceTimeKind", "EPISODE", "referenceEpisodeNo", 3)))))
                .andExpect(status().isOk());
        var changed = jobs.findById(UUID.fromString(analyze(book.path("id").asText()).path("id").asText())).orElseThrow();
        assertThat(changed.getSourceContentHash()).isEqualTo(first.getSourceContentHash());
        assertThat(changed.getSourceSettingBookRevision().getId()).isNotEqualTo(first.getSourceSettingBookRevision().getId());
        assertThat(rejectionPolicy.match(rejectionDiscovery(changed, characterEvidence.deepCopy()), state)).isEmpty();
        assertThat(rejectionPolicy.match(rejectionWorld(changed, actual, worldEvidence.deepCopy()), state)).isEmpty();

        mvc.perform(patch("/api/v1/works/{workId}/setting-books/{id}", work.getId(), book.path("id").asText()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(editRequest(book.path("id").asText(), json.writeValueAsString(Map.of("content", text, "referenceTimeKind", "WORK_START")))))
                .andExpect(status().isOk());
        var restored = jobs.findById(UUID.fromString(analyze(book.path("id").asText()).path("id").asText())).orElseThrow();
        assertThat(restored.getSourceContentHash()).isEqualTo(first.getSourceContentHash());
        assertThat(restored.getSourceSettingBookRevision().getId()).isNotEqualTo(first.getSourceSettingBookRevision().getId())
                .isNotEqualTo(changed.getSourceSettingBookRevision().getId());
        assertThat(rejectionPolicy.match(rejectionDiscovery(restored, characterEvidence.deepCopy()), state)).isPresent();
        assertThat(rejectionPolicy.match(rejectionWorld(restored, actual, worldEvidence.deepCopy()), state)).isPresent();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sourceGuard.assertCurrent(first, null))
                .isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sourceGuard.assertCurrent(changed, null))
                .isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
        sourceGuard.assertCurrent(restored, null);
        assertThat(discovery.getReviewStatus().name()).isEqualTo("DISMISSED");
        assertThat(world.getReviewStatus().name()).isEqualTo("DISMISSED");
    }

    SettingCandidate rejectionDiscovery(AnalysisJob origin, JsonNode evidence) {
        var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        return SettingCandidate.createCharacterDiscovery(work, null, null, origin, "하린", "하린", null,
                org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED, evidence, java.math.BigDecimal.ONE,
                j.objectNode().put("candidate_kind", "CHARACTER_DISCOVERY").put("entity_type", "CHARACTER").put("entity_name", "하린").put("raw_entity_mention", "하린"));
    }

    WorldSettingCandidate rejectionWorld(AnalysisJob origin, WorldSetting target, JsonNode evidence) {
        var row = WorldSettingCandidate.create(work, null, origin, WorldSettingCategory.RACE, "엘프", "서식지", "북부", evidence, java.math.BigDecimal.ONE, null);
        org.springframework.test.util.ReflectionTestUtils.setField(row, "targetWorldSetting", target);
        return row;
    }

    void completeFixtureOrigin(org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob origin) {
        // Arrange a completed source independently from worker extraction; its pending candidates remain human-reviewable.
        states.prepareInput(origin);
        origin.claim("synthetic-only","완료 fixture",java.time.LocalDateTime.now().plusMinutes(5));
        var record=(com.fasterxml.jackson.databind.node.ObjectNode)origin.getStateJournal().deepCopy();
        record.put("outputStateHash","a".repeat(64));origin.replacePendingJournal(record);origin.sealJournal();
        origin.completeAutomaticApplication();origin.succeed(null,0,0);
        em.flush();
    }

    @Test void workPurgeDeletesAllImmutableBookRevisionsChunksAndCandidates() throws Exception {
        var book=upload("하린은 기사다.");var first=jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        UUID firstRevision=first.getSourceSettingBookRevision().getId();
        mvc.perform(patch("/api/v1/works/{workId}/setting-books/{id}",work.getId(),book.path("id").asText()).header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
                .content(editRequest(book.path("id").asText(), "{\"content\":\"하린은 마법사다.\"}"))).andExpect(status().isOk());
        analyze(book.path("id").asText());em.flush();
        sql.update("insert into setting_book_chunks(id,analysis_job_id,setting_book_revision_id,chunk_index,chunk_text,start_offset,end_offset,paragraph_start_index,paragraph_end_index,created_at,updated_at) values(?,?,?,0,?,0,9,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",UUID.randomUUID(),first.getId(),firstRevision,"하린은 기사다.");
        assertThat(sql.queryForObject("select count(*) from setting_book_revisions where setting_book_id=?",Integer.class,first.getSourceSettingBook().getId())).isEqualTo(2);
        purgeData.purgeWorkData(work.getId());em.clear();
        assertThat(sql.queryForObject("select count(*) from setting_book_revisions where setting_book_id=?",Integer.class,first.getSourceSettingBook().getId())).isZero();
        assertThat(sql.queryForObject("select count(*) from setting_book_chunks where analysis_job_id=?",Integer.class,first.getId())).isZero();
        assertThat(sql.queryForObject("select count(*) from analysis_jobs where work_id=?",Integer.class,work.getId())).isZero();
        assertThat(sql.queryForObject("select count(*) from works where id=?",Integer.class,work.getId())).isZero();
    }

    @Autowired org.monitoring.catchholebackend.domain.analysis.service.AnalysisPendingReferenceSource pendingReferences;
    @Autowired org.monitoring.catchholebackend.domain.analysis.service.AnalysisCandidateSourceGuard sourceGuard;

    @Test void newerSameRevisionBookAttemptSupersedesPendingReferencesAndOldReview() throws Exception {
        var book=upload("하린은 기사다.");var first=jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var j=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var evidence=j.arrayNode().add(j.objectNode().put("quote","하린은 기사다.").put("startOffset",0).put("endOffset",9));
        var discovery=org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.createCharacterDiscovery(work,null,null,first,"하린","하린",null,
                org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED,evidence,java.math.BigDecimal.ONE,null);em.persist(discovery);
        var pending=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(work,null,first,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"엘프","서식지","북부",evidence,java.math.BigDecimal.ONE,null);
        org.springframework.test.util.ReflectionTestUtils.setField(pending,"comparisonStatus",org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus.FAILED);em.persist(pending);completeFixtureOrigin(first);
        var episode=org.monitoring.catchholebackend.domain.episode.entity.Episode.create(work,null,1,"1화","synthetic/one.txt","v1","d".repeat(64),10);em.persist(episode);
        var reader=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.create(work,null,episode,org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION);em.persist(reader);em.flush();
        assertThat(pendingReferences.capture(reader)).hasSize(2);
        var hiddenCharacter=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.createCharacterFactComparison(discovery);em.persist(hiddenCharacter);
        var hiddenWorld=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.createWorldSettingComparison(pending);
        UUID hiddenLease=hiddenWorld.claim("synthetic-only","비교 fixture",java.time.LocalDateTime.now().plusMinutes(5));em.persist(hiddenWorld);em.flush();
        JsonNode scoped=response(mvc.perform(get("/api/v1/works/{workId}/world-setting-candidates",work.getId()).header("Authorization",bearer).param("batchId",first.getBatch().getId().toString()).param("sourceAnalysisJobId",first.getId().toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(scoped.path("activeComparisonJobCount").asInt()).isEqualTo(1);
        JsonNode episodesOnly=response(mvc.perform(get("/api/v1/works/{workId}/world-setting-candidates",work.getId()).header("Authorization",bearer).param("batchId",first.getBatch().getId().toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(episodesOnly.path("activeComparisonJobCount").asInt()).isZero();
        var newer=jobs.findById(UUID.fromString(analyze(book.path("id").asText()).path("id").asText())).orElseThrow();
        assertThat(newer.getSourceSettingBookRevision().getId()).isEqualTo(first.getSourceSettingBookRevision().getId());
        assertThat(hiddenCharacter.getStatus()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.CANCELED);
        assertThat(hiddenWorld.getStatus()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.CANCELED);
        mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/heartbeat",hiddenWorld.getId()).header("X-Internal-Api-Key","test-internal-api-key").header("X-Worker-Lease-Token",hiddenLease)).andExpect(status().isConflict());
        assertThat(pendingReferences.capture(reader)).isEmpty();
        for (var entry : java.util.Map.of("setting-candidates",discovery.getId(),"world-setting-candidates",pending.getId()).entrySet()) {
            JsonNode oldHistory=response(mvc.perform(get("/api/v1/works/{workId}/"+entry.getKey()+"/{id}",work.getId(),entry.getValue())
                    .header("Authorization",bearer).param("batchId",first.getBatch().getId().toString()).param("sourceAnalysisJobId",first.getId().toString()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(oldHistory.path("manualReviewAvailable").asBoolean()).isFalse();
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sourceGuard.assertCurrent(first,null)).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
        mvc.perform(post("/api/v1/works/{workId}/setting-candidates/{id}/dismiss",work.getId(),discovery.getId()).header("Authorization",bearer).param("sourceAnalysisJobId",first.getId().toString())).andExpect(status().isConflict());
        var latest=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(work,null,newer,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"엘프","서식지","남부",evidence,java.math.BigDecimal.ONE,null);
        org.springframework.test.util.ReflectionTestUtils.setField(latest,"comparisonStatus",org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus.FAILED);em.persist(latest);completeFixtureOrigin(newer);
        JsonNode references=pendingReferences.capture(reader);
        assertThat(references).hasSize(1);assertThat(references.has("pending-world:"+latest.getId())).isTrue();
        assertThat(references.elements().next().path("sourceKind").asText()).isEqualTo("SETTING_BOOK");
        assertThat(references.elements().next().path("sourceSettingBookRevisionId").asText()).isEqualTo(newer.getSourceSettingBookRevision().getId().toString());
        assertThat(discovery.getReviewStatus()).isEqualTo(org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.PENDING_REVIEW);
    }

    @Test void scopedGroupMutationsApplyBookAndRejectMixedOriginAtomicallyWithOverlappingNames() throws Exception {
        var j=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        if(sql.queryForObject("select count(*) from character_setting_schemas where schema_key='age'",Integer.class)==0) em.persist(org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema.create(work,"age",null,"나이",
                org.monitoring.catchholebackend.domain.character.type.CharacterFactType.AGE,org.monitoring.catchholebackend.domain.character.type.SettingValueType.NUMBER,
                org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics.BASE_VALUE,org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy.REPLACE,
                j.arrayNode(),org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource.SYSTEM_SEED,true));
        var book=upload("하린은 18살이다. 엘프는 북부에 산다.");var origin=jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var episode=org.monitoring.catchholebackend.domain.episode.entity.Episode.create(work,null,1,"1화","synthetic/one.txt","v1","d".repeat(64),10);em.persist(episode);
        var epJob=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.create(work,origin.getBatch(),episode,org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION);epJob.succeed(null,0,0);em.persist(epJob);
        var bookAge=ageCandidate(origin,null,18);var epAge=ageCandidate(epJob,episode,99);em.persist(bookAge);em.persist(epAge);
        var evidence=j.arrayNode().add(j.objectNode().put("quote","엘프는 북부에 산다.").put("startOffset",12).put("endOffset",24));
        var bookWorld=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(work,null,origin,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"엘프","서식지","북부",evidence,java.math.BigDecimal.ONE,null);
        var epWorld=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(work,episode,epJob,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"엘프","서식지","남부",evidence,java.math.BigDecimal.ONE,null);
        em.persist(bookWorld);em.persist(epWorld);em.flush();
        for(var row : java.util.Map.of("setting-candidates",bookAge.getId(),"world-setting-candidates",bookWorld.getId()).entrySet()) {
            JsonNode pending=response(mvc.perform(get("/api/v1/works/{workId}/"+row.getKey()+"/{id}",work.getId(),row.getValue()).header("Authorization",bearer).param("batchId",origin.getBatch().getId().toString()).param("sourceAnalysisJobId",origin.getId().toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(pending.path("automaticApplicationPending").asBoolean()).isTrue();assertThat(pending.path("manualReviewAvailable").asBoolean()).isFalse();
            mvc.perform(post("/api/v1/works/{workId}/"+row.getKey()+"/{id}/dismiss",work.getId(),row.getValue()).header("Authorization",bearer).param("batchId",origin.getBatch().getId().toString()).param("sourceAnalysisJobId",origin.getId().toString()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isConflict());
        }
        completeFixtureOrigin(origin);em.flush();
        var bookDecision=java.util.Map.of("candidateId",bookAge.getId(),"applicationMode","APPLY_PROPOSAL");
        var epDecision=java.util.Map.of("candidateId",epAge.getId(),"applicationMode","APPLY_PROPOSAL");
        mvc.perform(post("/api/v1/works/{workId}/setting-candidates/group-confirm",work.getId()).header("Authorization",bearer).param("sourceAnalysisJobId",origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("batchId",origin.getBatch().getId(),"candidates",java.util.List.of(bookDecision,epDecision))))).andExpect(status().is4xxClientError());
        assertThat(bookAge.getReviewStatus()).isEqualTo(org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.PENDING_REVIEW);
        assertThat(epAge.getReviewStatus()).isEqualTo(org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.PENDING_REVIEW);
        mvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/group-dismiss",work.getId()).header("Authorization",bearer).param("sourceAnalysisJobId",origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("batchId",origin.getBatch().getId(),"candidateIds",java.util.List.of(bookWorld.getId(),epWorld.getId()))))).andExpect(status().is4xxClientError());
        assertThat(bookWorld.getReviewStatus()).isEqualTo(org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.PENDING_REVIEW);
        assertThat(epWorld.getReviewStatus()).isEqualTo(org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.PENDING_REVIEW);
        mvc.perform(post("/api/v1/works/{workId}/setting-candidates/group-confirm",work.getId()).header("Authorization",bearer).param("sourceAnalysisJobId",origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("batchId",origin.getBatch().getId(),"candidates",java.util.List.of(bookDecision))))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/group-dismiss",work.getId()).header("Authorization",bearer).param("sourceAnalysisJobId",origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("batchId",origin.getBatch().getId(),"candidateIds",java.util.List.of(bookWorld.getId()))))).andExpect(status().isOk());
        assertThat(bookAge.getReviewStatus()).isEqualTo(org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CONFIRMED);
        assertThat(bookWorld.getReviewStatus()).isEqualTo(org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.DISMISSED);
        assertThat(epAge.getReviewStatus()).isEqualTo(org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.PENDING_REVIEW);
        assertThat(epWorld.getReviewStatus()).isEqualTo(org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.PENDING_REVIEW);
        em.flush();assertThat(sql.queryForObject("select count(*) from character_facts where extracted_by_job_id=?",Integer.class,origin.getId())).isEqualTo(1);
    }

    org.monitoring.catchholebackend.domain.character.entity.SettingCandidate ageCandidate(org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob origin,
            org.monitoring.catchholebackend.domain.episode.entity.Episode episode,int age) {
        var j=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        em.flush();
        var characters=sql.queryForList("select id from characters where work_id=? and name='하린'",UUID.class,work.getId());
        org.monitoring.catchholebackend.domain.character.entity.WorkCharacter character;
        if(characters.isEmpty()) {character=org.monitoring.catchholebackend.domain.character.entity.WorkCharacter.create(work,"하린",null,null,null,null,null,null,null,null,null);em.persist(character);em.flush();}
        else character=em.find(org.monitoring.catchholebackend.domain.character.entity.WorkCharacter.class,characters.getFirst());
        var candidate=org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.create(work,episode,null,origin,org.monitoring.catchholebackend.domain.character.type.SettingEntityType.CHARACTER,"하린","하린",character.getId(),
                org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.MATCHED,"age",Integer.toString(age),org.monitoring.catchholebackend.domain.character.type.SettingValueType.NUMBER,
                j.objectNode().put("value",age),controlledEvidence(origin, "하린은 "+age+"살이다.", 11),java.math.BigDecimal.ONE,null);
        candidate.startComparison();candidate.recordComparisonContext(0,"synthetic-context");candidate.completeComparison(org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation.ADD,
                org.monitoring.catchholebackend.domain.character.type.CharacterFactType.AGE,"age",Integer.toString(age),j.objectNode().put("value",age),j.arrayNode(),org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope.PRESENT,
                "합성 원문 근거",j.objectNode(),java.time.LocalDateTime.now(),"age",j.arrayNode());return candidate;
    }

    @Test void mixedBatchQuotaResumeUsesOnlyEpisodeScopeAndRejectsBookOriginResumeOrForgedScope() throws Exception {
        var book=upload("엘프는 북부에 산다.");
        var origin=jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        completeFixtureOrigin(origin);
        var episode=org.monitoring.catchholebackend.domain.episode.entity.Episode.create(work,null,1,"1화","synthetic/one.txt","v1","d".repeat(64),10);em.persist(episode);
        var epJob=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.create(work,origin.getBatch(),episode,org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION);
        epJob.succeed(null,0,0);em.persist(epJob);
        var j=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var bookRow=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(work,null,origin,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"엘프","서식지","북부",j.arrayNode(),java.math.BigDecimal.ONE,null);
        var episodeRow=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(work,episode,epJob,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"엘프","서식지","남부",j.arrayNode(),java.math.BigDecimal.ONE,null);
        bookRow.interruptComparisonForTokenQuota("합성 사용량 중단");episodeRow.interruptComparisonForTokenQuota("합성 사용량 중단");em.persist(bookRow);em.persist(episodeRow);em.flush();
        String route="/api/v1/works/{workId}/world-setting-candidates/batches/{batchId}/resume-token-interrupted";
        mvc.perform(post(route,work.getId(),origin.getBatch().getId()).header("Authorization",bearer).param("sourceAnalysisJobId",origin.getId().toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ANALYSIS_ORDERED_JOB_RETRY_REQUIRED"));
        mvc.perform(post(route,work.getId(),origin.getBatch().getId()).header("Authorization",bearer).param("sourceAnalysisJobId",UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        mvc.perform(post(route,work.getId(),origin.getBatch().getId()).header("Authorization",bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resumedCandidateCount").value(1));
        mvc.perform(post(route,work.getId(),origin.getBatch().getId()).header("Authorization",bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resumedCandidateCount").value(0));
        em.flush();
        assertThat(bookRow.isTokenInterruptedComparison()).isTrue();assertThat(episodeRow.isTokenInterruptedComparison()).isFalse();
        assertThat(sql.queryForObject("select count(*) from analysis_jobs where world_setting_candidate_id=? and status in ('PENDING','RUNNING')",Integer.class,bookRow.getId())).isZero();
        assertThat(sql.queryForObject("select count(*) from analysis_jobs where world_setting_candidate_id=? and status in ('PENDING','RUNNING')",Integer.class,episodeRow.getId())).isEqualTo(1);
        JsonNode defaultPage=response(mvc.perform(get("/api/v1/works/{workId}/world-setting-candidates",work.getId()).header("Authorization",bearer).param("batchId",origin.getBatch().getId().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode bookPage=response(mvc.perform(get("/api/v1/works/{workId}/world-setting-candidates",work.getId()).header("Authorization",bearer).param("batchId",origin.getBatch().getId().toString()).param("sourceAnalysisJobId",origin.getId().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(defaultPage.path("activeComparisonJobCount").asInt()).isEqualTo(1);assertThat(bookPage.path("activeComparisonJobCount").asInt()).isZero();
        analyze(book.path("id").asText());
        mvc.perform(post(route,work.getId(),origin.getBatch().getId()).header("Authorization",bearer).param("sourceAnalysisJobId",origin.getId().toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ANALYSIS_JOB_SUPERSEDED"));
        assertThat(sql.queryForObject("select count(*) from analysis_jobs where world_setting_candidate_id=? and status='PENDING'",Integer.class,episodeRow.getId())).isEqualTo(1);
    }

    @Test void bookAndEpisodeCurrentValuesCanAdvanceBothWaysAndAuthorEditsRemainProtected() throws Exception {
        ensureAgeSchema();
        var book=uploadReference("하린은 18살이다. 엘프는 북부에 산다.", "WORK_START", null);
        var bookOrigin=jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var bookAge=ageCandidate(bookOrigin,null,18);em.persist(bookAge);
        var bookWorld=comparableWorld(bookOrigin,null,null,"북부");
        completeFixtureOrigin(bookOrigin);
        confirmAgeGroup(bookAge);confirmWorld(bookWorld);
        var character=em.find(org.monitoring.catchholebackend.domain.character.entity.WorkCharacter.class,bookAge.getMatchedCharacterId());
        var currentWorld=bookWorld.getTargetWorldSetting();
        assertThat(character.getCurrentAge()).isEqualTo(18);
        assertThat(currentWorld.getPropertyValue("서식지")).isEqualTo("북부");
        assertThat(bookAge.getConfirmedApplicationMode().name()).isEqualTo("APPLY_PROPOSAL");
        assertThat(bookWorld.isHistoryOnly()).isFalse();

        var episodeOrigin=episodeOrderedOrigin(1, "하린은 19살이다. 엘프는 남부에 산다.");
        var episodeAge=ageCandidate(episodeOrigin,episodeOrigin.getEpisode(),19);em.persist(episodeAge);
        var episodeWorld=comparableWorld(episodeOrigin,episodeOrigin.getEpisode(),currentWorld,"남부");
        completeFixtureOrigin(episodeOrigin);confirmAgeGroup(episodeAge);confirmWorld(episodeWorld);
        assertThat(character.getCurrentAge()).isEqualTo(19);
        assertThat(currentWorld.getPropertyValue("서식지")).isEqualTo("남부");
        assertThat(episodeAge.getConfirmedApplicationMode().name()).isEqualTo("APPLY_PROPOSAL");
        assertThat(episodeWorld.isHistoryOnly()).isFalse();

        mvc.perform(patch("/api/v1/works/{workId}/setting-books/{id}", work.getId(), book.path("id").asText())
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content(editRequest(book.path("id").asText(), json.writeValueAsString(Map.of("content", "하린은 20살이다. 엘프는 서부에 산다.", "referenceTimeKind", "EPISODE", "referenceEpisodeNo", 2)))))
                .andExpect(status().isOk());
        var nextBook=jobs.findById(UUID.fromString(analyze(book.path("id").asText()).path("id").asText())).orElseThrow();
        var nextAge=ageCandidate(nextBook,null,20);em.persist(nextAge);
        var nextWorld=comparableWorld(nextBook,null,currentWorld,"서부");
        completeFixtureOrigin(nextBook);confirmAgeGroup(nextAge);confirmWorld(nextWorld);
        assertThat(character.getCurrentAge()).isEqualTo(20);
        assertThat(currentWorld.getPropertyValue("서식지")).isEqualTo("서부");
        assertThat(nextAge.getConfirmedApplicationMode().name()).isEqualTo("APPLY_PROPOSAL");
        assertThat(nextWorld.isHistoryOnly()).isFalse();

        mvc.perform(patch("/api/v1/works/{workId}/characters/{id}",work.getId(),character.getId())
                .header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"하린\",\"currentAge\":77,\"profile\":[],\"stats\":[],\"skills\":[],\"items\":[],\"statuses\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.currentAge").value(77));
        mvc.perform(patch("/api/v1/works/{workId}/world-settings/{id}/properties",work.getId(),currentWorld.getId())
                .header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentSettingName","서식지","settingName","서식지","settingValue","작가가 정한 지역","version",currentWorld.getVersion()))))
                .andExpect(status().isOk());
        long authorCharacterVersion=character.getSnapshotVersion();long authorWorldVersion=currentWorld.getVersion();
        var protectedOrigin=jobs.findById(UUID.fromString(analyze(book.path("id").asText()).path("id").asText())).orElseThrow();
        var protectedAge=ageCandidate(protectedOrigin,null,21);em.persist(protectedAge);
        var protectedWorld=comparableWorld(protectedOrigin,null,currentWorld,"동부");
        completeFixtureOrigin(protectedOrigin);confirmAgeGroup(protectedAge);confirmWorld(protectedWorld);
        assertThat(character.getCurrentAge()).isEqualTo(77);
        assertThat(currentWorld.getPropertyValue("서식지")).isEqualTo("작가가 정한 지역");
        assertThat(character.getSnapshotVersion()).isEqualTo(authorCharacterVersion);
        assertThat(currentWorld.getVersion()).isEqualTo(authorWorldVersion);
        assertThat(protectedAge.getConfirmedApplicationMode().name()).isEqualTo("HISTORY_ONLY");
        assertThat(protectedWorld.isHistoryOnly()).isTrue();
        em.flush();
        assertThat(sql.queryForList("select fact_value from character_facts where character_id=? and fact_key='age'",String.class,character.getId()))
                .contains("18","19","20","21");
    }

    void ensureAgeSchema() {
        var j=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        if(sql.queryForObject("select count(*) from character_setting_schemas where schema_key='age'",Integer.class)==0)
            em.persist(org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema.create(work,"age",null,"나이",
                    org.monitoring.catchholebackend.domain.character.type.CharacterFactType.AGE,org.monitoring.catchholebackend.domain.character.type.SettingValueType.NUMBER,
                    org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics.BASE_VALUE,org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy.REPLACE,
                    j.arrayNode(),org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource.SYSTEM_SEED,true));
        em.flush();
    }

    @Autowired org.monitoring.catchholebackend.domain.character.service.SettingCandidateServiceImpl characterCandidates;
    @Autowired org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingCandidateServiceImpl worldCandidates;
    @Autowired org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor snapshotAccessor;

    @Test void bookAutomaticCannotOverwriteHumanCurrentEditedBeforeAnalysis() throws Exception {
        ensureAgeSchema();
        WorkCharacter character = manualCharacter(77);
        WorldSetting world = manualWorld();
        var book = upload("하린은 21살이다. 엘프는 동부에 산다.");
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        SettingCandidate age = ageCandidate(origin, null, 21); em.persist(age);
        attachAutomaticContext(age, character, CharacterFactOperation.UPDATE);
        WorldSettingCandidate row = comparableWorld(origin, null, world, "동부");
        attachAutomaticWorld(row, world);
        completeFixtureOrigin(origin);
        characterCandidates.applyAutomatically(origin); worldCandidates.applyAutomatically(origin); em.flush();
        assertThat(character.getCurrentAge()).isEqualTo(77);
        assertThat(world.getPropertyValue("서식지")).isEqualTo("작가가 정한 지역");
        assertThat(age.getReviewStatus().name()).isEqualTo("PENDING_REVIEW");
        assertThat(row.getReviewStatus().name()).isEqualTo("PENDING_REVIEW");
        assertThat(age.getAutomaticReviewHoldReason().name()).isEqualTo("HUMAN_CURRENT_SETTING_PROTECTED");
        assertThat(row.getAutomaticReviewHoldReason().name()).isEqualTo("HUMAN_CURRENT_SETTING_PROTECTED");
    }

    @Test void deletedCharacterSlotRemainsProtectedInBookInputAndAutomaticAdd() throws Exception {
        ensureAgeSchema();
        WorkCharacter character = manualCharacter(77);
        mvc.perform(patch("/api/v1/works/{workId}/characters/{id}", work.getId(), character.getId())
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"하린\",\"currentAge\":null,\"profile\":[],\"stats\":[],\"skills\":[],\"items\":[],\"statuses\":[]}"))
                .andExpect(status().isOk());
        var book = upload("하린은 21살이다.");
        JsonNode claim = claimBook();
        JsonNode known = claim.path("knownCharacters").get(0);
        assertThat(known.path("protectedSlots")).anySatisfy(slot -> {
            assertThat(slot.path("factType").asText()).isEqualTo("AGE");
            assertThat(slot.path("factKey").asText()).isEqualTo("age");
        });
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var age = ageCandidate(origin, null, 21); em.persist(age);
        attachAutomaticContext(age, character, CharacterFactOperation.ADD);
        characterCandidates.applyAutomatically(origin); em.flush();
        assertThat(character.getCurrentAge()).isNull();
        assertThat(age.getReviewStatus().name()).isEqualTo("PENDING_REVIEW");
        assertThat(age.getAutomaticReviewHoldReason().name()).isEqualTo("HUMAN_CURRENT_SETTING_PROTECTED");
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"MERGE", "REMOVE"})
    void bookAutomaticCollateralRemovalProtectsManualSourceAndAppliesIndependentNewSlot(String operation) throws Exception {
        ensureAgeSchema(); ensureLevelSchema(); ensureStatusSchema("status.부상", "부상"); ensureStatusSchema("status.완치", "완치");
        var character = manualCharacter(77);
        mvc.perform(patch("/api/v1/works/{workId}/characters/{id}", work.getId(), character.getId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"하린","currentAge":77,"profile":[],"stats":[],"skills":[],"items":[],"statuses":[
                        {"key":"status.부상","value":"부상","valueType":"JSON","properties":[
                        {"key":"name","value":"부상","valueType":"STRING"},{"key":"active","value":"true","valueType":"BOOLEAN"}]}]}
                        """))
                .andExpect(status().isOk());
        assertThat(character.isManuallyEditedSlot(CharacterFactType.STATUS, "status.부상")).isTrue();
        var book = uploadReference("하린은 완치되어 부상이 사라진다. 하린의 레벨은 3이다.", "WORK_START", null);
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var collateral = SettingCandidate.create(work, null, null, origin,
                org.monitoring.catchholebackend.domain.character.type.SettingEntityType.CHARACTER, "하린", "하린", character.getId(),
                org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.MATCHED,
                "status.완치", "완치", org.monitoring.catchholebackend.domain.character.type.SettingValueType.JSON,
                j.objectNode().put("name", "완치").put("active", true), controlledEvidence(origin, "하린은 완치되어 부상이 사라진다.", 10),
                java.math.BigDecimal.ONE, null);
        collateral.startComparison(); collateral.recordComparisonContext(character.getSnapshotVersion(), "collateral-context");
        var removals = j.arrayNode().add(j.objectNode().put("factType", "STATUS").put("factKey", "status.부상"));
        collateral.completeComparison(CharacterFactOperation.MERGE, CharacterFactType.STATUS, "status.완치", "완치",
                j.objectNode().put("name", "완치").put("active", true), removals, CharacterFactTemporalScope.PRESENT,
                "강제 부수 삭제 제안", j.objectNode(), java.time.LocalDateTime.now(), "status.완치", j.arrayNode()); em.persist(collateral);
        attachAutomaticContext(collateral, character, CharacterFactOperation.valueOf(operation));
        if (operation.equals("REMOVE")) {
            org.springframework.test.util.ReflectionTestUtils.setField(collateral, "suggestedOperation", CharacterFactOperation.REMOVE);
            org.springframework.test.util.ReflectionTestUtils.setField(collateral, "comparisonTargetFactType", null);
            org.springframework.test.util.ReflectionTestUtils.setField(collateral, "comparisonTargetFactKey", null);
            org.springframework.test.util.ReflectionTestUtils.setField(collateral, "proposedValueJson", null);
            org.springframework.test.util.ReflectionTestUtils.setField(collateral, "proposedFactValue", null);
        }
        var independent = levelCandidate(origin, character, 3); em.persist(independent);
        org.springframework.test.util.ReflectionTestUtils.setField(independent, "evidenceSpans", controlledEvidence(origin, "하린의 레벨은 3이다.", 10));
        attachAutomaticContext(independent, character, CharacterFactOperation.ADD); completeFixtureOrigin(origin);
        characterCandidates.applyAutomatically(origin); em.flush();
        var slots = snapshotAccessor.read(character);
        assertThat(slots).containsKey(new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot(CharacterFactType.STATUS, "status.부상"));
        assertThat(slots).doesNotContainKey(new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot(CharacterFactType.STATUS, "status.완치"));
        assertThat(collateral.isPendingReview()).isTrue(); assertThat(collateral.getAutomaticReviewHoldReason().name()).isEqualTo("HUMAN_CURRENT_SETTING_PROTECTED");
        assertThat(character.getCurrentLevel()).isEqualTo(3); assertThat(independent.getReviewStatus().name()).isEqualTo("CONFIRMED");
        assertThat(character.isManuallyEditedSlot(CharacterFactType.STATUS, "status.부상")).isTrue();
        assertThat(sql.queryForObject("select count(*) from character_facts where extracted_by_job_id=?", Integer.class, origin.getId())).isEqualTo(1);
    }

    @Test void bookAutomaticProtectedPredecessorBlocksDependentMergeAndAppliesIndependentSibling() throws Exception {
        ensureAgeSchema(); ensureLevelSchema(); var character = manualCharacter(77);
        var book = uploadReference("하린은 21살이다. 새 기록은 22살이라고 보완한다. 하린의 레벨은 3이다.", "WORK_START", null);
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow(); states.prepareInput(origin);
        origin.claim("synthetic-only", "의존성 검증", java.time.LocalDateTime.now().plusMinutes(5));
        var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var predecessor = ageCandidate(origin, null, 21); em.persist(predecessor); attachAutomaticContext(predecessor, character, CharacterFactOperation.UPDATE);
        characterStates.recordDecision(origin, predecessor, new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry(
                new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot(CharacterFactType.AGE, "age"), "21", j.objectNode().put("value", 21), true),
                java.util.List.of(), java.util.List.of());
        var dependent = ageCandidate(origin, null, 22); em.persist(dependent);
        attachAutomaticContext(dependent, character, CharacterFactOperation.MERGE);
        org.springframework.test.util.ReflectionTestUtils.setField(dependent, "comparisonDependencyCandidateIds", j.arrayNode().add(predecessor.getId().toString()));
        characterStates.recordDecision(origin, dependent, new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry(
                new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot(CharacterFactType.AGE, "age"), "22", j.objectNode().put("value", 22), true),
                java.util.List.of(), java.util.List.of(predecessor.getId()));
        var independent = levelCandidate(origin, character, 3); em.persist(independent);
        org.springframework.test.util.ReflectionTestUtils.setField(independent, "evidenceSpans", controlledEvidence(origin, "하린의 레벨은 3이다.", 10));
        attachAutomaticContext(independent, character, CharacterFactOperation.ADD);
        characterStates.recordDecision(origin, independent, new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry(
                new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot(CharacterFactType.LEVEL, "level"), "3", j.objectNode().put("value", 3), true),
                java.util.List.of(), java.util.List.of());
        for (var candidate : java.util.List.of(predecessor, dependent, independent))
            candidate.getCharacterComparisonBatch().complete("e".repeat(64), j.objectNode());
        origin.updateCheckpointStage(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED); em.flush();
        mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/complete", origin.getId())
                .header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", origin.getLeaseToken().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk()); em.flush();
        assertThat(predecessor.getAutomaticReviewHoldReason().name()).isEqualTo("HUMAN_CURRENT_SETTING_PROTECTED");
        assertThat(dependent.getAutomaticReviewHoldReason().name()).isEqualTo("DEPENDENCY_CONFIRMATION_REQUIRED");
        assertThat(predecessor.isPendingReview()).isTrue(); assertThat(dependent.isPendingReview()).isTrue();
        assertThat(character.getCurrentAge()).isEqualTo(77); assertThat(character.getCurrentLevel()).isEqualTo(3);
        assertThat(independent.getReviewStatus().name()).isEqualTo("CONFIRMED");
        assertThat(sql.queryForObject("select count(*) from character_facts where extracted_by_job_id=?", Integer.class, origin.getId())).isEqualTo(1);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"source", "absentDestination"})
    void bookAutomaticRootMoveProtectsManualReadWritePathsAndAllowsIndependentProperty(String protectedPath) throws Exception {
        var episode = episodeOrderedOrigin(1); var original = comparableWorld(episode, episode.getEpisode(), null, "북부");
        completeFixtureOrigin(episode); confirmWorld(original); var actual = original.getTargetWorldSetting();
        if (protectedPath.equals("source")) {
            mvc.perform(patch("/api/v1/works/{workId}/world-settings/{id}/properties", work.getId(), actual.getId()).header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("currentSettingName", "서식지", "settingName", "서식지",
                            "settingValue", "북부", "version", actual.getVersion())))).andExpect(status().isOk());
            assertThat(actual.isManuallyEdited(null, "서식지")).isTrue();
        } else {
            actual.applyProperty("거주집단", "서식지", "작가가 보관한 경로"); em.flush();
            mvc.perform(patch("/api/v1/works/{workId}/world-settings/{id}/properties", work.getId(), actual.getId()).header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("currentScopeName", "거주집단", "currentSettingName", "서식지",
                            "scopeName", "거주집단", "settingName", "보호이전명", "settingValue", "작가가 보관한 경로", "version", actual.getVersion()))))
                    .andExpect(status().isOk());
            assertThat(actual.getPropertyValue("거주집단", "서식지")).isNull();
            assertThat(actual.isManuallyEdited("거주집단", "서식지")).isTrue();
            assertThat(actual.isManuallyEdited(null, "서식지")).isFalse();
        }
        var book = uploadReference("엘프 거주집단의 통치자는 왕이다. 엘프의 수명은 500년이다.", "EPISODE", 3);
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var mover = comparableWorld(origin, null, actual, "통치자", "왕"); attachAutomaticWorld(mover, actual, WorldSettingSuggestedOperation.ADD);
        var decision = mover.getComparisonDecision(); var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        org.springframework.test.util.ReflectionTestUtils.setField(mover, "proposedScopeName", "거주집단");
        org.springframework.test.util.ReflectionTestUtils.setField(decision, "proposedScopeName", "거주집단");
        org.springframework.test.util.ReflectionTestUtils.setField(decision, "existingRootPropertyMoveSnapshotsJson",
                j.arrayNode().add(j.objectNode().put("settingName", "서식지").put("beforeValue", "북부")));
        var independent = comparableWorld(origin, null, actual, "수명", "500년"); attachAutomaticWorld(independent, actual, WorldSettingSuggestedOperation.ADD);
        completeFixtureOrigin(origin); worldCandidates.applyAutomatically(origin); em.flush();
        assertThat(actual.getPropertyValue("서식지")).isEqualTo("북부");
        assertThat(actual.getPropertyValue("거주집단", "서식지")).isNull(); assertThat(actual.getPropertyValue("거주집단", "통치자")).isNull();
        assertThat(mover.isPendingReview()).isTrue(); assertThat(mover.getAutomaticReviewHoldReason().name()).isEqualTo("HUMAN_CURRENT_SETTING_PROTECTED");
        assertThat(decision.getRootPropertyMovesAppliedWorldSettingVersion()).isNull();
        assertThat(actual.getPropertyValue("수명")).isEqualTo("500년"); assertThat(independent.getReviewStatus().name()).isEqualTo("CONFIRMED");
        assertThat(actual.isManuallyEdited(protectedPath.equals("source") ? null : "거주집단", "서식지")).isTrue();
    }

    void ensureStatusSchema(String key, String name) {
        var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        em.persist(org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema.create(work, key, null, name,
                CharacterFactType.STATUS, org.monitoring.catchholebackend.domain.character.type.SettingValueType.JSON,
                org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics.BASE_VALUE,
                org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy.UPSERT_BY_NAME, j.arrayNode(),
                org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource.SYSTEM_SEED, true)); em.flush();
    }

    @ParameterizedTest @CsvSource({"WORK_START,", "EPISODE,3", "UNSPECIFIED,"})
    void bookAutomaticLegacyNullOriginsHoldConflictsButAllowTrueDuplicatesAndIndependentSlots(String kind, Integer number) throws Exception {
        ensureAgeSchema(); ensureLevelSchema();
        var character = WorkCharacter.create(work, "하린", null, 18, null, null, null, null, null, null, null); em.persist(character);
        var actual = WorldSetting.create(work, WorldSettingCategory.RACE, "엘프", "서식지", "북부"); em.persist(actual); em.flush();
        assertThat(character.getManuallyEditedSlots()).isEmpty(); assertThat(actual.getManuallyEditedPaths()).isEmpty();
        assertThat(sql.queryForObject("select count(*) from character_facts where character_id=?", Integer.class, character.getId())).isZero();
        assertThat(sql.queryForObject("select count(*) from world_setting_candidates where target_world_setting_id=?", Integer.class, actual.getId())).isZero();
        var book = uploadReference("하린은 21살이다. 하린은 18살이다. 하린의 레벨은 3이다. 엘프의 서식지는 남부다. 엘프의 서식지는 북부다. 엘프의 수명은 500년이다.", kind, number);
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var conflict = ageCandidate(origin, null, 21); em.persist(conflict); attachAutomaticContext(conflict, character, CharacterFactOperation.UPDATE);
        var duplicate = ageCandidate(origin, null, 18); em.persist(duplicate); attachAutomaticContext(duplicate, character, CharacterFactOperation.EXCLUDE);
        var independent = levelCandidate(origin, character, 3); em.persist(independent);
        org.springframework.test.util.ReflectionTestUtils.setField(independent, "evidenceSpans", controlledEvidence(origin, "하린의 레벨은 3이다.", 10));
        attachAutomaticContext(independent, character, CharacterFactOperation.ADD);
        var worldConflict = comparableWorld(origin, null, actual, "남부"); attachAutomaticWorld(worldConflict, actual);
        var worldDuplicate = comparableWorld(origin, null, actual, "북부"); attachAutomaticWorld(worldDuplicate, actual, WorldSettingSuggestedOperation.EXCLUDE);
        var worldIndependent = comparableWorld(origin, null, actual, "수명", "500년"); attachAutomaticWorld(worldIndependent, actual, WorldSettingSuggestedOperation.ADD);
        completeFixtureOrigin(origin); characterCandidates.applyAutomatically(origin); worldCandidates.applyAutomatically(origin); em.flush();
        assertThat(character.getCurrentAge()).isEqualTo(18); assertThat(actual.getPropertyValue("서식지")).isEqualTo("북부");
        assertThat(conflict.isPendingReview()).isTrue(); assertThat(worldConflict.isPendingReview()).isTrue();
        assertThat(conflict.getAutomaticReviewHoldReason().name()).isEqualTo("SETTING_TIME_CONFIRMATION_REQUIRED");
        assertThat(worldConflict.getAutomaticReviewHoldReason().name()).isEqualTo("SETTING_TIME_CONFIRMATION_REQUIRED");
        assertThat(duplicate.getReviewStatus().name()).isEqualTo("DISMISSED"); assertThat(worldDuplicate.getReviewStatus().name()).isEqualTo("DISMISSED");
        assertThat(character.getCurrentLevel()).isEqualTo(3); assertThat(actual.getPropertyValue("수명")).isEqualTo("500년");
        assertThat(independent.getReviewStatus().name()).isEqualTo("CONFIRMED"); assertThat(worldIndependent.getReviewStatus().name()).isEqualTo("CONFIRMED");
        assertThat(character.getManuallyEditedSlots()).isEmpty(); assertThat(actual.getManuallyEditedPaths()).isEmpty();
        assertThat(sql.queryForObject("select count(*) from character_facts where extracted_by_job_id=?", Integer.class, origin.getId())).isEqualTo(1);
    }

    @ParameterizedTest @CsvSource({
            "EP1,EP4,false", "EP4,EP1,false",
            "BOOK_START,BOOK4,false", "BOOK4,BOOK_START,false",
            "EP1,EP3,false", "EP3,EP1,false",
            "BOOK_START,BOOK3,false", "BOOK3,BOOK_START,false",
            "EP1,BOOK_UNKNOWN,true", "BOOK_UNKNOWN,EP1,true",
            "BOOK_START,EP_UNKNOWN,false", "EP_UNKNOWN,BOOK_START,false",
            "EP1,EP2,true", "EP2,EP1,true",
            "BOOK_START,BOOK2,true", "BOOK2,BOOK_START,true",
            "EP1,BOOK2,true", "BOOK2,EP1,true"})
    @org.junit.jupiter.api.DisplayName("세계관 BOOK 자동 변경은 MERGE CURRENT 전체 출처의 시점을 순서와 무관하게 검사한다")
    void bookWorldAutomaticChecksEveryMergedCurrentSourceTime(String firstTime, String secondTime, boolean advance) throws Exception {
        assertBookWorldMultipleCurrentTimes(firstTime, secondTime, advance, false);
    }

    @ParameterizedTest @CsvSource({
            "EP1,EP4,false", "EP4,EP1,false",
            "EP1,EP3,false", "EP3,EP1,false",
            "EP1,BOOK_UNKNOWN,false", "BOOK_UNKNOWN,EP1,false",
            "EP1,BOOK2,true", "BOOK2,EP1,true"})
    @org.junit.jupiter.api.DisplayName("세계관 BOOK root 부수 이동도 전체 CURRENT 시점을 지키고 독립 속성과 진짜 중복은 유지한다")
    void bookWorldAutomaticCollateralMoveChecksEveryMergedCurrentSourceTime(String firstTime, String secondTime, boolean advance) throws Exception {
        assertBookWorldMultipleCurrentTimes(firstTime, secondTime, advance, true);
    }

    void assertBookWorldMultipleCurrentTimes(String firstTime, String secondTime, boolean advance, boolean move) throws Exception {
        var actual = WorldSetting.create(work, WorldSettingCategory.RACE, "엘프", "서식지", "북부");
        actual.addProperty("공용어", "고대어"); em.persist(actual); em.flush();
        // Arrange persisted imported/legacy MERGE history, including an unknown or later origin.
        // Its two applied sources are real rows; the incoming automatic guard is never mocked.
        var firstOrigin = worldCurrentTimeOrigin(firstTime);
        var secondOrigin = worldCurrentTimeOrigin(secondTime);
        var first = comparableWorld(firstOrigin, firstOrigin.getEpisode(), actual, "북부");
        if (firstTime.equals("EP_UNKNOWN")) first.recordTemporalScope(CharacterFactTemporalScope.UNKNOWN, null);
        if (firstOrigin.isOrderedProvisional()) completeFixtureOrigin(firstOrigin);
        first.confirm(org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation.ADD,
                WorldSettingCategory.RACE, "엘프", "서식지", "북부", null, work.getMember(), actual); em.flush();
        var second = comparableWorld(secondOrigin, secondOrigin.getEpisode(), actual, "북부와 남부");
        if (secondTime.equals("EP_UNKNOWN")) second.recordTemporalScope(CharacterFactTemporalScope.UNKNOWN, null);
        if (secondOrigin.isOrderedProvisional()) completeFixtureOrigin(secondOrigin);
        actual.updateProperty("서식지", "서식지", "북부와 남부");
        second.confirm(org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation.MERGE,
                WorldSettingCategory.RACE, "엘프", "서식지", "북부와 남부", null, work.getMember(), actual); em.flush();
        UUID seededTargetId = actual.getId(); UUID firstId = first.getId(); UUID secondId = second.getId();
        em.clear(); actual = em.find(WorldSetting.class, seededTargetId);
        first = em.find(WorldSettingCandidate.class, firstId); second = em.find(WorldSettingCandidate.class, secondId);
        var historyBefore = worldCandidateRepository.findAllByTargetWorldSettingIdAndReviewStatusOrderByReviewedAtDescCreatedAtDescIdDesc(
                actual.getId(), org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.CONFIRMED);
        assertThat(org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingCurrentSources.forProperty(
                historyBefore, null, "서식지", "북부와 남부")).containsExactly(first, second);
        var originalProofs = Map.of(first.getId(), worldCurrentHistoryProof(first), second.getId(), worldCurrentHistoryProof(second));
        UUID actualId = actual.getId(); long initialVersion = actual.getVersion();
        var book = uploadReference("엘프의 서식지는 동부다. 공용어는 고대어다. 수명은 500년이다. 거주집단의 통치자는 왕이다.", "EPISODE", 3);
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var change = comparableWorld(origin, null, actual, move ? "통치자" : "서식지", move ? "왕" : "동부");
        if (move) attachAutomaticRootMove(change, actual);
        else attachAutomaticWorld(change, actual, WorldSettingSuggestedOperation.UPDATE);
        // A held conflicting value must not prevent a genuine duplicate of that same CURRENT.
        // In positive cases the duplicate uses the unchanged property, not the property we update/move.
        var duplicate = comparableWorld(origin, null, actual, advance ? "공용어" : "서식지", advance ? "고대어" : "북부와 남부");
        attachAutomaticWorld(duplicate, actual, WorldSettingSuggestedOperation.EXCLUDE);
        var independent = comparableWorld(origin, null, actual, "수명", "500년");
        attachAutomaticWorld(independent, actual, WorldSettingSuggestedOperation.ADD);
        completeFixtureOrigin(origin);
        var path = org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingAnalysisStateMapper.pathKey(null, "서식지");
        JsonNode provenance = states.getInputState(origin).path("worldSettings").path("world:"+actualId).path("provenanceByPath").path(path);
        assertThat(provenance.path("sourceCandidateIds")).extracting(JsonNode::asText)
                .containsExactly(first.getId().toString(), second.getId().toString());
        assertThat(provenance.path("currentSourceTimes")).hasSize(2);
        assertWorldCurrentTime(provenance.path("currentSourceTimes").get(0), firstTime);
        assertWorldCurrentTime(provenance.path("currentSourceTimes").get(1), secondTime);
        worldCandidates.applyAutomatically(origin); em.flush(); em.clear();
        var reloaded = em.find(WorldSetting.class, actualId);
        var history = worldCandidateRepository.findAllByTargetWorldSettingIdAndReviewStatusOrderByReviewedAtDescCreatedAtDescIdDesc(
                actualId, org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.CONFIRMED);
        for (var row : history) if (originalProofs.containsKey(row.getId())) {
            assertThat(worldCurrentHistoryProof(row)).isEqualTo(originalProofs.get(row.getId()));
        }
        assertThat(history).extracting(WorldSettingCandidate::getId).contains(first.getId(), second.getId());
        var savedChange = em.find(WorldSettingCandidate.class, change.getId());
        if (advance) {
            assertThat(savedChange.getReviewStatus().name()).isEqualTo("CONFIRMED");
            assertThat(savedChange.getAutomaticReviewHoldReason()).isNull();
            assertThat(savedChange.isHistoryOnly()).isFalse();
            assertThat(history).hasSize(4);
        } else {
            assertThat(savedChange.isPendingReview()).isTrue();
            assertThat(savedChange.getAutomaticReviewHoldReason().name()).isEqualTo("SETTING_TIME_CONFIRMATION_REQUIRED");
            assertThat(savedChange.getFinalOperation()).isNull();
            assertThat(history).hasSize(3);
        }
        String currentScope = move && advance ? "거주집단" : null;
        String currentValue = !move && advance ? "동부" : "북부와 남부";
        assertThat(reloaded.getPropertyValue(currentScope, "서식지")).isEqualTo(currentValue);
        if (move) {
            assertThat(reloaded.getPropertyValue("거주집단", "통치자")).isEqualTo(advance ? "왕" : null);
            assertThat(reloaded.hasProperty(null, "서식지")).isEqualTo(!advance);
            assertThat(savedChange.getComparisonDecision().getRootPropertyMovesAppliedWorldSettingVersion())
                    .isEqualTo(advance ? reloaded.getVersion() : null);
        }
        var currentSources = org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingCurrentSources.forProperty(
                history, currentScope, "서식지", currentValue);
        assertThat(currentSources).as("reloaded canonical %s and applied history %s", reloaded.getPropertiesJson(),
                history.stream().map(this::worldCurrentHistoryProof).toList()).extracting(WorldSettingCandidate::getId).containsExactlyElementsOf(!move && advance
                ? java.util.List.of(change.getId()) : java.util.List.of(first.getId(), second.getId()));
        assertThat(em.find(WorldSettingCandidate.class, duplicate.getId()).getReviewStatus().name()).isEqualTo("DISMISSED");
        assertThat(em.find(WorldSettingCandidate.class, independent.getId()).getReviewStatus().name()).isEqualTo("CONFIRMED");
        assertThat(reloaded.getPropertyValue("공용어")).isEqualTo("고대어");
        assertThat(reloaded.getPropertyValue("수명")).isEqualTo("500년");
        assertThat(reloaded.getManuallyEditedPaths()).isEmpty();
        assertThat(reloaded.getVersion()).isEqualTo(initialVersion + 1);
        assertThat(sql.queryForObject("select count(*) from world_setting_candidates where analysis_job_id=? and review_status='CONFIRMED'",
                Integer.class, origin.getId())).isEqualTo(advance ? 2 : 1);
    }

    AnalysisJob worldCurrentTimeOrigin(String time) throws Exception {
        if (time.startsWith("EP")) return completedManualEpisodeOrigin(time.equals("EP_UNKNOWN") ? 2 : Integer.parseInt(time.substring(2)));
        String kind = time.equals("BOOK_START") ? "WORK_START" : time.equals("BOOK_UNKNOWN") ? "UNSPECIFIED" : "EPISODE";
        Integer number = kind.equals("EPISODE") ? Integer.valueOf(time.substring(4)) : null;
        var book = uploadReference("엘프의 서식지는 북부와 남부다.", kind, number);
        return jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
    }

    void assertWorldCurrentTime(JsonNode time, String expected) {
        assertThat(time.path("temporalScope").asText()).isEqualTo(expected.equals("EP_UNKNOWN") ? "UNKNOWN" : "PRESENT");
        if (expected.startsWith("EP")) {
            assertThat(time.path("sourceKind").asText()).isEqualTo("EPISODE");
            assertThat(time.path("sourceEpisodeNo").asInt()).isEqualTo(expected.equals("EP_UNKNOWN") ? 2 : Integer.parseInt(expected.substring(2)));
            assertThat(time.path("referenceTimeKind").isNull()).isTrue();
        } else {
            assertThat(time.path("sourceKind").asText()).isEqualTo("SETTING_BOOK");
            assertThat(time.path("sourceEpisodeNo").isNull()).isTrue();
            assertThat(time.path("referenceTimeKind").asText()).isEqualTo(expected.equals("BOOK_START") ? "WORK_START" : expected.equals("BOOK_UNKNOWN") ? "UNSPECIFIED" : "EPISODE");
            if (expected.equals("BOOK_START") || expected.equals("BOOK_UNKNOWN")) assertThat(time.path("referenceEpisodeNo").isNull()).isTrue();
            else assertThat(time.path("referenceEpisodeNo").asInt()).isEqualTo(Integer.parseInt(expected.substring(4)));
        }
    }

    JsonNode worldCurrentHistoryProof(WorldSettingCandidate row) {
        var node = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        node.put("id", row.getId().toString()); node.put("analysisJobId", row.getAnalysisJob().getId().toString());
        node.put("targetId", row.getTargetWorldSetting().getId().toString()); node.put("finalOperation", row.getFinalOperation().name());
        node.put("finalScopeName", row.getFinalScopeName()); node.put("finalSettingName", row.getFinalSettingName()); node.put("finalValue", row.getFinalValue());
        node.put("reviewedAt", row.getReviewedAt().toString()); node.put("appliedVersion", row.getAppliedWorldSettingVersion());
        node.put("historyOnly", row.isHistoryOnly()); node.set("evidenceSpans", row.getEvidenceSpans().deepCopy());
        if (row.getComparisonDecision() != null) {
            node.put("decisionScope", row.getComparisonDecision().getProposedScopeName());
            node.put("decisionBaseVersion", row.getComparisonDecision().getBaseWorldSettingVersion());
            node.put("rootMovesAppliedVersion", row.getComparisonDecision().getRootPropertyMovesAppliedWorldSettingVersion());
            node.set("rootMoveSnapshots", json.valueToTree(row.getComparisonDecision().getExistingRootPropertyMoveSnapshots()));
        }
        org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisSourceMetadata.put(node, row.getAnalysisJob());
        node.set("sourceTime", org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.toJson(
                org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.from(row.getAnalysisJob(), row.getEffectiveFromEpisodeNo(), row.getTemporalScope())));
        return node;
    }

    void attachAutomaticRootMove(WorldSettingCandidate candidate, WorldSetting actual) {
        var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var batch = WorldSettingComparisonBatch.createOrdered(work, null, candidate.getAnalysisJob(), candidate.getCategory(), null,
                WorldSettingSubjectResolutionType.EXISTING, "world:"+actual.getId(), actual.getSubjectName(),
                j.arrayNode().add(actual.getId().toString()), j.arrayNode(), 1); em.persist(batch);
        // Immutable decision fields must be supplied at creation, before their initial SQL INSERT.
        var decision = WorldSettingComparisonDecision.create(batch, "D1", actual.getSubjectName(), actual, null, null,
                WorldSettingConsolidationStatus.SINGLE, WorldSettingSuggestedOperation.ADD, null, "거주집단", "통치자", null, "왕",
                "원래 CURRENT의 값과 전체 출처는 유지하고 root 위치만 이동", java.util.List.of(
                        new WorldSettingComparisonDecision.ExistingRootPropertyMoveSnapshot("서식지", "북부와 남부")), j.objectNode());
        decision.bindOrderedTarget(null, actual.getVersion()); em.persist(decision);
        org.springframework.test.util.ReflectionTestUtils.setField(candidate, "comparisonDecision", decision);
        org.springframework.test.util.ReflectionTestUtils.setField(candidate, "proposedScopeName", "거주집단");
        org.springframework.test.util.ReflectionTestUtils.setField(candidate, "suggestedOperation", WorldSettingSuggestedOperation.ADD);
        em.persist(WorldSettingComparisonDecisionSource.create(batch, decision, candidate, "C1", 0)); em.flush();
    }

    @ParameterizedTest @CsvSource({"1,false", "2,false", "3,true"})
    void bookTimePreventsEarlierAndSameEpisodeCurrentRollback(int number, boolean advance) throws Exception {
        ensureAgeSchema();
        var book = uploadReference("하린은 18살이다. 엘프는 북부에 산다.", "EPISODE", 2);
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var age = ageCandidate(origin, null, 18); em.persist(age);
        var world = comparableWorld(origin, null, null, "북부");
        completeFixtureOrigin(origin); confirmAgeGroup(age); confirmWorld(world);
        var current = em.find(WorkCharacter.class, age.getMatchedCharacterId());
        var episode = episodeOrderedOrigin(number);
        var next = ageCandidate(episode, episode.getEpisode(), 19); em.persist(next);
        var nextWorld = comparableWorld(episode, episode.getEpisode(), world.getTargetWorldSetting(), "남부");
        completeFixtureOrigin(episode); confirmAgeGroup(next); confirmWorld(nextWorld);
        assertThat(current.getCurrentAge()).isEqualTo(advance ? 19 : 18);
        assertThat(world.getTargetWorldSetting().getPropertyValue("서식지")).isEqualTo(advance ? "남부" : "북부");
        assertThat(next.getConfirmedApplicationMode().name()).isEqualTo(advance ? "APPLY_PROPOSAL" : "HISTORY_ONLY");
        assertThat(nextWorld.isHistoryOnly()).isEqualTo(!advance);
    }

    @ParameterizedTest @CsvSource({"WORK_START,,false", "EPISODE,1,false", "EPISODE,2,false", "EPISODE,3,true", "UNSPECIFIED,,false"})
    void episodeTimePreventsEarlierSameAndUnknownBookCurrentRollback(String kind, Integer number, boolean advance) throws Exception {
        ensureAgeSchema();
        String originalContent = "하린은 18살이다. 엘프의 서식지는 북부다.";
        var episode = episodeOrderedOrigin(2, originalContent);
        var age = ageCandidate(episode, episode.getEpisode(), 18); em.persist(age);
        org.springframework.test.util.ReflectionTestUtils.setField(age, "evidenceSpans", json.createArrayNode().add(identitySpan(originalContent, "하린은 18살이다.")));
        var world = comparableWorld(episode, episode.getEpisode(), null, "북부");
        completeFixtureOrigin(episode); confirmAgeGroup(age); confirmWorld(world);
        var character = em.find(WorkCharacter.class, age.getMatchedCharacterId());
        var book = uploadReference("ハリンは19歳。エルフは南部。", kind, number);
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var next = ageCandidate(origin, null, 19); em.persist(next);
        var nextWorld = comparableWorld(origin, null, world.getTargetWorldSetting(), "남부");
        completeFixtureOrigin(origin); confirmAgeGroup(next); confirmWorld(nextWorld);
        assertThat(character.getCurrentAge()).isEqualTo(advance ? 19 : 18);
        assertThat(world.getTargetWorldSetting().getPropertyValue("서식지")).isEqualTo(advance ? "남부" : "북부");
        assertThat(next.getConfirmedApplicationMode().name()).isEqualTo(advance ? "APPLY_PROPOSAL" : "HISTORY_ONLY");
        assertThat(nextWorld.isHistoryOnly()).isEqualTo(!advance);
    }

    @Test @org.junit.jupiter.api.DisplayName("설정집 세계관 후보의 미래 효력 시점은 실제 게시 응답과 DB에 보존된다")
    void bookWorldPublishPreservesBodyTemporalMetadata() throws Exception {
        var book = uploadReference("8화 이후 엘프의 서식지는 동부로 바뀔 계획이다.", "WORK_START", null);
        JsonNode claim = claimBook();
        UUID id = UUID.fromString(claim.path("analysisJobId").asText());
        jobs.findById(id).orElseThrow().updateCheckpointStage(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED);
        JsonNode published = response(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/internal/v1/analysis-jobs/{id}/world-setting-candidates", id)
                .header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", claim.path("leaseToken").asText())
                .contentType(MediaType.APPLICATION_JSON).content("{\"candidates\":[{\"category\":\"RACE\",\"subjectName\":\"엘프\",\"settingName\":\"서식지\",\"extractedValue\":\"동부\",\"extractionConfidence\":1,\"temporalScope\":\"HYPOTHETICAL\",\"effectiveFromEpisodeNo\":8,\"evidenceSpans\":[{\"quote\":\"8화 이후 엘프의 서식지는 동부로 바뀔 계획이다.\",\"startOffset\":0,\"endOffset\":28}]}]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(published.get(0).path("temporalScope").asText()).isEqualTo("HYPOTHETICAL");
        assertThat(published.get(0).path("effectiveFromEpisodeNo").asInt()).isEqualTo(8);
        assertThat(sql.queryForObject("select effective_from_episode_no from world_setting_candidates where analysis_job_id=?", Integer.class, id)).isEqualTo(8);
        assertThat(sql.queryForObject("select temporal_scope from world_setting_candidates where analysis_job_id=?", String.class, id)).isEqualTo("HYPOTHETICAL");
    }

    @Test @org.junit.jupiter.api.DisplayName("강제로 구체적 비교 결정을 보내도 수동 현재값이 누적 문맥에 투영되지 않는다")
    void forcedCharacterDecisionCannotPolluteProtectedProjectedCurrent() throws Exception {
        ensureAgeSchema(); WorkCharacter character = manualCharacter(77);
        var book = uploadReference("하린은 21살이다.", "EPISODE", 3); JsonNode claimed = claimBook();
        var origin = jobs.findById(UUID.fromString(claimed.path("analysisJobId").asText())).orElseThrow();
        var candidate = ageCandidate(origin, null, 21); em.persist(candidate); attachAutomaticContext(candidate, character, CharacterFactOperation.UPDATE);
        var slot = snapshotAccessor.entry(CharacterFactType.AGE, "age", "21", candidate.getProposedValueJson());
        characterStates.recordDecision(origin, candidate, slot, java.util.List.of(), java.util.List.of());
        JsonNode state = states.getProjectedState(origin).path("characters").path("character:"+character.getId());
        assertThat(state.path("slots").path("AGE:age").path("factValue").asText()).isEqualTo("77");
        assertThat(candidate.getAutomaticReviewHoldReason()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.HUMAN_CURRENT_SETTING_PROTECTED);
        assertThat(states.getProjectedState(origin).path("references")).anySatisfy(ref -> assertThat(ref.path("candidateId").asText()).isEqualTo(candidate.getId().toString()));
    }

    @Test @org.junit.jupiter.api.DisplayName("세계관 Worker 실제 완료 요청도 작가 현재값을 누적 문맥에서 보호한다")
    void forcedWorldHttpDecisionCannotPolluteProtectedProjectedCurrent() throws Exception {
        WorldSetting actual = manualWorld(); var book = uploadReference("엘프의 서식지는 동부다.", "EPISODE", 3);
        JsonNode claimed = claimBook(); UUID jobId = UUID.fromString(claimed.path("analysisJobId").asText()); UUID lease = UUID.fromString(claimed.path("leaseToken").asText());
        var origin = jobs.findById(jobId).orElseThrow(); origin.updateCheckpointStage(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED);
        var published = worldWorker.publishWorldSettingCandidates(jobId, lease, new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest(java.util.List.of(
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.Candidate(WorldSettingCategory.RACE, "엘프", null, "서식지", "동부",
                        java.util.List.of(new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.EvidenceSpan("엘프의 서식지는 동부다.", 0, 13)), java.math.BigDecimal.ONE, Map.of("epistemic_status", "ASSERTED"),
                        CharacterFactTemporalScope.PRESENT, null))));
        worldWorker.resolveWorldSettingSubjects(jobId, lease, new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest(java.util.List.of(
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput(published.getFirst().candidateId(), java.util.List.of(actual.getId())))));
        var batch = worldWorker.claimNextWorldSettingComparisonBatch(jobId, lease).orElseThrow();
        var context = worldWorker.getWorldSettingComparisonBatchContext(jobId, batch.comparisonBatchId(), lease,
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchContextRequest(batch.resolvedTargetWorldSettingIds(), batch.resolvedProvisionalSubjectKeys()));
        Map<String,Object> decision = new java.util.LinkedHashMap<>(); decision.put("decisionRef", "D1"); decision.put("sourceCandidateRefs", java.util.List.of("C1"));
        decision.put("canonicalSubjectName", "엘프"); decision.put("targetWorldSettingId", actual.getId()); decision.put("matchedPropertyName", "서식지");
        decision.put("consolidationStatus", "SINGLE"); decision.put("suggestedOperation", "UPDATE"); decision.put("proposedSettingName", "서식지"); decision.put("proposedValue", "동부"); decision.put("comparisonReason", "강제 변경 결정");
        mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/world-setting-comparison-batches/{batch}/complete", jobId, batch.comparisonBatchId())
                .header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("contextToken", context.contextToken(), "contextVersions", java.util.List.of(Map.of("worldSettingId", actual.getId(), "version", actual.getVersion())), "decisions", java.util.List.of(decision)))))
                .andExpect(status().isOk());
        JsonNode projected = states.getProjectedState(origin).path("worldSettings").path("world:"+actual.getId());
        assertThat(projected.path("propertiesJson").path("서식지").asText()).isEqualTo("작가가 정한 지역");
        assertThat(worldCandidateRepository.findById(published.getFirst().candidateId()).orElseThrow().getAutomaticReviewHoldReason())
                .isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.HUMAN_CURRENT_SETTING_PROTECTED);
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(CharacterFactTemporalScope.class)
    @org.junit.jupiter.api.DisplayName("현재와 같은 값이어도 과거·계획·불명확 BOOK 주장은 자동 제외로 사라지지 않는다")
    void nonCurrentBookCannotDisappearAsAutomaticExclude(CharacterFactTemporalScope temporal) throws Exception {
        ensureAgeSchema(); var character = manualCharacter(77); var actual = manualWorld();
        var book = uploadReference("4화 이후 하린은 77세가 될 예정이며 엘프는 작가가 정한 지역에 산다.", "EPISODE", 3);
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var age = ageCandidate(origin, null, 77); em.persist(age); attachAutomaticContext(age, character, CharacterFactOperation.EXCLUDE);
        org.springframework.test.util.ReflectionTestUtils.setField(age, "temporalScope", temporal); age.recordEffectiveFromEpisodeNo(4);
        var row = comparableWorld(origin, null, actual, actual.getPropertyValue("서식지")); row.recordTemporalScope(temporal, 4);
        attachAutomaticWorld(row, actual, org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation.EXCLUDE);
        completeFixtureOrigin(origin); characterCandidates.applyAutomatically(origin); worldCandidates.applyAutomatically(origin); em.flush();
        assertThat(age.isPendingReview()).isTrue(); assertThat(row.isPendingReview()).isTrue();
        assertThat(age.getAutomaticReviewHoldReason()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.SETTING_TIME_CONFIRMATION_REQUIRED);
        assertThat(row.getAutomaticReviewHoldReason()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.SETTING_TIME_CONFIRMATION_REQUIRED);
        assertThat(character.getCurrentAge()).isEqualTo(77); assertThat(actual.getPropertyValue("서식지")).isEqualTo("작가가 정한 지역");
    }

    @Test @org.junit.jupiter.api.DisplayName("PRESENT 표기라도 기준보다 미래인 효력은 현재반영 체크만으로 승인되지 않는다")
    void bodyFutureCannotBePromotedByCurrentOverrideFlagAlone() throws Exception {
        ensureAgeSchema(); var character = manualCharacter(77); var actual = manualWorld(); var book = uploadReference("8화 이후 하린은 21살이며 엘프는 동부로 이주한다.", "WORK_START", null);
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var age = ageCandidate(origin, null, 21); em.persist(age); age.recordEffectiveFromEpisodeNo(8);
        var row = comparableWorld(origin, null, actual, "동부"); row.recordTemporalScope(CharacterFactTemporalScope.PRESENT, 8);
        completeFixtureOrigin(origin); em.flush();
        mvc.perform(post("/api/v1/works/{workId}/setting-candidates/{id}/confirm", work.getId(), age.getId()).header("Authorization", bearer)
                .param("sourceAnalysisJobId", origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("applicationMode", "APPLY_PROPOSAL", "applyToCurrent", true,
                        "baseSnapshotVersion", character.getSnapshotVersion(), "expectedUpdatedAt", age.getUpdatedAt().toString())))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{id}/confirm", work.getId(), row.getId()).header("Authorization", bearer)
                .param("sourceAnalysisJobId", origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("operation", "UPDATE", "category", "RACE", "subjectName", "엘프", "settingName", "서식지", "value", "동부",
                        "applyToCurrent", true, "expectedWorldSettingVersion", actual.getVersion(), "expectedUpdatedAt", row.getUpdatedAt().toString())))).andExpect(status().isConflict());
        assertThat(character.getCurrentAge()).isEqualTo(77); assertThat(actual.getPropertyValue("서식지")).isEqualTo("작가가 정한 지역");
    }

    @Test void bookExplicitCurrentChoiceChangesOnlySelectedHumanCurrentPath() throws Exception {
        ensureAgeSchema();
        var character = manualCharacter(77); var world = manualWorld();
        var book = upload("하린은 21살이다. 엘프는 동부에 산다.");
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var age = ageCandidate(origin, null, 21); em.persist(age);
        var row = comparableWorld(origin, null, world, "동부"); completeFixtureOrigin(origin);
        mvc.perform(post("/api/v1/works/{workId}/setting-candidates/{id}/confirm", work.getId(), age.getId())
                .header("Authorization", bearer).param("sourceAnalysisJobId", origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("applicationMode", "APPLY_PROPOSAL", "applyToCurrent", true,
                        "baseSnapshotVersion", character.getSnapshotVersion(), "expectedUpdatedAt", age.getUpdatedAt().toString()))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{id}/confirm", work.getId(), row.getId())
                .header("Authorization", bearer).param("sourceAnalysisJobId", origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("operation", "UPDATE", "category", "RACE", "subjectName", "엘프", "settingName", "서식지", "value", "동부",
                        "applyToCurrent", true, "expectedWorldSettingVersion", world.getVersion(), "expectedUpdatedAt", row.getUpdatedAt().toString()))))
                .andExpect(status().isOk());
        assertThat(character.getCurrentAge()).isEqualTo(21);
        assertThat(world.getPropertyValue("서식지")).isEqualTo("동부");
        assertThat(age.getConfirmedApplicationMode().name()).isEqualTo("APPLY_PROPOSAL");
        assertThat(row.isHistoryOnly()).isFalse();
        assertThat(character.isManuallyEditedSlot(CharacterFactType.AGE, "age")).isTrue();
        assertThat(world.isManuallyEdited(null, "서식지")).isTrue();
        var next = jobs.findById(UUID.fromString(analyze(book.path("id").asText()).path("id").asText())).orElseThrow();
        var nextAge = ageCandidate(next, null, 22); em.persist(nextAge); attachAutomaticContext(nextAge, character, CharacterFactOperation.UPDATE);
        var nextWorld = comparableWorld(next, null, world, "서부"); attachAutomaticWorld(nextWorld, world); completeFixtureOrigin(next);
        characterCandidates.applyAutomatically(next); worldCandidates.applyAutomatically(next);
        assertThat(character.getCurrentAge()).isEqualTo(21); assertThat(world.getPropertyValue("서식지")).isEqualTo("동부");
        assertThat(nextAge.getAutomaticReviewHoldReason().name()).isEqualTo("HUMAN_CURRENT_SETTING_PROTECTED");
        assertThat(nextWorld.getAutomaticReviewHoldReason().name()).isEqualTo("HUMAN_CURRENT_SETTING_PROTECTED");
    }

    @Test void bookExplicitCurrentChoiceRejectsStaleActualVersionBeforeMutation() throws Exception {
        ensureAgeSchema(); var character = manualCharacter(77); var world = manualWorld();
        var book = upload("하린은 21살이다. 엘프는 동부에 산다.");
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var age = ageCandidate(origin, null, 21); em.persist(age);
        var row = comparableWorld(origin, null, world, "동부"); completeFixtureOrigin(origin);
        mvc.perform(post("/api/v1/works/{workId}/setting-candidates/{id}/confirm", work.getId(), age.getId())
                .header("Authorization", bearer).param("sourceAnalysisJobId", origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("applicationMode", "APPLY_PROPOSAL", "applyToCurrent", true,
                        "baseSnapshotVersion", character.getSnapshotVersion() - 1, "expectedUpdatedAt", age.getUpdatedAt().toString()))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{id}/confirm", work.getId(), row.getId())
                .header("Authorization", bearer).param("sourceAnalysisJobId", origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("operation", "UPDATE", "category", "RACE", "subjectName", "엘프", "settingName", "서식지", "value", "동부",
                        "applyToCurrent", true, "expectedWorldSettingVersion", world.getVersion() - 1, "expectedUpdatedAt", row.getUpdatedAt().toString()))))
                .andExpect(status().isConflict());
        assertThat(character.getCurrentAge()).isEqualTo(77); assertThat(world.getPropertyValue("서식지")).isEqualTo("작가가 정한 지역");
        assertThat(age.getReviewStatus().name()).isEqualTo("PENDING_REVIEW"); assertThat(row.getReviewStatus().name()).isEqualTo("PENDING_REVIEW");
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {
            "missingVersion", "missingTimestamp", "staleTimestamp", "provisional", "superseded", "incomplete"})
    void bookCurrentOverrideRequiresFreshActualTargetAndCompletedSource(String invalid) throws Exception {
        ensureAgeSchema(); var character = manualCharacter(77); var actual = manualWorld();
        var book = upload("하린은 21살이다. 엘프는 동부에 산다.");
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var age = ageCandidate(origin, null, 21); em.persist(age);
        var world = comparableWorld(origin, null, actual, "동부");
        if (invalid.equals("provisional")) {
            String characterKey = "provisional-character:"+UUID.randomUUID();
            org.springframework.test.util.ReflectionTestUtils.setField(age, "matchedCharacterId", null);
            org.springframework.test.util.ReflectionTestUtils.setField(age, "matchedCharacter", null);
            org.springframework.test.util.ReflectionTestUtils.setField(age, "provisionalSubjectKey", characterKey);
            org.springframework.test.util.ReflectionTestUtils.setField(age, "matchStatus",
                    org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED);
            String worldKey = "provisional-world:"+UUID.randomUUID();
            var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
            org.springframework.test.util.ReflectionTestUtils.setField(world, "targetWorldSetting", null);
            org.springframework.test.util.ReflectionTestUtils.setField(world, "comparisonStatus",
                    org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus.PENDING);
            world.resolveOrderedSubject(WorldSettingSubjectResolutionType.EXISTING, worldKey, "엘프",
                    j.arrayNode(), j.arrayNode().add(worldKey));
            world.startComparison();
            world.completeComparison(null, WorldSettingConsolidationStatus.SINGLE,
                    org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation.ADD,
                    null, "서식지", null, "동부", "실제 대상으로 승격되지 않은 임시 대상", j.objectNode(), java.time.LocalDateTime.now());
        }
        completeFixtureOrigin(origin);
        if (invalid.equals("superseded")) {
            var latest = analyze(book.path("id").asText());
            assertThat(latest.path("source").path("revisionId").asText())
                    .isEqualTo(origin.getSourceSettingBookRevision().getId().toString());
            assertThat(latest.path("id").asText()).isNotEqualTo(origin.getId().toString());
        }
        if (invalid.equals("incomplete")) {
            org.springframework.test.util.ReflectionTestUtils.setField(origin, "journalStatus",
                    org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus.INCOMPLETE);
            assertThat(origin.getStatus().name()).isEqualTo("SUCCEEDED");
        }
        em.flush();
        long characterVersion = character.getSnapshotVersion(), worldVersion = actual.getVersion();
        var ageRequest = new java.util.LinkedHashMap<String,Object>(Map.of("applicationMode", "APPLY_PROPOSAL", "applyToCurrent", true,
                "baseSnapshotVersion", characterVersion, "expectedUpdatedAt", age.getUpdatedAt().toString()));
        var worldRequest = new java.util.LinkedHashMap<String,Object>(Map.of("operation", "UPDATE", "category", "RACE", "subjectName", "엘프",
                "settingName", "서식지", "value", "동부", "applyToCurrent", true,
                "expectedWorldSettingVersion", worldVersion, "expectedUpdatedAt", world.getUpdatedAt().toString()));
        if (invalid.equals("missingVersion")) { ageRequest.remove("baseSnapshotVersion"); worldRequest.remove("expectedWorldSettingVersion"); }
        if (invalid.equals("missingTimestamp")) { ageRequest.remove("expectedUpdatedAt"); worldRequest.remove("expectedUpdatedAt"); }
        if (invalid.equals("staleTimestamp")) {
            ageRequest.put("expectedUpdatedAt", age.getUpdatedAt().minusSeconds(1).toString());
            worldRequest.put("expectedUpdatedAt", world.getUpdatedAt().minusSeconds(1).toString());
        }
        mvc.perform(post("/api/v1/works/{workId}/setting-candidates/{id}/confirm", work.getId(), age.getId())
                .header("Authorization", bearer).param("sourceAnalysisJobId", origin.getId().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(ageRequest)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{id}/confirm", work.getId(), world.getId())
                .header("Authorization", bearer).param("sourceAnalysisJobId", origin.getId().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(worldRequest)))
                .andExpect(status().isConflict());
        em.flush();
        assertThat(character.getCurrentAge()).isEqualTo(77); assertThat(actual.getPropertyValue("서식지")).isEqualTo("작가가 정한 지역");
        assertThat(character.getSnapshotVersion()).isEqualTo(characterVersion); assertThat(actual.getVersion()).isEqualTo(worldVersion);
        assertThat(age.isPendingReview()).isTrue(); assertThat(world.isPendingReview()).isTrue();
        assertThat(age.getConfirmedApplicationMode()).isNull(); assertThat(world.getFinalOperation()).isNull();
        assertThat(sql.queryForObject("select count(*) from character_facts where extracted_by_job_id=?", Integer.class, origin.getId())).isZero();
    }

    @ParameterizedTest @CsvSource({
            "NONE,APPLY_PROPOSAL,false,true,409,false",
            "NONE,APPLY_PROPOSAL,true,true,409,false",
            "APPLY_PROPOSAL,APPLY_PROPOSAL,false,true,409,false",
            "APPLY_PROPOSAL,APPLY_PROPOSAL,true,true,200,true",
            "APPLY_PROPOSAL,APPLY_PROPOSAL,true,false,200,false",
            "HISTORY_ONLY,HISTORY_ONLY,true,false,200,false",
            "HISTORY_ONLY,HISTORY_ONLY,true,true,409,false",
            "APPLY_PROPOSAL,HISTORY_ONLY,true,false,409,false",
            "HISTORY_ONLY,APPLY_PROPOSAL,true,false,409,false"})
    void bookCharacterExplicitChoiceRequiresSavedModeAndEditedValueAgreement(String savedMode, String requestedMode,
            boolean applyEditedValue, boolean applyToCurrent, int httpStatus, boolean changesCurrent) throws Exception {
        ensureAgeSchema(); var character = manualCharacter(77);
        var book = uploadReference("8화 이후 하린은 21살이 될 예정이다.", "WORK_START", null);
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var age = ageCandidate(origin, null, 21); em.persist(age);
        org.springframework.test.util.ReflectionTestUtils.setField(age, "temporalScope", CharacterFactTemporalScope.HYPOTHETICAL);
        age.recordEffectiveFromEpisodeNo(8); completeFixtureOrigin(origin); em.flush();
        if (!savedMode.equals("NONE")) {
            mvc.perform(patch("/api/v1/works/{workId}/setting-candidates/{id}", work.getId(), age.getId())
                    .header("Authorization", bearer).param("sourceAnalysisJobId", origin.getId().toString())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("attributeName", "age", "attributeValue", "21",
                            "reviewedApplicationMode", savedMode, "expectedUpdatedAt", age.getUpdatedAt().toString()))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewedApplicationMode").value(savedMode));
            // A deliberate unchanged-value selection is still a saved author decision; it is not a provider rewrite.
            assertThat(age.isUserModified()).isTrue();
        }
        long version = character.getSnapshotVersion(); var confirmedAt = age.getUpdatedAt();
        mvc.perform(post("/api/v1/works/{workId}/setting-candidates/{id}/confirm", work.getId(), age.getId())
                .header("Authorization", bearer).param("sourceAnalysisJobId", origin.getId().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("applicationMode", requestedMode,
                        "applyEditedValue", applyEditedValue, "applyToCurrent", applyToCurrent,
                        "baseSnapshotVersion", version, "expectedUpdatedAt", confirmedAt.toString()))))
                .andExpect(status().is(httpStatus));
        em.flush();
        assertThat(character.getCurrentAge()).isEqualTo(changesCurrent ? 21 : 77);
        assertThat(character.isManuallyEditedSlot(CharacterFactType.AGE, "age")).isTrue();
        assertThat(age.isPendingReview()).isEqualTo(httpStatus == 409);
        assertThat(sql.queryForObject("select count(*) from character_facts where extracted_by_job_id=?", Integer.class, origin.getId()))
                .isEqualTo(httpStatus == 200 ? 1 : 0);
        if (httpStatus == 409) {
            assertThat(character.getSnapshotVersion()).isEqualTo(version);
            assertThat(age.getConfirmedApplicationMode()).isNull(); assertThat(age.getUpdatedAt()).isEqualTo(confirmedAt);
        } else {
            assertThat(age.getConfirmedApplicationMode().name()).isEqualTo(changesCurrent ? "APPLY_PROPOSAL" : "HISTORY_ONLY");
        }
    }

    @Test void rawFutureWorldCannotBePromotedByUnsavedEditedConfirm() throws Exception {
        var actual = manualWorld(); var book = uploadReference("8화 이후 엘프는 동부로 이주할 예정이다.", "WORK_START", null);
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var row = comparableWorld(origin, null, actual, "동부"); row.recordTemporalScope(CharacterFactTemporalScope.HYPOTHETICAL, 8);
        completeFixtureOrigin(origin); em.flush();
        mvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{id}/confirm", work.getId(), row.getId()).header("Authorization", bearer)
                .param("sourceAnalysisJobId", origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("operation", "UPDATE", "category", "RACE", "subjectName", "엘프", "settingName", "서식지", "value", "직접확정값",
                        "applyToCurrent", true, "expectedWorldSettingVersion", actual.getVersion(), "expectedUpdatedAt", row.getUpdatedAt().toString()))))
                .andExpect(status().isConflict());
        assertThat(actual.getPropertyValue("서식지")).isEqualTo("작가가 정한 지역"); assertThat(row.isPendingReview()).isTrue();
    }

    @Test void savedWorldDecisionForNewIdentityDoesNotAdvertiseOriginalTargetCurrentOverride() throws Exception {
        var actual = manualWorld(); var book = upload("엘프는 동부에 산다.");
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var row = comparableWorld(origin, null, actual, "동부"); completeFixtureOrigin(origin); em.flush();
        var selection = Map.of("candidateId", row.getId(), "operation", "ADD", "category", "RACE", "subjectName", "드워프", "settingName", "서식지", "value", "동부");
        mvc.perform(patch("/api/v1/works/{workId}/world-setting-candidates/decisions", work.getId()).header("Authorization", bearer)
                .param("sourceAnalysisJobId", origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("batchId", origin.getBatch().getId(), "candidates", java.util.List.of(selection)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidates[0].applyToCurrentAvailable").value(false));
        mvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{id}/confirm", work.getId(), row.getId()).header("Authorization", bearer)
                .param("sourceAnalysisJobId", origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("operation", "ADD", "category", "RACE", "subjectName", "드워프", "settingName", "서식지", "value", "동부",
                        "applyToCurrent", true, "expectedWorldSettingVersion", actual.getVersion(), "expectedUpdatedAt", row.getUpdatedAt().toString()))))
                .andExpect(status().isConflict());
        assertThat(actual.getPropertyValue("서식지")).isEqualTo("작가가 정한 지역"); assertThat(row.isPendingReview()).isTrue();
        mvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{id}/confirm", work.getId(), row.getId()).header("Authorization", bearer)
                .param("sourceAnalysisJobId", origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("operation", "ADD", "category", "RACE", "subjectName", "드워프", "settingName", "서식지", "value", "동부"))))
                .andExpect(status().isOk());
        assertThat(row.getTargetWorldSetting().getSubjectName()).isEqualTo("드워프");
        assertThat(actual.getPropertyValue("서식지")).isEqualTo("작가가 정한 지역");
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void characterCurrentOverrideGroupIsScopedPerRowAndRejectsStaleLastRowAtomically(boolean staleLast) throws Exception {
        ensureAgeSchema(); ensureLevelSchema(); var character = manualCharacter(77);
        var book = upload("하린은 21살이고 레벨은 3이다.");
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var age = ageCandidate(origin, null, 21); em.persist(age);
        var level = levelCandidate(origin, character, 3); em.persist(level); completeFixtureOrigin(origin); em.flush();
        long version = character.getSnapshotVersion();
        var first = Map.of("candidateId", age.getId(), "applicationMode", "APPLY_PROPOSAL", "applyToCurrent", true,
                "baseSnapshotVersion", version, "expectedUpdatedAt", age.getUpdatedAt().toString());
        var second = Map.of("candidateId", level.getId(), "applicationMode", "APPLY_PROPOSAL", "applyToCurrent", staleLast,
                "baseSnapshotVersion", staleLast ? version - 1 : version, "expectedUpdatedAt", level.getUpdatedAt().toString());
        var request = mvc.perform(post("/api/v1/works/{workId}/setting-candidates/group-confirm", work.getId()).header("Authorization", bearer)
                .param("sourceAnalysisJobId", origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("batchId", origin.getBatch().getId(), "candidates", java.util.List.of(first, second)))));
        if (staleLast) {
            request.andExpect(status().isConflict());
            assertThat(character.getCurrentAge()).isEqualTo(77); assertThat(character.getCurrentLevel()).isNull();
            assertThat(character.getSnapshotVersion()).isEqualTo(version);
            assertThat(age.isPendingReview()).isTrue(); assertThat(level.isPendingReview()).isTrue();
            assertThat(sql.queryForObject("select count(*) from character_facts where extracted_by_job_id=?", Integer.class, origin.getId())).isZero();
        } else {
            request.andExpect(status().isOk());
            assertThat(character.getCurrentAge()).isEqualTo(21); assertThat(character.getCurrentLevel()).isEqualTo(3);
            assertThat(age.getConfirmedApplicationMode().name()).isEqualTo("APPLY_PROPOSAL");
            assertThat(level.getConfirmedApplicationMode().name()).isEqualTo("APPLY_PROPOSAL");
            assertThat(character.isManuallyEditedSlot(CharacterFactType.AGE, "age")).isTrue();
            assertThat(character.isManuallyEditedSlot(CharacterFactType.LEVEL, "level")).isFalse();
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void worldCurrentOverrideGroupIsScopedPerRowAndRejectsStaleLastRowAtomically(boolean staleLast) throws Exception {
        var actual = manualWorld(); var book = upload("엘프는 동부에 산다. 엘프의 수명은 500년이다.");
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var home = comparableWorld(origin, null, actual, "동부");
        var lifespan = comparableWorld(origin, null, actual, "수명", "500년"); completeFixtureOrigin(origin); em.flush();
        long version = actual.getVersion();
        var first = Map.of("candidateId", home.getId(), "operation", "UPDATE", "category", "RACE", "subjectName", "엘프", "settingName", "서식지", "value", "동부",
                "applyToCurrent", true, "expectedWorldSettingVersion", version, "expectedUpdatedAt", home.getUpdatedAt().toString());
        var second = Map.of("candidateId", lifespan.getId(), "operation", "ADD", "category", "RACE", "subjectName", "엘프", "settingName", "수명", "value", "500년",
                "applyToCurrent", staleLast, "expectedWorldSettingVersion", staleLast ? version - 1 : version, "expectedUpdatedAt", lifespan.getUpdatedAt().toString());
        var request = mvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/group-confirm", work.getId()).header("Authorization", bearer)
                .param("sourceAnalysisJobId", origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("batchId", origin.getBatch().getId(), "candidates", java.util.List.of(first, second)))));
        if (staleLast) {
            request.andExpect(status().isConflict());
            assertThat(actual.getPropertyValue("서식지")).isEqualTo("작가가 정한 지역"); assertThat(actual.hasProperty("수명")).isFalse();
            assertThat(actual.getVersion()).isEqualTo(version);
            assertThat(home.isPendingReview()).isTrue(); assertThat(lifespan.isPendingReview()).isTrue();
        } else {
            request.andExpect(status().isOk());
            assertThat(actual.getPropertyValue("서식지")).isEqualTo("동부"); assertThat(actual.getPropertyValue("수명")).isEqualTo("500년");
            assertThat(home.isHistoryOnly()).isFalse(); assertThat(lifespan.isHistoryOnly()).isFalse();
            assertThat(actual.isManuallyEdited(null, "서식지")).isTrue(); assertThat(actual.isManuallyEdited(null, "수명")).isFalse();
        }
    }

    @Test void twoCharacterCurrentOverridesUseTheSamePreMutationVersionOnOneActualTarget() throws Exception {
        ensureAgeSchema(); ensureLevelSchema(); var actual = manualCharacter(77);
        mvc.perform(patch("/api/v1/works/{workId}/characters/{id}", work.getId(), actual.getId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"하린\",\"currentAge\":77,\"currentLevel\":9,\"profile\":[],\"stats\":[],\"skills\":[],\"items\":[],\"statuses\":[]}"))
                .andExpect(status().isOk());
        var book = upload("하린은 21살이고 레벨은 3이다.");
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var age = ageCandidate(origin, null, 21); var level = levelCandidate(origin, actual, 3);
        em.persist(age); em.persist(level); completeFixtureOrigin(origin); em.flush();
        long version = actual.getSnapshotVersion();
        var decisions = java.util.List.of(
                Map.of("candidateId", age.getId(), "applicationMode", "APPLY_PROPOSAL", "applyToCurrent", true, "baseSnapshotVersion", version, "expectedUpdatedAt", age.getUpdatedAt().toString()),
                Map.of("candidateId", level.getId(), "applicationMode", "APPLY_PROPOSAL", "applyToCurrent", true, "baseSnapshotVersion", version, "expectedUpdatedAt", level.getUpdatedAt().toString()));
        mvc.perform(post("/api/v1/works/{workId}/setting-candidates/group-confirm", work.getId()).header("Authorization", bearer)
                .param("sourceAnalysisJobId", origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("batchId", origin.getBatch().getId(), "candidates", decisions))))
                .andExpect(status().isOk());
        assertThat(actual.getCurrentAge()).isEqualTo(21); assertThat(actual.getCurrentLevel()).isEqualTo(3);
        assertThat(age.getMatchedCharacterId()).isEqualTo(actual.getId()); assertThat(level.getMatchedCharacterId()).isEqualTo(actual.getId());
        assertThat(age.getConfirmedApplicationMode().name()).isEqualTo("APPLY_PROPOSAL"); assertThat(level.getConfirmedApplicationMode().name()).isEqualTo("APPLY_PROPOSAL");
        assertThat(actual.isManuallyEditedSlot(CharacterFactType.AGE, "age")).isTrue(); assertThat(actual.isManuallyEditedSlot(CharacterFactType.LEVEL, "level")).isTrue();
        assertThat(sql.queryForObject("select count(*) from character_facts where extracted_by_job_id=?", Integer.class, origin.getId())).isEqualTo(2);
    }

    @Test void twoWorldCurrentOverridesUseTheSamePreMutationVersionOnOneActualTarget() throws Exception {
        var actual = manualWorld(); actual.addProperty("수명", "700년"); em.flush();
        mvc.perform(patch("/api/v1/works/{workId}/world-settings/{id}/properties", work.getId(), actual.getId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("currentSettingName", "수명", "settingName", "수명", "settingValue", "작가가 정한 수명", "version", actual.getVersion()))))
                .andExpect(status().isOk());
        var book = upload("엘프는 동부에 산다. 엘프의 수명은 500년이다.");
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var home = comparableWorld(origin, null, actual, "동부"); var lifespan = comparableWorld(origin, null, actual, "수명", "500년");
        completeFixtureOrigin(origin); em.flush(); long version = actual.getVersion();
        var decisions = java.util.List.of(
                Map.of("candidateId", home.getId(), "operation", "UPDATE", "category", "RACE", "subjectName", "엘프", "settingName", "서식지", "value", "동부", "applyToCurrent", true, "expectedWorldSettingVersion", version, "expectedUpdatedAt", home.getUpdatedAt().toString()),
                Map.of("candidateId", lifespan.getId(), "operation", "UPDATE", "category", "RACE", "subjectName", "엘프", "settingName", "수명", "value", "500년", "applyToCurrent", true, "expectedWorldSettingVersion", version, "expectedUpdatedAt", lifespan.getUpdatedAt().toString()));
        mvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/group-confirm", work.getId()).header("Authorization", bearer)
                .param("sourceAnalysisJobId", origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("batchId", origin.getBatch().getId(), "candidates", decisions))))
                .andExpect(status().isOk());
        assertThat(actual.getPropertyValue("서식지")).isEqualTo("동부"); assertThat(actual.getPropertyValue("수명")).isEqualTo("500년");
        assertThat(home.getTargetWorldSetting().getId()).isEqualTo(actual.getId()); assertThat(lifespan.getTargetWorldSetting().getId()).isEqualTo(actual.getId());
        assertThat(home.isHistoryOnly()).isFalse(); assertThat(lifespan.isHistoryOnly()).isFalse();
        assertThat(actual.isManuallyEdited(null, "서식지")).isTrue(); assertThat(actual.isManuallyEdited(null, "수명")).isTrue();
    }

    @Test void savedFutureWorldCurrentDecisionAllowsSameValueAndKeepsHumanProtection() throws Exception {
        var actual = manualWorld(); var book = uploadReference("8화 이후 엘프는 동부로 이주할 예정이다.", "WORK_START", null);
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var row = comparableWorld(origin, null, actual, "동부"); row.recordTemporalScope(CharacterFactTemporalScope.HYPOTHETICAL, 8);
        completeFixtureOrigin(origin); em.flush();
        var selection = Map.of("candidateId", row.getId(), "operation", "UPDATE", "category", "RACE", "subjectName", "엘프", "settingName", "서식지", "value", "동부");
        mvc.perform(patch("/api/v1/works/{workId}/world-setting-candidates/decisions", work.getId()).header("Authorization", bearer)
                .param("sourceAnalysisJobId", origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("batchId", origin.getBatch().getId(), "candidates", java.util.List.of(selection)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidates[0].applyToCurrentAvailable").value(true));
        em.flush();
        mvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{id}/confirm", work.getId(), row.getId()).header("Authorization", bearer)
                .param("sourceAnalysisJobId", origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("operation", "UPDATE", "category", "RACE", "subjectName", "엘프", "settingName", "서식지", "value", "동부",
                        "applyToCurrent", true, "expectedWorldSettingVersion", actual.getVersion(), "expectedUpdatedAt", row.getUpdatedAt().toString()))))
                .andExpect(status().isOk());
        assertThat(actual.getPropertyValue("서식지")).isEqualTo("동부"); assertThat(actual.isManuallyEdited(null, "서식지")).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(CharacterFactTemporalScope.class)
    void rawNonCurrentBookWorldNeedsSavedCurrentDecisionBeforeNewTargetCreation(CharacterFactTemporalScope temporal) throws Exception {
        var book = uploadReference("8화 이후 엘프는 동부에 살 예정이다.", "WORK_START", null);
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var row = comparableWorld(origin, null, null, "동부"); row.recordTemporalScope(temporal, 8); completeFixtureOrigin(origin); em.flush();
        mvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{id}/confirm", work.getId(), row.getId()).header("Authorization", bearer)
                .param("sourceAnalysisJobId", origin.getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("operation", "ADD", "category", "RACE", "subjectName", "엘프", "settingName", "서식지", "value", "동부"))))
                .andExpect(status().isConflict());
        assertThat(sql.queryForObject("select count(*) from world_settings where work_id=?", Integer.class, work.getId())).isZero();
        assertThat(row.isPendingReview()).isTrue();
    }

    @Test void worldMergedCurrentRetainsOriginalBookSources() throws Exception {
        var book = uploadReference("엘프는 북부에 산다.", "WORK_START", null);
        var origin = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var first = comparableWorld(origin, null, null, "북부"); completeFixtureOrigin(origin); confirmWorld(first);
        var actual = first.getTargetWorldSetting(); var episode = episodeOrderedOrigin(2);
        var merged = comparableWorld(episode, episode.getEpisode(), actual, "북부와 남부");
        org.springframework.test.util.ReflectionTestUtils.setField(merged, "suggestedOperation", WorldSettingSuggestedOperation.MERGE);
        completeFixtureOrigin(episode); confirmWorld(merged);
        assertThat(actual.getPropertyValue("서식지")).isEqualTo("북부와 남부");
        var applied = org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingCurrentSources.forProperty(
                worldCandidateRepository.findAllByTargetWorldSettingIdAndReviewStatusOrderByReviewedAtDescCreatedAtDescIdDesc(actual.getId(), org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.CONFIRMED),
                null, "서식지", actual.getPropertyValue("서식지"));
        assertThat(applied).containsExactly(first, merged);
    }

    @ParameterizedTest @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    @org.junit.jupiter.api.DisplayName("root 이동은 기존 설정집 및 MERGE 전체 출처를 투영과 실제 CURRENT에 보존한다")
    void rootMoveRetainsOriginalBookLineageInProjectedAndActualCurrent(boolean projectedOnly, boolean merged) throws Exception {
        var originalBook = uploadReference("엘프의 서식지는 북부다.", "EPISODE", 2);
        var original = jobs.findById(UUID.fromString(originalBook.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var home = comparableWorld(original, null, null, "북부"); completeFixtureOrigin(original); confirmWorld(home);
        var actual = home.getTargetWorldSetting();
        var originalSources = new java.util.ArrayList<WorldSettingCandidate>(); originalSources.add(home);
        String homeValue = "북부";
        if (merged) {
            var mergeOrigin = episodeOrderedOrigin(3);
            var extra = comparableWorld(mergeOrigin, mergeOrigin.getEpisode(), actual, "북부와 남부");
            org.springframework.test.util.ReflectionTestUtils.setField(extra, "suggestedOperation", WorldSettingSuggestedOperation.MERGE);
            completeFixtureOrigin(mergeOrigin); confirmWorld(extra); originalSources.add(extra); homeValue = "북부와 남부";
        }
        uploadReference("거주집단의 통치자는 왕이다.", "EPISODE", merged ? 4 : 3);
        var claimed = claimRootMoveFixture(); UUID jobId = UUID.fromString(claimed.path("analysisJobId").asText()); UUID lease = UUID.fromString(claimed.path("leaseToken").asText());
        var origin = jobs.findById(jobId).orElseThrow(); origin.updateCheckpointStage(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED);
        var published = worldWorker.publishWorldSettingCandidates(jobId, lease, new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest(java.util.List.of(
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.Candidate(WorldSettingCategory.RACE, "엘프", "거주집단", "통치자", "왕",
                        java.util.List.of(new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.EvidenceSpan("거주집단의 통치자는 왕이다.", 0, 15)), java.math.BigDecimal.ONE, Map.of("epistemic_status", "ASSERTED"),
                        CharacterFactTemporalScope.PRESENT, null))));
        worldWorker.resolveWorldSettingSubjects(jobId, lease, new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest(java.util.List.of(
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput(published.getFirst().candidateId(), java.util.List.of(actual.getId())))));
        var batch = worldWorker.claimNextWorldSettingComparisonBatch(jobId, lease).orElseThrow();
        var context = worldWorker.getWorldSettingComparisonBatchContext(jobId, batch.comparisonBatchId(), lease,
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchContextRequest(batch.resolvedTargetWorldSettingIds(), batch.resolvedProvisionalSubjectKeys()));
        Map<String,Object> decision = new java.util.LinkedHashMap<>(); decision.put("decisionRef", "D1"); decision.put("sourceCandidateRefs", java.util.List.of("C1"));
        decision.put("canonicalSubjectName", "엘프"); decision.put("targetWorldSettingId", actual.getId()); decision.put("existingRootPropertyNamesToMove", java.util.List.of("서식지"));
        decision.put("consolidationStatus", "SINGLE"); decision.put("suggestedOperation", "ADD"); decision.put("proposedScopeName", "거주집단");
        decision.put("proposedSettingName", "통치자"); decision.put("proposedValue", "왕"); decision.put("comparisonReason", "root 속성은 값 변경 없이 범위 아래로 이동");
        mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/world-setting-comparison-batches/{batch}/complete", jobId, batch.comparisonBatchId())
                .header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("contextToken", context.contextToken(), "contextVersions", java.util.List.of(Map.of("worldSettingId", actual.getId(), "version", actual.getVersion())), "decisions", java.util.List.of(decision)))))
                .andExpect(status().isOk());
        if (projectedOnly) {
            JsonNode moved = states.getProjectedState(origin).path("worldSettings").path("world:"+actual.getId()).path("provenanceByPath")
                    .path(org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingAnalysisStateMapper.pathKey("거주집단", "서식지"));
            assertThat(moved.path("sourceCandidateIds")).anySatisfy(id -> assertThat(id.asText()).isEqualTo(home.getId().toString()));
            assertThat(moved.path("sourceCandidateIds")).hasSize(originalSources.size() + 1);
            assertThat(moved.path("sourceCandidateIds")).anySatisfy(id -> assertThat(id.asText()).isEqualTo(published.getFirst().candidateId().toString()));
            assertThat(moved.path("currentSourceTimes")).hasSize(originalSources.size());
            assertThat(moved.path("currentSourceTimes").get(0).path("referenceEpisodeNo").asInt()).isEqualTo(2);
            return;
        }
        assertThat(worldWorker.claimNextWorldSettingComparisonBatch(jobId, lease)).isEmpty();
        origin.updateCheckpointStage(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED);
        worker.completeAnalysisJob(jobId, lease, new org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest(null, null, null));
        em.flush();
        assertThat(actual.hasProperty(null, "서식지")).isFalse();
        assertThat(actual.getPropertyValue("거주집단", "서식지")).isEqualTo(homeValue);
        var history = worldCandidateRepository.findAllByTargetWorldSettingIdAndReviewStatusOrderByReviewedAtDescCreatedAtDescIdDesc(actual.getId(), org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.CONFIRMED);
        assertThat(org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingCurrentSources.forProperty(history, "거주집단", "서식지", homeValue)).containsExactlyElementsOf(originalSources);
        var episode = merged ? completedManualEpisodeOrigin(1) : episodeOrderedOrigin(1);
        var past = comparableWorld(episode, episode.getEpisode(), actual, "남부");
        org.springframework.test.util.ReflectionTestUtils.setField(past, "scopeName", "거주집단");
        org.springframework.test.util.ReflectionTestUtils.setField(past, "matchedScopeName", "거주집단");
        org.springframework.test.util.ReflectionTestUtils.setField(past, "beforeValue", homeValue);
        org.springframework.test.util.ReflectionTestUtils.setField(past, "suggestedOperation", WorldSettingSuggestedOperation.UPDATE);
        if (!merged) completeFixtureOrigin(episode);
        mvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{id}/confirm", work.getId(), past.getId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("operation", "UPDATE", "category", "RACE", "subjectName", "엘프", "scopeName", "거주집단", "settingName", "서식지", "value", "남부"))))
                .andExpect(status().isOk());
        assertThat(past.isHistoryOnly()).isTrue();
        assertThat(actual.getPropertyValue("거주집단", "서식지")).isEqualTo(homeValue);
    }

    @ParameterizedTest @CsvSource({"false,true,4,false", "false,false,2,false", "true,true,4,false", "false,false,2,true"})
    @org.junit.jupiter.api.DisplayName("EP1 속성의 BOOK3 범위 이동은 의미 출처와 후행 batch 의존성을 각각 보존한다")
    void rootMoveKeepsEpisodeSourceAndDependentBatchContextAcrossReload(boolean staleMove, boolean automatic, int incomingNo, boolean episodeIncoming) throws Exception {
        var episode = episodeOrderedOrigin(1);
        var home = comparableWorld(episode, episode.getEpisode(), null, "북부"); completeFixtureOrigin(episode); confirmWorld(home);
        var actual = home.getTargetWorldSetting();
        String firstQuote = "거주집단의 통치자는 왕이다.";
        String secondQuote = "거주집단의 서식지는 북부다.";
        uploadReference(firstQuote+"\n"+secondQuote, "EPISODE", 3);
        JsonNode claimed = claimRootMoveFixture(); var origin = jobs.findById(UUID.fromString(claimed.path("analysisJobId").asText())).orElseThrow();
        UUID lease = UUID.fromString(claimed.path("leaseToken").asText());
        origin.updateCheckpointStage(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED);
        var published = worldWorker.publishWorldSettingCandidates(origin.getId(), lease,
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest(java.util.List.of(
                        worldInput(null, "통치자", "왕", firstQuote, 0), worldInput("거주집단", "서식지", "북부", secondQuote, firstQuote.length()+1))));
        worldWorker.resolveWorldSettingSubjects(origin.getId(), lease,
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest(published.stream().map(row ->
                        new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput(row.candidateId(), java.util.List.of(actual.getId()))).toList()));
        var moveBatch = worldWorker.claimNextWorldSettingComparisonBatch(origin.getId(), lease).orElseThrow();
        assertThat(moveBatch.candidates()).extracting(row -> row.settingName()).containsExactly("통치자");
        JsonNode beforeContext = actualWorldContext(origin.getId(), lease, moveBatch);
        var move = new java.util.LinkedHashMap<String,Object>(); move.put("decisionRef", "D1"); move.put("sourceCandidateRefs", java.util.List.of("C1"));
        move.put("canonicalSubjectName", "엘프"); move.put("targetWorldSettingId", actual.getId()); move.put("existingRootPropertyNamesToMove", java.util.List.of("서식지"));
        move.put("consolidationStatus", "SINGLE"); move.put("suggestedOperation", "ADD"); move.put("proposedScopeName", "거주집단");
        move.put("proposedSettingName", "통치자"); move.put("proposedValue", "왕"); move.put("comparisonReason", "속성 값과 원 근거는 유지하고 위치만 이동");
        completeWorldBatch(origin.getId(), lease, moveBatch.comparisonBatchId(), beforeContext, move);
        JsonNode movedProvenance = states.getProjectedState(origin).path("worldSettings").path("world:"+actual.getId()).path("provenanceByPath")
                .path(org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingAnalysisStateMapper.pathKey("거주집단", "서식지"));
        assertThat(movedProvenance.path("sourceCandidateIds").get(0).asText()).isEqualTo(home.getId().toString());
        assertThat(origin.getStateJournal().path("changes")).anySatisfy(change -> {
            assertThat(change.path("operation").asText()).isEqualTo("ADD");
            assertThat(change.path("sourceCandidateIds").get(0).asText()).isEqualTo(published.getFirst().candidateId().toString());
        });
        var dependentBatch = worldWorker.claimNextWorldSettingComparisonBatch(origin.getId(), lease).orElseThrow();
        JsonNode movedContext = actualWorldContext(origin.getId(), lease, dependentBatch);
        var duplicate = new java.util.LinkedHashMap<String,Object>(); duplicate.put("decisionRef", "D1"); duplicate.put("sourceCandidateRefs", java.util.List.of("C1"));
        duplicate.put("canonicalSubjectName", "엘프"); duplicate.put("targetWorldSettingId", actual.getId()); duplicate.put("consolidationStatus", "SINGLE");
        duplicate.put("suggestedOperation", "EXCLUDE"); duplicate.put("matchedScopeName", "거주집단"); duplicate.put("matchedPropertyName", "서식지");
        duplicate.put("proposedScopeName", "거주집단"); duplicate.put("proposedSettingName", "서식지"); duplicate.put("proposedValue", "북부"); duplicate.put("comparisonReason", "이동된 기존 값과 중복");
        completeWorldBatch(origin.getId(), lease, dependentBatch.comparisonBatchId(), movedContext, duplicate);
        if (staleMove) { actual.updateProperty("서식지", "서식지", "작가가 정한 동부"); em.flush(); }
        assertThat(worldWorker.claimNextWorldSettingComparisonBatch(origin.getId(), lease)).isEmpty();
        origin.updateCheckpointStage(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED);
        worker.completeAnalysisJob(origin.getId(), lease, new org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest(null,null,null));
        var movedCandidate = worldCandidateRepository.findById(published.getFirst().candidateId()).orElseThrow();
        var dependent = worldCandidateRepository.findById(published.getLast().candidateId()).orElseThrow();
        if (staleMove) {
            assertThat(movedCandidate.isPendingReview()).isTrue(); assertThat(dependent.isPendingReview()).isTrue();
            assertThat(actual.getPropertyValue("서식지")).isEqualTo("작가가 정한 동부"); assertThat(actual.hasProperty("거주집단", "서식지")).isFalse();
            return;
        }
        assertThat(movedCandidate.getReviewStatus()).isEqualTo(org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.CONFIRMED);
        assertThat(dependent.getReviewStatus()).isEqualTo(org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.DISMISSED);
        assertThat(actual.getPropertyValue("거주집단", "서식지")).isEqualTo("북부");
        if (episodeIncoming) {
            var following = episodeOrderedOrigin(incomingNo);
            var changed = comparableWorld(following,following.getEpisode(),actual,"남부");
            org.springframework.test.util.ReflectionTestUtils.setField(changed,"scopeName","거주집단");
            org.springframework.test.util.ReflectionTestUtils.setField(changed,"matchedScopeName","거주집단");
            org.springframework.test.util.ReflectionTestUtils.setField(changed,"proposedScopeName","거주집단");
            org.springframework.test.util.ReflectionTestUtils.setField(changed,"matchedPropertyName","서식지");
            org.springframework.test.util.ReflectionTestUtils.setField(changed,"beforeValue","북부");
            org.springframework.test.util.ReflectionTestUtils.setField(changed,"suggestedOperation",WorldSettingSuggestedOperation.UPDATE);
            completeFixtureOrigin(following);
            mvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{id}/confirm",work.getId(),changed.getId()).header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("operation","UPDATE","category","RACE","subjectName","엘프","scopeName","거주집단","settingName","서식지","value","남부","applyToCurrent",false))))
                    .andExpect(status().isOk());
            assertThat(changed.isHistoryOnly()).isFalse(); assertThat(actual.getPropertyValue("거주집단","서식지")).isEqualTo("남부");
            return;
        }
        uploadReference("거주집단의 서식지는 남부다.", "EPISODE", incomingNo);
        JsonNode reloadedClaim = claimRootMoveFixture(); UUID reloadedJob = UUID.fromString(reloadedClaim.path("analysisJobId").asText()); UUID reloadedLease = UUID.fromString(reloadedClaim.path("leaseToken").asText());
        jobs.findById(reloadedJob).orElseThrow().updateCheckpointStage(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED);
        var next = worldWorker.publishWorldSettingCandidates(reloadedJob,reloadedLease,new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest(
                java.util.List.of(worldInput("거주집단", "서식지", "남부", "거주집단의 서식지는 남부다.", 0)))).getFirst();
        worldWorker.resolveWorldSettingSubjects(reloadedJob,reloadedLease,new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest(java.util.List.of(
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput(next.candidateId(),java.util.List.of(actual.getId())))));
        var nextBatch = worldWorker.claimNextWorldSettingComparisonBatch(reloadedJob,reloadedLease).orElseThrow();
        JsonNode reloadedContext = actualWorldContext(reloadedJob,reloadedLease,nextBatch);
        for (JsonNode context : java.util.List.of(beforeContext,movedContext,reloadedContext)) {
            assertThat(context.path("targets").get(0).path("properties")).anySatisfy(property -> {
                assertThat(property.path("settingName").asText()).isEqualTo("서식지");
                assertThat(property.path("provenance").path("sourceCandidateIds").get(0).asText()).isEqualTo(home.getId().toString());
                assertThat(property.path("provenance").path("currentSourceTimes").get(0).path("sourceEpisodeNo").asInt()).isEqualTo(1);
            });
        }
        var exportDir = System.getenv("GH221_EXPORT_DIR");
        if (exportDir != null) java.nio.file.Files.writeString(java.nio.file.Path.of(exportDir,"java-root-move-wire.json"),
                json.writeValueAsString(Map.of("beforeContext",beforeContext,"movedContext",movedContext,"reloadedContext",reloadedContext,"originalSourceCandidateId",home.getId())));
        var update = new java.util.LinkedHashMap<String,Object>(); update.put("decisionRef","D1"); update.put("sourceCandidateRefs",java.util.List.of("C1"));
        update.put("canonicalSubjectName","엘프"); update.put("targetWorldSettingId",actual.getId()); update.put("consolidationStatus","SINGLE");
        update.put("matchedScopeName","거주집단"); update.put("matchedPropertyName","서식지");
        update.put("suggestedOperation",automatic ? "UPDATE" : "REVIEW_REQUIRED"); if (!automatic) update.put("comparisonReviewReason","GENERAL_UNCERTAINTY");
        update.put("proposedScopeName","거주집단"); update.put("proposedSettingName","서식지"); update.put("proposedValue","남부"); update.put("comparisonReason","EP1 이후 설정집 근거로 현재값 갱신");
        completeWorldBatch(reloadedJob,reloadedLease,nextBatch.comparisonBatchId(),reloadedContext,update);
        assertThat(worldWorker.claimNextWorldSettingComparisonBatch(reloadedJob,reloadedLease)).isEmpty();
        jobs.findById(reloadedJob).orElseThrow().updateCheckpointStage(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED);
        worker.completeAnalysisJob(reloadedJob,reloadedLease,new org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest(null,null,null));
        if (!automatic) {
            var ordinary = Map.of("operation","UPDATE","category","RACE","subjectName","엘프","scopeName","거주집단","settingName","서식지","value","남부");
            var draft = new java.util.LinkedHashMap<String,Object>(ordinary); draft.put("candidateId",next.candidateId());
            mvc.perform(patch("/api/v1/works/{workId}/world-setting-candidates/decisions",work.getId()).header("Authorization",bearer).param("sourceAnalysisJobId",reloadedJob.toString()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("batchId",jobs.findById(reloadedJob).orElseThrow().getBatch().getId(),"candidates",java.util.List.of(draft)))))
                    .andExpect(status().isOk());
            var confirm = new java.util.LinkedHashMap<String,Object>(ordinary); confirm.put("applyToCurrent",false);
            mvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{id}/confirm",work.getId(),next.candidateId()).header("Authorization",bearer).param("sourceAnalysisJobId",reloadedJob.toString()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(confirm))).andExpect(status().isOk());
        }
        var confirmed = worldCandidateRepository.findById(next.candidateId()).orElseThrow();
        assertThat(confirmed.getReviewStatus()).isEqualTo(org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus.CONFIRMED);
        assertThat(confirmed.isHistoryOnly()).isFalse();
        assertThat(actual.getPropertyValue("거주집단","서식지")).isEqualTo("남부");
    }

    private org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.Candidate worldInput(
            String scope, String name, String value, String quote, int start) {
        return new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.Candidate(WorldSettingCategory.RACE,"엘프",scope,name,value,
                java.util.List.of(new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.EvidenceSpan(quote,start,start+quote.length())),java.math.BigDecimal.ONE,Map.of("epistemic_status", "ASSERTED"),CharacterFactTemporalScope.PRESENT,null);
    }

    private AnalysisJob completedManualEpisodeOrigin(int number) {
        var episode = org.monitoring.catchholebackend.domain.episode.entity.Episode.create(work,null,number,number+"화","synthetic/"+number+".txt","v1","d".repeat(64),10); em.persist(episode);
        var origin = AnalysisJob.create(work,null,episode,org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION); em.persist(origin); em.flush();
        origin.claim("synthetic-only","과거 회차 직접 검토 fixture",java.time.LocalDateTime.now().plusMinutes(5)); origin.succeed(null,0,0);
        return origin;
    }

    private JsonNode actualWorldContext(UUID job, UUID lease,
            org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingComparisonBatchPayload batch) throws Exception {
        return response(mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/world-setting-comparison-batches/{batch}/context",job,batch.comparisonBatchId())
                .header("X-Internal-Api-Key","test-internal-api-key").header("X-Worker-Lease-Token",lease).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("targetWorldSettingIds",batch.resolvedTargetWorldSettingIds(),"provisionalSubjectKeys",batch.resolvedProvisionalSubjectKeys()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void completeWorldBatch(UUID job, UUID lease, UUID batch, JsonNode context, Map<String,Object> decision) throws Exception {
        var versions = new java.util.ArrayList<Map<String,Object>>();
        context.path("targets").forEach(target -> versions.add(Map.of("worldSettingId",target.path("worldSettingId").asText(),"version",target.path("version").asLong())));
        mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/world-setting-comparison-batches/{batch}/complete",job,batch)
                .header("X-Internal-Api-Key","test-internal-api-key").header("X-Worker-Lease-Token",lease).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("contextToken",context.path("contextToken").asText(),"contextVersions",versions,"decisions",java.util.List.of(decision)))))
                .andExpect(status().isOk());
    }

    JsonNode uploadReference(String text, String kind, Integer number) throws Exception {
        var request = multipart("/api/v1/works/{workId}/setting-books", work.getId())
                .file(new MockMultipartFile("file", "시점설정.txt", MediaType.TEXT_PLAIN_VALUE, text.getBytes(StandardCharsets.UTF_8)))
                .param("referenceTimeKind", kind).header("Authorization", bearer);
        if (number != null) request.param("referenceEpisodeNo", number.toString());
        return response(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    void ensureLevelSchema() {
        var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        if (sql.queryForObject("select count(*) from character_setting_schemas where schema_key='level'", Integer.class) == 0)
            em.persist(org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema.create(work, "level", null, "레벨",
                    CharacterFactType.LEVEL, org.monitoring.catchholebackend.domain.character.type.SettingValueType.NUMBER,
                    org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics.BASE_VALUE,
                    org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy.REPLACE, j.arrayNode(),
                    org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource.SYSTEM_SEED, true));
        em.flush();
    }

    SettingCandidate levelCandidate(AnalysisJob origin, WorkCharacter character, int level) {
        var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var row = SettingCandidate.create(work, null, null, origin, org.monitoring.catchholebackend.domain.character.type.SettingEntityType.CHARACTER,
                "하린", "하린", character.getId(), org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.MATCHED,
                "level", Integer.toString(level), org.monitoring.catchholebackend.domain.character.type.SettingValueType.NUMBER,
                j.objectNode().put("value", level), j.arrayNode(), java.math.BigDecimal.ONE, null);
        row.startComparison(); row.recordComparisonContext(character.getSnapshotVersion(), "level-context");
        row.completeComparison(CharacterFactOperation.ADD, CharacterFactType.LEVEL, "level", Integer.toString(level), j.objectNode().put("value", level),
                j.arrayNode(), CharacterFactTemporalScope.PRESENT, "레벨 근거", j.objectNode(), java.time.LocalDateTime.now(), "level", j.arrayNode());
        return row;
    }

    WorkCharacter manualCharacter(int age) throws Exception {
        var character = WorkCharacter.create(work, "하린", null, null, null, null, null, null, null, null, null); em.persist(character); em.flush();
        mvc.perform(patch("/api/v1/works/{workId}/characters/{id}", work.getId(), character.getId())
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"하린\",\"currentAge\":"+age+",\"profile\":[],\"stats\":[],\"skills\":[],\"items\":[],\"statuses\":[]}"))
                .andExpect(status().isOk());
        return character;
    }

    WorldSetting manualWorld() throws Exception {
        var setting = WorldSetting.create(work, WorldSettingCategory.RACE, "엘프", "서식지", "북부"); em.persist(setting); em.flush();
        mvc.perform(patch("/api/v1/works/{workId}/world-settings/{id}/properties", work.getId(), setting.getId())
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentSettingName", "서식지", "settingName", "서식지", "settingValue", "작가가 정한 지역", "version", setting.getVersion()))))
                .andExpect(status().isOk());
        return setting;
    }

    void attachAutomaticContext(SettingCandidate candidate, WorkCharacter character, CharacterFactOperation operation) {
        var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var batch = CharacterFactComparisonBatch.create(work, candidate.getEpisode(), candidate.getAnalysisJob(), character,
                candidate.getComparisonTargetFactType(), 1, character.getSnapshotVersion());
        var context = j.objectNode(); var slots = context.putArray("slots");
        snapshotAccessor.read(character).values().forEach(entry -> slots.add(j.objectNode().put("factType", entry.slot().factType().name())
                .put("factKey", entry.slot().factKey()).put("factValue", entry.factValue()).set("valueJson", entry.valueJson())));
        batch.recordAnalysisContext(context); em.persist(batch);
        org.springframework.test.util.ReflectionTestUtils.setField(candidate, "characterComparisonBatch", batch);
        org.springframework.test.util.ReflectionTestUtils.setField(candidate, "characterComparisonCandidateRef", "C1");
        org.springframework.test.util.ReflectionTestUtils.setField(candidate, "suggestedOperation", operation);
        org.springframework.test.util.ReflectionTestUtils.setField(candidate, "comparisonBaseSnapshotVersion", character.getSnapshotVersion());
        em.flush();
    }

    void attachAutomaticWorld(WorldSettingCandidate candidate, WorldSetting actual) {
        attachAutomaticWorld(candidate, actual, WorldSettingSuggestedOperation.UPDATE);
    }

    void attachAutomaticWorld(WorldSettingCandidate candidate, WorldSetting actual, WorldSettingSuggestedOperation operation) {
        var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var job = candidate.getAnalysisJob(); String ref = "world:"+actual.getId();
        var batch = WorldSettingComparisonBatch.createOrdered(work, candidate.getSourceEpisode(), job, candidate.getCategory(), null,
                WorldSettingSubjectResolutionType.EXISTING, ref, actual.getSubjectName(), j.arrayNode().add(actual.getId().toString()), j.arrayNode(), 1); em.persist(batch);
        var decision = WorldSettingComparisonDecision.create(batch, "D1", actual.getSubjectName(), actual, null, candidate.getSettingName(),
                WorldSettingConsolidationStatus.SINGLE, operation, null, null, candidate.getSettingName(),
                actual.getPropertyValue(candidate.getSettingName()), candidate.getExtractedValue(), "강제 비교 근거", j.objectNode());
        decision.bindOrderedTarget(null, actual.getVersion()); em.persist(decision);
        org.springframework.test.util.ReflectionTestUtils.setField(candidate, "comparisonDecision", decision);
        org.springframework.test.util.ReflectionTestUtils.setField(candidate, "suggestedOperation", operation);
        em.persist(WorldSettingComparisonDecisionSource.create(batch, decision, candidate, "C1", 0)); em.flush();
    }

    org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob episodeOrderedOrigin(int number) {
        return episodeOrderedOrigin(number, null);
    }

    org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob episodeOrderedOrigin(int number, String frozenContent) {
        var batch=org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.create(work,work.getMember(),org.monitoring.catchholebackend.domain.upload.type.UploadType.SINGLE_EPISODE,
                org.monitoring.catchholebackend.domain.upload.type.UploadSourceType.FILE);em.persist(batch);
        String key = "synthetic/"+number+".txt", hash = "d".repeat(64);
        if (frozenContent != null) {
            byte[] bytes = frozenContent.getBytes(StandardCharsets.UTF_8); objects.put(key, bytes);
            try { hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
        var episode=org.monitoring.catchholebackend.domain.episode.entity.Episode.create(work,null,number,number+"화",key,"v1",hash,10);em.persist(episode);
        var origin=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.create(work,batch,episode,org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION);
        origin.configureReviewMode(org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC);states.initializeRun(java.util.List.of(origin));em.persist(origin);em.flush();return origin;
    }

    void confirmAgeGroup(org.monitoring.catchholebackend.domain.character.entity.SettingCandidate age) throws Exception {
        mvc.perform(post("/api/v1/works/{workId}/setting-candidates/group-confirm",work.getId()).header("Authorization",bearer)
                .param("sourceAnalysisJobId",age.getAnalysisJob().getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("batchId",age.getAnalysisJob().getBatch().getId(),"candidates",java.util.List.of(
                        Map.of("candidateId",age.getId(),"applicationMode","APPLY_PROPOSAL")))))).andExpect(status().isOk());
    }

    org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate comparableWorld(
            org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob origin,org.monitoring.catchholebackend.domain.episode.entity.Episode episode,
            org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting current,String value) {
        return comparableWorld(origin, episode, current, "서식지", value);
    }

    org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate comparableWorld(
            org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob origin,org.monitoring.catchholebackend.domain.episode.entity.Episode episode,
            org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting current,String settingName,String value) {
        var j=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var row=org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.create(work,episode,origin,
                org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE,"엘프",settingName,value,
                controlledEvidence(origin, "엘프의 서식지는 "+value+"다.", 12),java.math.BigDecimal.ONE,
                origin.isSettingBookSource() ? j.objectNode().put("epistemic_status", "ASSERTED") : null);
        row.recordTemporalScope(CharacterFactTemporalScope.PRESENT, null);
        row.resolveSubject(current == null ? WorldSettingSubjectResolutionType.NEW : WorldSettingSubjectResolutionType.EXISTING,
                current == null ? "new:엘프" : "world:"+current.getId(), "엘프", current == null ? j.arrayNode() : j.arrayNode().add(current.getId().toString()));
        row.startComparison();row.completeComparison(current,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingConsolidationStatus.SINGLE,
                current==null || !current.hasProperty(settingName) ? org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation.ADD : org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation.UPDATE,
                null,settingName,current==null ? null : current.getPropertyValue(settingName),value,"합성 원문 근거",j.objectNode(),java.time.LocalDateTime.now());
        em.persist(row);em.flush();return row;
    }

    JsonNode controlledEvidence(AnalysisJob origin, String preferredQuote, int legacyEndOffset) {
        var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        if (!origin.isSettingBookSource() && !objects.containsKey(origin.getSourceContentS3Key())) return j.arrayNode().add(j.objectNode().put("quote", preferredQuote).put("startOffset", 0).put("endOffset", legacyEndOffset));
        String content = new String(objects.get(origin.getSourceContentS3Key()), StandardCharsets.UTF_8);
        // These policy fixtures force comparator outcomes; their source citation still uses actual frozen bytes.
        return j.arrayNode().add(identitySpan(content, content.contains(preferredQuote) ? preferredQuote : content));
    }

    String editRequest(String bookId, String body) throws Exception {
        // These single-client policy fixtures start a new edit from the saved revision.
        // Stale/multi-tab fixtures capture their old base explicitly in SettingBookRevisionCasIntegrationTest.
        var request = (com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(body);
        var saved = em.find(org.monitoring.catchholebackend.domain.upload.entity.UploadFile.class, UUID.fromString(bookId));
        request.put("expectedRevisionNo", saved.getCurrentSettingBookRevision() == null ? 0 : saved.getCurrentSettingBookRevision().getRevisionNo());
        return json.writeValueAsString(request);
    }

    void confirmWorld(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate row) throws Exception {
        mvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{id}/confirm",work.getId(),row.getId()).header("Authorization",bearer)
                .param("sourceAnalysisJobId",row.getAnalysisJob().getId().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("operation",row.getSuggestedOperation().name(),"category","RACE","subjectName","엘프","settingName","서식지","value",row.getProposedValue()))))
                .andExpect(status().isOk());
    }

    void assertReviewPage(JsonNode response,UUID expected) {
        JsonNode groups=response.path("groups");assertThat(groups.path("page").asInt()).isZero();assertThat(groups.path("size").asInt()).isEqualTo(1);
        assertThat(groups.path("totalElements").asInt()).isEqualTo(1);assertThat(groups.path("totalPages").asInt()).isEqualTo(1);assertThat(groups.path("hasNext").asBoolean()).isFalse();
        assertThat(groups.path("content")).hasSize(1);assertThat(groups.path("content").get(0).path("candidates")).hasSize(1);
        assertThat(groups.path("content").get(0).path("candidates").get(0).path("id").asText()).isEqualTo(expected.toString());
    }

    JsonNode claimRootMoveFixture() {
        // Named policy fixture only: unfinished C02 claim capability is not advertised or tested here.
        UUID id = sql.queryForObject("select id from analysis_jobs where work_id=? and status='PENDING' order by created_at desc limit 1", UUID.class, work.getId());
        AnalysisJob job = jobs.findByIdForUpdate(id).orElseThrow(); assertThat(states.prepareInput(job)).isTrue();
        UUID lease = job.claim("deterministic-fixture", "typed root move fixture", java.time.LocalDateTime.now().plusMinutes(5));
        return json.createObjectNode().put("analysisJobId", id.toString()).put("leaseToken", lease.toString());
    }

    JsonNode claimBook() throws Exception {
        return response(mvc.perform(post("/api/internal/v1/analysis-jobs/claim").header("X-Internal-Api-Key","test-internal-api-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"allowedJobTypes\":[\"SETTING_EXTRACTION\"],\"supportsFrozenComparisonEvidence\":true,\"supportedAnalysisModes\":[\"ORDERED_PROVISIONAL\"],\"supportedSourceKinds\":[\"SETTING_BOOK\"],\"supportsSettingBookReferenceTime\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    void reserveTokens(UUID jobId,String lease,UUID request,long count,int expected) throws Exception {
        mvc.perform(post("/api/internal/v1/ai-token-usages/reserve").header("X-Internal-Api-Key","test-internal-api-key").header("X-Worker-Lease-Token",lease)
                .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\""+request+"\",\"analysisJobId\":\""+jobId+"\",\"purpose\":\"SETTING_EXTRACTION\",\"attempt\":1,\"modelName\":\"test-only\",\"reservedTokens\":"+count+"}"))
                .andExpect(status().is(expected));
    }

    JsonNode upload(String text) throws Exception {
        return response(mvc.perform(multipart("/api/v1/works/{workId}/setting-books",work.getId())
                .file(new MockMultipartFile("file","인물설정.txt",MediaType.TEXT_PLAIN_VALUE,text.getBytes(StandardCharsets.UTF_8)))
                .header("Authorization",bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    JsonNode analyze(String id) throws Exception {
        return response(mvc.perform(post("/api/v1/works/{workId}/setting-books/{id}/analysis-jobs",work.getId(),id)
                .header("Authorization",bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    JsonNode response(String body) throws Exception { return json.readTree(body).path("data"); }
}
