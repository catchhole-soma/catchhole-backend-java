package org.monitoring.catchholebackend.domain.character.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.monitoring.catchholebackend.domain.analysis.entity.*;
import org.monitoring.catchholebackend.domain.analysis.mapper.*;
import org.monitoring.catchholebackend.domain.analysis.service.*;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.analysis.dto.response.*;
import org.monitoring.catchholebackend.domain.character.entity.*;
import org.monitoring.catchholebackend.domain.character.mapper.*;
import org.monitoring.catchholebackend.domain.character.service.*;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.character.dto.response.*;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;

/** Actual production mappers -> controllers -> MVC converter -> localhost HTTP. Only service return values are stubbed. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.docker.compose.enabled=false","internal.api-key=time-json-contract-only"})
@ActiveProfiles("test")
@DisplayName("Worker 캐릭터 비교와 서사 시점 HTTP 계약")
class WorkerNarrativeTimeHttpContractTest {
 @LocalServerPort int port;
 @Autowired CharacterFactComparisonWorkerMapper candidateMapper;
 @Autowired CharacterFactComparisonBatchWorker batchMapper;
 @Autowired AnalysisJobWorkerMapper jobMapper;
 @MockitoBean CharacterFactComparisonWorkerService comparisons;
 @MockitoBean AnalysisJobWorkerService jobs;
 @MockitoBean org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingWorkerService worlds;
 @Autowired AnalysisRunContextMapper contexts;
 final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
 final UUID character=UUID.randomUUID(),candidateId=UUID.randomUUID(),chunk=UUID.randomUUID(),batch=UUID.randomUUID(),lease=UUID.randomUUID();
 final String hash="a".repeat(64),quote="오늘은 \"장마\" 첫날이다. 🌧";
 final Map<String,JsonNode> bodies=new LinkedHashMap<>();

 @Test void frozenSelectionCapabilityIsConsistentAcrossAllContextRoutes() throws Exception {
  var job = org.mockito.Mockito.mock(AnalysisJob.class);
  when(job.isOrderedProvisional()).thenReturn(true);
  when(job.getAnalysisRunId()).thenReturn(UUID.randomUUID());
  when(job.getRunGeneration()).thenReturn(1L);
  when(job.getInputStateHash()).thenReturn(hash);
  when(job.getSourceContentHash()).thenReturn(hash);
  var frozen = json.createObjectNode();
  var reference = frozen.putObject("references").putObject("pending-character:synthetic");
  reference.put("domain", "characters").put("subjectName", "합성 인물").put("sourceEpisodeNo", 1)
      .put("reason", "검토 대기").put("settingName", "status.부상").put("value", "골절");
  reference.putArray("evidenceSpans").addObject().put("quote", "합성 근거");
  reference.putObject("selection").put("factType", "STATUS").putArray("actualSubjectIds").add(character.toString());
  var expectedValue = json.createObjectNode().put("active", false).put("value", "골절").putNull("endedAt");
  expectedValue.putObject("details").put("confirmed", true).putArray("symptoms").add("통증").addNull().add(2);
  ((ObjectNode) reference.path("selection")).set("valueJson", expectedValue.deepCopy());
  when(job.getAutomaticInputState()).thenReturn(frozen);
  UUID jobId = UUID.randomUUID();
  for (String capability : List.of("missing", "false", "true")) {
   boolean include = capability.equals("true");
   var context = contexts.toResponse(job, include);
   when(jobs.claimAnalysisJob(any(), eq(include))).thenReturn(Optional.of(contextRecord(WorkerAnalysisJobPayload.class, context)));
   when(comparisons.claimNextCharacterFactComparisonBatch(any(), any(), eq(false), eq(include)))
       .thenReturn(Optional.of(contextRecord(WorkerCharacterFactComparisonBatchPayload.class, context)));
   when(comparisons.getCharacterFactComparisonBatchContext(any(), any(), any(), eq(include)))
       .thenReturn(contextRecord(WorkerCharacterFactComparisonBatchContextResponse.class, context));
   when(worlds.getPendingWorldSettingSubjectResolutions(any(), any(), eq(include)))
       .thenReturn(contextRecord(org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingSubjectResolutionPendingResponse.class, context));
   when(worlds.claimNextWorldSettingComparisonBatch(any(), any(), eq(include)))
       .thenReturn(Optional.of(contextRecord(org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingComparisonBatchPayload.class, context)));
   when(worlds.getWorldSettingComparisonBatchContext(any(), any(), any(), any(), eq(include)))
       .thenReturn(contextRecord(org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingComparisonBatchContextResponse.class, context));
   String base = "/api/internal/v1/analysis-jobs/" + jobId;
   var paths = List.of("/api/internal/v1/analysis-jobs/claim", base + "/character-fact-comparison-batches/claim-next",
       base + "/character-fact-comparison-batches/" + batch + "/context", base + "/world-setting-subject-resolutions/pending",
       base + "/world-setting-comparison-batches/claim-next", base + "/world-setting-comparison-batches/" + batch + "/context");
   for (String path : paths) {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .header("X-Internal-Api-Key", "time-json-contract-only").header("X-Worker-Lease-Token", lease.toString())
        .header("Content-Type", "application/json");
    if (!capability.equals("missing")) request.header("X-Analysis-Reference-Selection", capability);
    if (path.endsWith("/pending")) request.GET();
    else request.POST(HttpRequest.BodyPublishers.ofString(path.endsWith("/claim") ? "{\"allowedJobTypes\":[\"SETTING_EXTRACTION\"]}"
        : path.contains("world-setting-comparison-batches/") && path.endsWith("/context") ? "{\"targetWorldSettingIds\":[],\"provisionalSubjectKeys\":[]}" : "{}"));
    var response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(capability + " " + path).isEqualTo(200);
    var actual = json.readTree(response.body()).at("/data/analysisContext/unresolvedReferences/0");
    assertThat(actual.has("selection")).as(capability + " " + path).isEqualTo(include);
    if (include) {
     assertThat(actual.at("/selection/actualSubjectIds/0").asText()).isEqualTo(character.toString());
     assertThat(actual.at("/selection/valueJson")).as("plain JSON " + path).isEqualTo(expectedValue);
     assertThat(actual.at("/selection/valueJson/active").isBoolean()).isTrue();
    }
    if (path.contains("character-fact-comparison-batches/") && path.endsWith("/context")) {
     var carrier = json.readTree(response.body()).at("/data/advisoryCurrentContext");
     assertThat(carrier.has("selectionNames")).as(capability).isEqualTo(include);
    }
   }
  }
 }

 @SuppressWarnings("unchecked") private <T> T contextRecord(Class<T> type, WorkerAnalysisContextPayload context) throws Exception {
  var fields = type.getRecordComponents();
  Object[] args = new Object[fields.length];
  Class<?>[] types = new Class<?>[fields.length];
  for (int i=0; i<fields.length; i++) {
   types[i] = fields[i].getType();
   args[i] = types[i] == WorkerCharacterAdvisoryCurrentContextPayload.class
       ? new WorkerCharacterAdvisoryCurrentContextPayload(1, 0, null, 0, hash, hash, hash, true, character, null, List.of())
           .withSelectionNames(context.unresolvedReferences().getFirst().selection() == null ? List.of() : List.of("합성 별칭"))
       : types[i] == WorkerAnalysisContextPayload.class ? context : types[i] == long.class ? 0L
       : types[i] == int.class ? 0 : types[i] == boolean.class ? false : List.class.isAssignableFrom(types[i]) ? List.of() : null;
  }
  return type.getDeclaredConstructor(types).newInstance(args);
 }

 @Test
 @DisplayName("인물 묶음 claim은 명시적인 Worker 지원 헤더가 있을 때만 허용한다")
 void personBatchClaimRequiresExplicitWorkerCapabilityHeader() throws Exception {
  UUID job = UUID.randomUUID(), work = UUID.randomUUID();
  var candidate = new WorkerCharacterFactComparisonBatchPayload.Candidate("C1", "Q1", CharacterFactType.STATUS,
          null, "status.부상", "status.부상", CharacterFactCanonicalKeyResolution.PATTERN, "부상", SettingValueType.JSON,
          Map.of("value", "부상"), List.of(), null, null, null);
  when(comparisons.claimNextCharacterFactComparisonBatch(eq(job), eq(lease), eq(false), eq(false)))
          .thenReturn(Optional.of(new WorkerCharacterFactComparisonBatchPayload(batch, work, null, "K1", "류정우",
                  CharacterFactType.STATUS, List.of(candidate))));
  when(comparisons.claimNextCharacterFactComparisonBatch(eq(job), eq(lease), eq(true), eq(false)))
          .thenReturn(Optional.of(new WorkerCharacterFactComparisonBatchPayload(batch, work, null, "K1", "류정우",
                  null, List.of(candidate))));
  for (String capability : List.of("missing", "false", "true")) {
   var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/internal/v1/analysis-jobs/" + job + "/character-fact-comparison-batches/claim-next"))
           .header("X-Internal-Api-Key", "time-json-contract-only").header("X-Worker-Lease-Token", lease.toString())
           .POST(HttpRequest.BodyPublishers.noBody());
   if (!capability.equals("missing")) request.header("X-Character-Comparison-Person-Batches", capability);
   var response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
   assertThat(response.statusCode()).as(capability).isEqualTo(200);
   var payload = json.readTree(response.body()).path("data");
   assertThat(payload.path("canonicalFactType").isNull()).as(capability).isEqualTo(capability.equals("true"));
   assertThat(payload.at("/candidates/0/canonicalFactType").asText()).isEqualTo("STATUS");
  }
 }

 @Test void productionTimeValuesSurviveActualHttpIncludingLegacyNulls() throws Exception {
  Member member=Member.register("time-contract@fixture.invalid","fixture",null,"검증");
  Work work=Work.create(member,"시점 HTTP 계약",WorkGenre.FANTASY,null);id(work,UUID.randomUUID());
  Episode episode=Episode.create(work,null,2,"장마 첫날","fixture/time.txt",null,hash,quote.codePointCount(0,quote.length()));id(episode,UUID.randomUUID());
  AnalysisJob job=AnalysisJob.create(work,null,episode,AnalysisJobType.SETTING_EXTRACTION);id(job,UUID.randomUUID());
  job.claim("gpt-6.1-sol","HTTP contract",LocalDateTime.now().plusHours(1));
  ObjectNode point=json.createObjectNode().put("description","장마 첫날");
  point.putArray("evidenceSpans").addObject().put("quote",quote).put("startOffset",0).put("endOffset",quote.codePointCount(0,quote.length())).put("sourceChunkId",chunk.toString());
  ObjectNode time=json.createObjectNode();time.set("factTime",point);time.set("referenceTime",point.deepCopy());
  ObjectNode origin=json.createObjectNode().put("sourceCandidateId",candidateId.toString()).putNull("sourceAnalysisJobId").put("availability","UNAVAILABLE").put("unavailableReason","LEGACY_NO_MANIFEST").putNull("candidateWasEdited").putNull("source").put("quoteRelation","SOURCE_CONTEXT_ONLY");origin.putArray("evidenceSpans");origin.set("narrativeTime",time.deepCopy());
  ObjectNode representative=point.deepCopy();representative.put("sourceCandidateId",candidateId.toString());representative.set("supportingSource",origin.deepCopy());
  ObjectNode owner=json.createObjectNode().put("actualCharacterId",character.toString()).putNull("provisionalSubjectKey").put("name","류정우").put("currentSupportingEvidenceVersion",1);
  owner.putArray("aliases");owner.putArray("identityEvidence");owner.putObject("provenance").put("sourceKind","EPISODE").put("sourceEpisodeNo",1).put("sourceEpisodeId",episode.getId().toString()).put("sourceHash",hash);owner.set("currentNarrativeTime",representative);
  ObjectNode slot=owner.putObject("slots").putObject("PROFILE:profile.거처").put("factType","PROFILE").put("factKey","profile.거처").put("factValue","항구");slot.putObject("valueJson").put("value","항구");
  ObjectNode provenance=slot.putObject("provenance").put("currentValueProtected",false);provenance.putArray("sourceCandidateIds").add(candidateId.toString());provenance.putArray("currentSourceTimes");slot.putArray("supportingSources").add(origin.deepCopy());
  ObjectNode history=owner.putArray("historyFacts").addObject().put("factId",UUID.randomUUID().toString()).put("sourceCandidateId",candidateId.toString()).put("factType","PROFILE").put("factKey","profile.거처").put("factValue","옛 항구").put("operation","ADD").put("temporalScope","PAST");history.putObject("valueJson").put("value","옛 항구");history.putArray("removedSnapshotEntries");history.set("narrativeTime",time.deepCopy());history.set("supportingSource",origin.deepCopy());
  var advisory=candidateMapper.toAdvisoryCurrentContext(job,owner,character,null);
  SettingCandidate entity=SettingCandidate.create(work,episode,chunk,job,SettingEntityType.CHARACTER,"류정우","profile.거처","항구",SettingValueType.STRING,json.createObjectNode().put("value","항구"),json.createArrayNode(),null,json.createObjectNode());id(entity,candidateId);entity.recordNarrativeTime(time);
  ReflectionTestUtils.setField(entity,"matchedCharacterId",character);ReflectionTestUtils.setField(entity,"characterComparisonCandidateRef","C1");
  var single=candidateMapper.toCandidatePayload(entity,"류정우",CharacterFactType.PROFILE,"profile.거처");
  var mappedCandidates=batchCandidates(entity);
  var claim=new WorkerCharacterFactComparisonBatchPayload(batch,work.getId(),episode.getId(),"K1","류정우",CharacterFactType.PROFILE,mappedCandidates);
  var context=new WorkerCharacterFactComparisonBatchContextResponse(batch,"K1","류정우",CharacterFactType.PROFILE,0,mappedCandidates,List.of(),hash,null,null,List.of(),advisory);
  when(comparisons.claimNextCharacterFactComparisonBatch(any(),any(),eq(false),eq(false))).thenReturn(Optional.of(claim));
  when(comparisons.getCharacterFactComparisonBatchContext(any(),any(),any(),eq(false))).thenReturn(context);
  when(comparisons.claimNextCharacterFactComparison(any(),any())).thenReturn(Optional.of(single));
  when(comparisons.getCharacterFactComparisonContext(any(),any(),any())).thenReturn(new WorkerCharacterFactComparisonContextResponse(single,List.of(),List.of(),hash,List.of(),advisory));
  String base="/api/internal/v1/analysis-jobs/"+job.getId();
  fetch("batch-claim",base+"/character-fact-comparison-batches/claim-next","{}");
  fetch("batch-context",base+"/character-fact-comparison-batches/"+batch+"/context","{}");
  fetch("single-claim",base+"/character-fact-comparisons/claim-next","{}");
  fetch("single-context",base+"/setting-candidates/"+candidateId+"/character-fact-comparison-context","{}");
  WorkCharacter known=WorkCharacter.create(work,"류정우",null,null,null,null,null,null,null,null,null);id(known,character);ReflectionTestUtils.setField(known,"currentNarrativeTime",representative);
  when(jobs.claimAnalysisJob(any(),eq(false))).thenReturn(Optional.of(jobMapper.toResponse(job,episode,List.of(),List.of(known),List.of())));
  fetch("known-legacy","/api/internal/v1/analysis-jobs/claim","{\"allowedJobTypes\":[\"SETTING_EXTRACTION\"]}");
  ReflectionTestUtils.setField(job,"analysisMode",AnalysisMode.ORDERED_PROVISIONAL);ReflectionTestUtils.setField(job,"analysisRunId",UUID.randomUUID());ReflectionTestUtils.setField(job,"runGeneration",1L);ReflectionTestUtils.setField(job,"inputStateHash",hash);
  ObjectNode state=json.createObjectNode();ObjectNode characters=state.putObject("characters");characters.set("character:"+character,owner);
  ObjectNode provisional=owner.deepCopy();provisional.putNull("actualCharacterId").put("provisionalSubjectKey","provisional-character:"+UUID.randomUUID()).put("name","새 인물");characters.set(provisional.path("provisionalSubjectKey").asText(),provisional);
  when(jobs.claimAnalysisJob(any(),eq(false))).thenReturn(Optional.of(jobMapper.toResponse(job,episode,List.of(),List.of(),List.of(),state)));
  fetch("known-provisional-ordered","/api/internal/v1/analysis-jobs/claim","{\"allowedJobTypes\":[\"SETTING_EXTRACTION\"]}");
  // Legacy null is mapped through both real candidate construction paths.
  ReflectionTestUtils.setField(entity,"narrativeTime",null);
  when(comparisons.claimNextCharacterFactComparison(any(),any())).thenReturn(Optional.of(candidateMapper.toCandidatePayload(entity,"류정우",CharacterFactType.PROFILE,"profile.거처")));
  when(comparisons.claimNextCharacterFactComparisonBatch(any(),any(),eq(false),eq(false))).thenReturn(Optional.of(new WorkerCharacterFactComparisonBatchPayload(batch,work.getId(),episode.getId(),"K1","류정우",CharacterFactType.PROFILE,batchCandidates(entity))));
  fetch("single-null",base+"/character-fact-comparisons/claim-next","{}");fetch("batch-null",base+"/character-fact-comparison-batches/claim-next","{}");
  ReflectionTestUtils.setField(known,"currentNarrativeTime",null);ReflectionTestUtils.setField(job,"analysisMode",AnalysisMode.CONFIRMED_ONLY);
  when(jobs.claimAnalysisJob(any(),eq(false))).thenReturn(Optional.of(jobMapper.toResponse(job,episode,List.of(),List.of(known),List.of())));
  fetch("known-null","/api/internal/v1/analysis-jobs/claim","{\"allowedJobTypes\":[\"SETTING_EXTRACTION\"]}");
  ObjectNode emptyOwner=owner.deepCopy();emptyOwner.putNull("currentNarrativeTime");ObjectNode nullHistory=history.deepCopy();nullHistory.putNull("narrativeTime");((ObjectNode)nullHistory.path("supportingSource")).putNull("narrativeTime");emptyOwner.putArray("historyFacts").add(nullHistory);emptyOwner.putObject("slots");
  var nullAdvisory=candidateMapper.toAdvisoryCurrentContext(job,emptyOwner,character,null);
  when(comparisons.getCharacterFactComparisonContext(any(),any(),any())).thenReturn(new WorkerCharacterFactComparisonContextResponse(candidateMapper.toCandidatePayload(entity,"류정우",CharacterFactType.PROFILE,"profile.거처"),List.of(),List.of(),hash,List.of(),nullAdvisory));
  fetch("advisory-null",base+"/setting-candidates/"+candidateId+"/character-fact-comparison-context","{}");
  export(point,time,representative);
  SoftAssertions softly=new SoftAssertions();
  for(String name:List.of("single-claim","single-context"))softly.assertThat(bodies.get(name).at(name.equals("single-context")?"/candidate/narrativeTime":"/narrativeTime")).as(name).isEqualTo(time);
  for(String name:List.of("batch-claim","batch-context"))softly.assertThat(bodies.get(name).at("/candidates/0/narrativeTime")).as(name).isEqualTo(time);
  for(String name:List.of("single-context","batch-context")) {JsonNode a=bodies.get(name).path("advisoryCurrentContext");softly.assertThat(a.path("currentNarrativeTime")).as(name+" representative").isEqualTo(representative);softly.assertThat(a.at("/currentSlots/0/supportingSources/0/narrativeTime")).as(name+" current fact").isEqualTo(time);softly.assertThat(a.at("/historyFacts/0/narrativeTime")).as(name+" history fact").isEqualTo(time);softly.assertThat(a.at("/historyFacts/0/supportingSource/narrativeTime")).as(name+" history supporting").isEqualTo(time);}
  ObjectNode publicTime=representative.deepCopy();publicTime.remove("supportingSource");
  softly.assertThat(bodies.get("known-legacy").at("/knownCharacters/0/currentNarrativeTime")).as("nonordered representative").isEqualTo(representative);
  softly.assertThat(bodies.get("known-provisional-ordered").at("/knownCharacters/0/currentNarrativeTime")).as("ordered known representative").isEqualTo(publicTime);
  softly.assertThat(bodies.get("known-provisional-ordered").at("/provisionalCharacters/0/currentNarrativeTime")).as("ordered provisional representative").isEqualTo(publicTime);
  softly.assertThat(bodies.get("single-null").path("narrativeTime").isNull()).as("singleton legacy null").isTrue();softly.assertThat(bodies.get("batch-null").at("/candidates/0/narrativeTime").isNull()).as("batch legacy null").isTrue();softly.assertThat(bodies.get("known-null").at("/knownCharacters/0/currentNarrativeTime").isNull()).as("known legacy null").isTrue();softly.assertThat(bodies.get("advisory-null").at("/advisoryCurrentContext/currentNarrativeTime").isNull()).as("advisory legacy null").isTrue();softly.assertThat(bodies.get("advisory-null").at("/advisoryCurrentContext/historyFacts/0/narrativeTime").isNull()).as("legacy history null").isTrue();softly.assertThat(bodies.get("advisory-null").at("/advisoryCurrentContext/historyFacts/0/supportingSource/narrativeTime").isNull()).as("legacy history supporting null").isTrue();softly.assertAll();
 }
 @SuppressWarnings("unchecked") List<WorkerCharacterFactComparisonBatchPayload.Candidate> batchCandidates(SettingCandidate candidate)throws Exception {
  Class<?> target=Class.forName(CharacterFactComparisonBatchWorker.class.getName()+"$CanonicalTarget");var tc=target.getDeclaredConstructors()[0];tc.setAccessible(true);Object canonical=tc.newInstance(CharacterFactType.PROFILE,"profile.거처",SettingValueType.STRING,CharacterFactCanonicalKeyResolution.EXACT);
  Class<?> joined=Class.forName(CharacterFactComparisonBatchWorker.class.getName()+"$CandidateTarget");var jc=joined.getDeclaredConstructors()[0];jc.setAccessible(true);
  return ReflectionTestUtils.invokeMethod(batchMapper,"toPayloadCandidates",List.of(jc.newInstance(candidate,canonical)));
 }
 void fetch(String name,String path,String body)throws Exception {var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("X-Internal-Api-Key","time-json-contract-only").header("X-Worker-Lease-Token",lease.toString()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).as(name).isEqualTo(200);bodies.put(name,json.readTree(response.body()).path("data"));}
 void export(JsonNode point,JsonNode time,JsonNode representative)throws Exception {String destination=System.getenv("TIME_JSON_HTTP_OUTPUT");if(destination==null)return;Path out=Path.of(destination);Files.createDirectories(out);for(var e:bodies.entrySet())Files.writeString(out.resolve(e.getKey()+".json"),json.writeValueAsString(e.getValue()),StandardOpenOption.CREATE_NEW);Files.writeString(out.resolve("expected.json"),json.writeValueAsString(Map.of("point",point,"time",time,"representative",representative)),StandardOpenOption.CREATE_NEW);}
 static void id(Object entity,UUID id){ReflectionTestUtils.setField(entity,"id",id);}
}
