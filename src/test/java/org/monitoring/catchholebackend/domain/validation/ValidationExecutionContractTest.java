package org.monitoring.catchholebackend.domain.validation;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.validation.dto.ValidationExecutionContract.*;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper;
import org.monitoring.catchholebackend.domain.validation.processor.*;
import org.monitoring.catchholebackend.domain.validation.service.ValidationDataService;
import org.monitoring.catchholebackend.domain.validation.type.*;

@DisplayName("Java/Python 검수 실행 v1 공통 wire와 code point grid")
class ValidationExecutionContractTest {
    final ObjectMapper json=new ObjectMapper();final AnalysisStateJournal journal=new AnalysisStateJournal();
    JsonNode fixture() throws Exception {return json.readTree(Path.of("docs/contracts/validation-execution-v1.json").toFile()).path("execution");}
    @Test @DisplayName("요청 DTO의 필드와 fixture가 정확히 왕복한다") void exactRequestDtosRoundTrip() throws Exception {
        var f=fixture();assertThat(f.path("contractVersion").asInt()).isEqualTo(1);
        Map<String,Class<?>> requests=Map.ofEntries(Map.entry("claim",Claim.class),Map.entry("heartbeat",Lease.class),Map.entry("plan",Publish.class),Map.entry("mergedPlan",Publish.class),Map.entry("settingUnit",Unit.class),Map.entry("typoUnit",Unit.class),Map.entry("revisions",Revise.class),Map.entry("checkpoint",Checkpoint.class),Map.entry("complete",Complete.class),Map.entry("retry",Retry.class),Map.entry("reserve",Reserve.class),Map.entry("settle",Usage.class),Map.entry("unknown",Usage.class),Map.entry("release",Usage.class));
        for(var entry:requests.entrySet()) {var input=f.path("requests").path(entry.getKey());assertThat(input.isObject()).as(entry.getKey()).isTrue();Object command=json.treeToValue(input,entry.getValue());assertThat(journal.canonicalJson(json.valueToTree(command))).as(entry.getKey()).isEqualTo(journal.canonicalJson(input));}
    }
    @Test @DisplayName("접수 때 색인 채우기 예약 예시는 IndexInput과 정확히 왕복하고 조각 digest 벡터가 맞다") void indexFillReservationRoundTrips() throws Exception {
        var fill=fixture().path("indexFill");var reservation=fill.path("reservation");
        assertThat(fill.path("role").asText()).isEqualTo(ValidationRole.INDEX_EMBEDDING.name());assertThat(fill.path("purpose").asText()).isEqualTo(ValidationRole.INDEX_EMBEDDING.purpose().name());
        assertThat(fill.path("chargePolicy").asText()).isEqualTo(org.monitoring.catchholebackend.domain.aitoken.type.AiTokenChargePolicy.servicePaid().version());
        var command=json.treeToValue(reservation,Reserve.class);assertThat(command.role()).isEqualTo(ValidationRole.INDEX_EMBEDDING);assertThat(command.queryInput()).isNull();
        assertThat(journal.canonicalJson(json.valueToTree(command))).isEqualTo(journal.canonicalJson(reservation));
        var declared=new ArrayList<String>();for(var component:IndexInput.class.getRecordComponents())declared.add(component.getName());
        var fields=new ArrayList<String>();reservation.path("indexInput").fieldNames().forEachRemaining(fields::add);assertThat(fields).isEqualTo(declared);
        var vector=fill.path("chunkDigestVector");var hashes=new ArrayList<String>();for(var hash:vector.path("chunkHashes"))hashes.add(hash.asText());
        var digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(String.join("\n",hashes).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(digest).isEqualTo(vector.path("sha256").asText()).isEqualTo(command.indexInput().chunkDigest());
    }
    @Test @DisplayName("서버 hash와 Python bundle hash의 계산 규칙을 구분한다") void canonicalDigestsHaveKnownVectors() throws Exception {
        var f=fixture();for(var v:f.path("serverDigestVectors")) {assertThat(journal.canonicalJson(v.path("input"))).isEqualTo(v.path("canonicalJson").asText());assertThat(journal.hash(v.path("input"))).isEqualTo(v.path("sha256").asText());}
        var worker=f.path("workerBundleDigestVector");assertThat(journal.hash(worker.path("input"))).isNotEqualTo(worker.path("sha256").asText());
        assertThat(worker.path("canonicalJson").asText()).contains(": ",", ");
    }
    static String caseText(JsonNode example) {var repeat=example.path("targetRepeat");return repeat.isObject()?repeat.path("unit").asText().repeat(repeat.path("count").asInt()):example.path("target").asText();}
    @Test @DisplayName("원문 길이에서 계산한 SETTING grid v4·TYPO grid v1이 공통 fixture(Python split_setting_segments)와 같다") void serverDerivesTheSharedGridVectors() throws Exception {
        var f=fixture();var mapping=new ValidationDataMapper();var decision=new ValidationDecisionValidator(mapping,journal);int v4=0;
        for(var example:f.path("gridCases")) {
            var service=mock(ValidationDataService.class);String hash="a".repeat(64),target=caseText(example);UUID job=UUID.randomUUID(),report=UUID.randomUUID(),lease=UUID.randomUUID(),work=UUID.randomUUID();
            var context=new ValidationPublicationValidator.Context(job,lease,new Scope(1,report,1,hash),1L,work,hash,hash,example.path("configuration"),example.has("baselineEntries")?example.path("baselineEntries"):json.createArrayNode(),Map.of());
            var input=json.createObjectNode().put("status","READY").put("entryCount",example.path("entryCount").asInt());input.putObject("target").put("contentS3Version","v1");input.set("sources",example.path("sources"));
            var reads=new ArrayList<String>();
            when(service.readValidationData(anyLong(),any(),any(),any(),any(),any())).thenAnswer(call->{var request=call.getArgument(3,org.monitoring.catchholebackend.domain.validation.dto.request.ValidationDataRequest.class);reads.add(request.operation()+":"+request.sourceRef());if(request.operation().equals("INPUT"))return input;
                return json.createObjectNode().put("status","READY").put("text",target).put("totalCodePoints",target.codePointCount(0,target.length()));});
            var validator=new ValidationPublicationValidator(service,decision,mapping);
            if(example.has("expectedError")) {assertThatThrownBy(()->validator.prepare(context,ValidationBranch.SETTING)).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);continue;}
            assertThat(journal.canonicalJson(validator.prepare(context,ValidationBranch.SETTING).grid())).as(example.path("name").asText()).isEqualTo(journal.canonicalJson(example.path("expectedSetting")));
            assertThat(journal.canonicalJson(validator.prepare(context,ValidationBranch.TYPO).grid())).as(example.path("name").asText()).isEqualTo(journal.canonicalJson(example.path("expectedTypo")));
            // Grid v4 reads only INPUT and the full target: setting books are no longer grid obligations.
            assertThat(reads).allMatch(read->read.equals("INPUT:null")||read.equals("READ_SOURCE:T1"));
            if(example.path("expectedSetting").path("gridVersion").asInt()==4)v4++;
        }
        assertThat(v4).isGreaterThanOrEqualTo(10);
    }
    @Test @DisplayName("서버 projection은 페이지와 무관한 S 참조와 원문 ref를 보존한다") void canonicalProjectionHasStableSubjectAndSourceRefs() throws Exception {
        var f=fixture().path("projectionVector");var sources=new LinkedHashMap<String,JsonNode>();f.path("sources").properties().forEach(e->sources.put(e.getKey(),e.getValue()));
        var mapper=new ValidationDataMapper();assertThat(json.<JsonNode>valueToTree(mapper.toValidationSettings(f.path("entries"),sources))).isEqualTo(f.path("expected"));
        assertThat(mapper.toValidationSourceCatalogue(sources)).isEqualTo(f.path("sourceCatalogue"));
    }
    @Test @DisplayName("정상 세계관의 text와 quote 속성은 원문 인용 필드로 오인하지 않는다")
    void canonicalWorldTextAndQuotePropertiesAreNotSourceContexts() throws Exception {
        var f=fixture();var entries=f.path("projectionVector").path("entries").deepCopy();
        var properties=(com.fasterxml.jackson.databind.node.ObjectNode)entries.path(1).path("state").path("propertiesJson");
        properties.put("text","합성 세계 설정 값").put("quote","합성 이름 속성");
        var mapper=new ValidationDataMapper();var settings=mapper.toValidationSettings(entries,Map.of());
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)f.path("requests").path("settingUnit").path("payload").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)payload.path("evidence_bundle")).set("settings",json.valueToTree(settings.values()));
        var validator=payloadValidator();var prepared=payloadContext(entries);var citations=payloadCitations();
        assertThatCode(()->validator.payload(prepared,payload,citations)).doesNotThrowAnyException();
        ((com.fasterxml.jackson.databind.node.ObjectNode)payload.path("evidence_bundle").path("settings").path(1).path("state").path("propertiesJson")).put("text","검증되지 않은 복사본문");
        assertThatThrownBy(()->validator.payload(prepared,payload,citations)).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
    }
    @Test @DisplayName("ref만 담은 settings 행은 받고 대상이 다르거나 추가 키에 복사본문을 숨기면 거절한다")
    void refOnlySettingRowsPassAndHiddenCopiesFail() throws Exception {
        var f=fixture();var payload=(com.fasterxml.jackson.databind.node.ObjectNode)f.path("requests").path("settingUnit").path("payload").deepCopy();
        var row=((com.fasterxml.jackson.databind.node.ObjectNode)payload.path("evidence_bundle")).putArray("settings").addObject().put("entry_ref","B2").put("subject_ref","S2");
        var validator=payloadValidator();var prepared=payloadContext(f.path("projectionVector").path("entries"));var citations=payloadCitations();
        assertThatCode(()->validator.payload(prepared,payload,citations)).doesNotThrowAnyException();
        row.put("subject_ref","S1");
        assertThatThrownBy(()->validator.payload(prepared,payload,citations)).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
        row.put("subject_ref","S2").put("quote","검증되지 않은 복사본문");
        assertThatThrownBy(()->validator.payload(prepared,payload,citations)).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
    }
    @Test @DisplayName("정의된 인용 경로 밖 복사와 필수 context 인용 누락은 계속 거절한다")
    void undeclaredCopiesAndMissingContextCitationsRemainInvalid() throws Exception {
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)fixture().path("requests").path("settingUnit").path("payload").deepCopy();var prepared=payloadContext(json.createArrayNode());var validator=payloadValidator();var citations=payloadCitations();
        payload.putObject("untrusted").put("text","😀가😀가");
        assertThatThrownBy(()->validator.payload(prepared,payload,citations)).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
        payload.remove("untrusted");
        assertThatThrownBy(()->validator.payload(prepared,payload,List.of(citations.getFirst()))).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
    }
    ValidationPublicationValidator payloadValidator() {
        var data=mock(ValidationDataService.class);when(data.readValidationData(anyLong(),any(),any(),any(),any(),any())).thenAnswer(call->{var request=call.getArgument(3,org.monitoring.catchholebackend.domain.validation.dto.request.ValidationDataRequest.class);String text="😀가😀가";int start=request.start()==null?0:request.start(),end=request.end()==null?4:request.end();return json.createObjectNode().put("status","READY").put("start",start).put("end",end).put("text",text.substring(text.offsetByCodePoints(0,start),text.offsetByCodePoints(0,end)));});
        var mapper=new ValidationDataMapper();return new ValidationPublicationValidator(data,new ValidationDecisionValidator(mapper,journal),mapper);
    }
    ValidationPublicationValidator.Prepared payloadContext(JsonNode entries) throws Exception {
        var citations=payloadCitations();String hash=citations.getFirst().sourceHash();
        Map<String,JsonNode> references=Map.of("T1",json.createObjectNode().put("sourceRef","T1").put("contentHash",hash).put("contentS3Version",citations.getFirst().sourceVersion()));
        var c=new ValidationPublicationValidator.Context(UUID.randomUUID(),UUID.randomUUID(),new Scope(1,UUID.randomUUID(),1,hash),1L,UUID.randomUUID(),hash,hash,json.createObjectNode(),entries,references);
        return new ValidationPublicationValidator.Prepared(c,ValidationBranch.SETTING,json.createObjectNode(),new HashMap<>(Map.of("T1",json.createObjectNode().put("contentHash",hash).put("text","😀가😀가").put("totalCodePoints",4))),entries.size());
    }
    List<Citation> payloadCitations() throws Exception {return json.convertValue(fixture().path("requests").path("settingUnit").path("citations"),new com.fasterxml.jackson.core.type.TypeReference<>(){});}
    @Test @DisplayName("인용은 이미 검증한 전체 원문을 code point로 잘라 비교하고 범위마다 저장소를 다시 읽지 않는다")
    void citationsSliceTheVerifiedFullTextWithoutRangedReads() throws Exception {
        var data=mock(ValidationDataService.class);var mapper=new ValidationDataMapper();var validator=new ValidationPublicationValidator(data,new ValidationDecisionValidator(mapper,journal),mapper);
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)fixture().path("requests").path("settingUnit").path("payload").deepCopy();
        var prepared=payloadContext(json.createArrayNode());var citations=payloadCitations();
        assertThatCode(()->validator.payload(prepared,payload,citations)).doesNotThrowAnyException();
        verifyNoInteractions(data);
        var moved=new ArrayList<>(citations);var first=moved.getFirst();moved.set(0,new Citation(first.sourceRef(),first.sourceHash(),first.sourceVersion(),1,3,first.quote()));
        assertThatThrownBy(()->validator.payload(prepared,payload,moved)).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
        var otherVersion=new ArrayList<>(citations);otherVersion.set(0,new Citation(first.sourceRef(),first.sourceHash(),"other-version",first.start(),first.end(),first.quote()));
        assertThatThrownBy(()->validator.payload(prepared,payload,otherVersion)).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
        var beyond=new ArrayList<>(citations);beyond.set(0,new Citation(first.sourceRef(),first.sourceHash(),first.sourceVersion(),3,9,"가"));
        assertThatThrownBy(()->validator.payload(prepared,payload,beyond)).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
    }
    @Test @DisplayName("판정 근거 문장 벡터: Worker가 게시하는 E·B 인용은 서버 규칙을 통과하고 버린 인용은 서버도 받지 않는다(오류 리포트 가독성)")
    void basisQuoteVectorsMatchServerRules() throws Exception {
        var flow=fixture().path("judgeFlow").path("basisQuotes");
        assertThat(flow.path("maxItems").asInt()).isEqualTo(ValidationBasisQuotes.MAX_ITEMS);
        assertThat(flow.path("maxQuoteCodePoints").asInt()).isEqualTo(ValidationBasisQuotes.MAX_QUOTE_CODE_POINTS);
        assertThat(flow.path("vectors").size()).isGreaterThanOrEqualTo(6);
        for(var v:flow.path("vectors")) {
            String name=v.path("name").asText();var published=v.path("published");var evidence=v.path("evidence");var setting=v.path("setting");
            if(!published.isNull()) {
                String quote=published.path("quote").asText();
                assertThat(ValidationBasisQuotes.codePoints(quote)).as(name).isBetween(1,ValidationBasisQuotes.MAX_QUOTE_CODE_POINTS);
                if(ValidationBasisQuotes.isSettingQuote(published)) {
                    assertThat(published.path("source_ref").isNull()&&published.path("start").isNull()&&published.path("end").isNull()).as(name).isTrue();
                    assertThat(ValidationBasisQuotes.containing(setting,quote)).as(name).isNotEmpty();
                } else {
                    assertThat(published.path("source_ref").asText()).as(name).isEqualTo(evidence.path("source_ref").asText()).isNotEqualTo("T1");
                    int start=published.path("start").asInt(),end=published.path("end").asInt(),from=evidence.path("start").asInt();
                    assertThat(start).as(name).isGreaterThanOrEqualTo(from);assertThat(end).as(name).isLessThanOrEqualTo(evidence.path("end").asInt());
                    assertThat(ValidationBasisQuotes.slice(evidence.path("quote").asText(),start-from,end-from)).as(name).isEqualTo(quote);
                }
            } else if(!setting.isNull()) {
                assertThat(ValidationBasisQuotes.containing(setting,v.path("answer").path("quote").asText().strip())).as(name+": a dropped setting quote is not a value leaf").isEmpty();
            } else {
                boolean target="T1".equals(evidence.path("source_ref").asText());
                assertThat(target||!evidence.path("quote").asText().contains(v.path("answer").path("quote").asText().strip())).as(name+": a dropped evidence quote is the target or not in the chunk").isTrue();
            }
        }
    }
    @Test @DisplayName("판정 작성 제목(headline)은 인용 경로가 아닌 글이라 게시 인용 검사를 그대로 통과한다")
    void headlineIsPlainTextForCitationChecks() throws Exception {
        var flow=fixture().path("judgeFlow").path("headline");
        assertThat(flow.path("maxCodePoints").asInt()).isEqualTo(ValidationDecisionValidator.MAX_HEADLINE_CODE_POINTS);
        assertThat(fixture().path("judgeFlow").path("authorText").path("fields").toString()).contains("headline");
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)fixture().path("requests").path("settingUnit").path("payload").deepCopy();
        payload.put("headline",flow.path("vectors").path(0).path("headline").asText());
        var validator=payloadValidator();var prepared=payloadContext(json.createArrayNode());var citations=payloadCitations();
        assertThatCode(()->validator.payload(prepared,payload,citations)).doesNotThrowAnyException();
        payload.putNull("headline");
        assertThatCode(()->validator.payload(prepared,payload,citations)).doesNotThrowAnyException();
    }
    @Test @DisplayName("질문 하나의 INCOMPLETE unit 예시가 DTO로 왕복하고 인용 검사를 통과한다(③)")
    void incompleteQuestionUnitExampleRoundTripsAndIsCited() throws Exception {
        var failure=fixture().path("judgeFlow").path("questionFailure");var unit=failure.path("unit");
        var command=json.treeToValue(unit,Unit.class);
        assertThat(journal.canonicalJson(json.valueToTree(command))).isEqualTo(journal.canonicalJson(unit));
        assertThat(command.outcome()).isEqualTo("INCOMPLETE");
        var fields=new HashSet<String>();unit.path("payload").fieldNames().forEachRemaining(fields::add);
        var declared=new HashSet<String>();failure.path("payloadFields").forEach(n->declared.add(n.asText()));
        assertThat(fields).isEqualTo(declared);
        var validator=payloadValidator();var prepared=payloadContext(json.createArrayNode());
        assertThatCode(()->validator.payload(prepared,unit.path("payload"),command.citations())).doesNotThrowAnyException();
        assertThatThrownBy(()->validator.payload(prepared,unit.path("payload"),List.of())).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
    }
    @Test @DisplayName("configuration v2 예시는 서버 빌더가 운영 예시 설정에서 만드는 job configuration과 같다")
    void configurationV2ExampleIsWhatTheServerBuilds() throws Exception {
        var expected=fixture().path("configurationV2").path("configuration");
        var roles=new EnumMap<ValidationRole,org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget>(ValidationRole.class);
        for(var role:List.of(ValidationRole.PLANNER,ValidationRole.JUDGE,ValidationRole.TYPO)) {
            var cap=expected.path("budgets").path("roles").path(role.name());var model=expected.path("models").path(role.name());
            roles.put(role,new org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget(cap.path("maxCalls").asInt(),cap.path("maxReservedTokens").asLong(),cap.path("outputTokens").asInt(),model.path("model").asText(),model.path("reasoningEffort").asText()));
        }
        for(var role:List.of(ValidationRole.QUERY_EMBEDDING,ValidationRole.INDEX_EMBEDDING)) {
            var embedding=expected.path("budgets").path("roles").path(role.name());
            roles.put(role,new org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget(embedding.path("maxCalls").asInt(),embedding.path("maxReservedTokens").asLong(),null,null,null));
        }
        var limits=new HashMap<String,org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.Limits>();
        for(String branch:List.of("setting","typo")) {var b=expected.path("budgets").path(branch);
            limits.put(branch,new org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.Limits(b.path("modelCalls").asInt(),b.path("lookups").asInt(),b.path("seconds").asDouble(),b.path("inputTokens").asInt(),b.path("segmentChars").asInt(),b.path("contextChars").asInt(),b.path("searchLimit").asInt()));}
        var properties=new org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties(true,java.time.Duration.ofMillis(expected.path("leaseMillis").asLong()),expected.path("maxLeaseRecoveries").asInt(),java.time.Duration.ofMinutes(10),limits.get("setting"),limits.get("typo"),roles,"text-embedding-3-small");
        var retrieval=mock(org.monitoring.catchholebackend.global.config.chat.ChatProperties.class);when(retrieval.retrievalEmbeddingVersion()).thenReturn("v1");
        var built=ValidationExecutionConfiguration.build(properties,retrieval);
        assertThat(journal.canonicalJson(built)).isEqualTo(journal.canonicalJson(expected));
        assertThat(ValidationExecutionConfiguration.isCurrent(built)).isTrue();
        var legacy=built.deepCopy();legacy.remove("configurationVersion");assertThat(ValidationExecutionConfiguration.isCurrent(legacy)).isFalse();
        // The shared claim example freezes the production model pairing.
        assertThat(fixture().path("models").path("JUDGE").path("model").asText()).isEqualTo("gpt-6-luna");
        assertThat(fixture().path("responses").path("claim").path("configurationVersion").asInt()).isEqualTo(2);
        assertThat(fixture().path("responses").path("claim").has("clueVersion")).isFalse();
    }
}
