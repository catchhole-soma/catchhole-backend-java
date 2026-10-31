package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * #314 B2 ①②④: 설정 finding의 대상·설정명, 등급·대상 서버 필터, 상세 basis의 정정 연결. 합성 Worker 게시가 실제 finding을 만들며
 * 고정 기준은 fixture의 캐릭터(B1=S1 "합성 인물")와 세계관(B2=S2 "합성 종족", 서식지=북부)이다.
 */
@SpringBootTest(properties="spring.config.import=") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("#314 B2 검수 finding의 대상·설정명·필터와 정정 연결")
class ValidationFindingTargetIntegrationTest extends ValidationReviewFixture {
    @Autowired ValidationDataMapper dataMapper;

    record Question(List<String> subjects,String property,String impact) {}

    /** 질문마다 subject_refs·property_or_rule·영향 등급을 정해 SETTING finding을 저장하고 오탈자 2건과 함께 실행을 끝낸다. */
    UUID completedWith(List<Question> questions) throws Exception {
        var job=begin();var report=reportId(job);
        var drafts=new ArrayList<Map<String,Object>>();
        for(int i=0;i<questions.size();i++) {var d=draft("local-"+(i+1),List.of(SPAN));d.put("subject_refs",questions.get(i).subjects());d.put("property_or_rule",questions.get(i).property());drafts.add(d);}
        var planner=publication(job,"SETTING","PLANNER","TSEG1",batch("TSEG1",drafts));planner.put("citations",List.of(cite(0,2,"처음")));internal(path(job,"/plans"),job,planner,200);
        savePlan(job,"SUBJECT_RESOLUTION","TSEG1",Map.of("mentions",List.of()));savePlan(job,"COVERAGE","TSEG1",batch("TSEG1",List.of()));
        var merged=new ArrayList<Object>();for(int i=0;i<drafts.size();i++)merged.add(Map.of("localRef","Q"+(i+1),"originalRefs",List.of("PLANNER:TSEG1:local-"+(i+1)),"state",Map.of("draft",drafts.get(i))));
        var plan=publication(job,"SETTING","MERGED_PLAN","PLAN",Map.of("questions",merged));plan.put("citations",List.of(cite(0,2,"처음")));
        var checks=new ArrayList<JsonNode>();internal(path(job,"/plans"),job,plan,200).path("checks").forEach(checks::add);
        var model=modelSettings(report);
        for(int i=0;i<checks.size();i++) {
            var check=checks.get(i);var question=questions.get(i);
            var decision=conflict(check,false);decision.put("impact",question.impact());
            @SuppressWarnings("unchecked") var bundle=(Map<String,Object>)decision.get("evidence_bundle");bundle.put("question",drafts.get(i));
            // 검증기는 질문 대상의 고정 기준 항목 투영 전체(ENTRIES.modelSettings)를 그대로 요구한다.
            var settings=new ArrayList<Object>();model.forEach((ref,setting)->{if(question.subjects().contains(setting.path("subject_ref").asText()))settings.add(json.convertValue(setting,Map.class));});
            bundle.put("settings",settings);
            var command=unit(job,"SETTING",check.path("localRef").asText(),"CONFLICT",decision);command.put("idempotencyKey","unit-"+check.path("localRef").asText());
            command.put("checkId",check.path("checkId").asText());command.put("expectedCheckRevision",check.path("revision").asLong());command.put("checkInputDigest",check.path("checkInputDigest").asText());
            command.put("citations",List.of(cite(0,2,"처음"),cite(0,8,"처음 합성 원고"),priorCite()));internal(path(job,"/units"),job,command,200);
        }
        typos(job);assertThat(finish(job,"done")).isEqualTo("COMPLETED");return report;
    }
    Map<String,JsonNode> modelSettings(UUID report) throws Exception {var r=stored(get(report));return dataMapper.toValidationSettings(r.getBaselineEntries(),dataMapper.toSourceReferences(r));}
    JsonNode row(JsonNode page,String unitRef) {for(var row:page.path("content"))if(unitRef.equals(row.path("unitRef").asText()))return row;throw new AssertionError(unitRef);}
    List<String> units(JsonNode page) {var out=new ArrayList<String>();page.path("content").forEach(r->out.add(r.path("unitRef").asText()));return out;}
    JsonNode error(String query) throws Exception {return read(query,token,400);}

    @Test @DisplayName("목록·상세 설정 항목은 고정 기준의 대상과 정확히 맞는 설정명을 담고 오탈자는 null이다")
    void findingsCarrySubjectAndSettingName() throws Exception {
        // schema 표시명은 기존 캐릭터 설정 표시명 규칙으로 해석한다(실제 seed와 같은 age → 나이).
        em.persist(CharacterSettingSchema.create(work,"age",null,"나이",CharacterFactType.AGE,SettingValueType.NUMBER,CharacterSettingValueSemantics.BASE_VALUE,
                CharacterSettingMergePolicy.REPLACE,com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode(),CharacterSettingSchemaSource.SYSTEM_SEED,true));em.flush();
        var report=completedWith(List.of(new Question(List.of("S1"),"나이","CAUTION"),new Question(List.of("S2"),"서식지","SEVERE"),new Question(List.of("S1"),"손 사용","REFERENCE"),
                new Question(List.of(),"age","CAUTION")));
        var page=findings(report,"?branch=SETTING");
        var age=row(page,"Q1");
        assertThat(age.path("subject").path("type").asText()).isEqualTo("CHARACTER");assertThat(age.path("subject").path("id").asText()).isEqualTo(character.getId().toString());
        assertThat(age.path("subject").path("name").asText()).isEqualTo("합성 인물");assertThat(age.path("settingName").asText()).isEqualTo("나이");assertThat(age.path("settingKey").asText()).isEqualTo("AGE:age");
        var habitat=row(page,"Q2");
        assertThat(habitat.path("subject").path("type").asText()).isEqualTo("WORLD_SETTING");assertThat(habitat.path("subject").path("id").asText()).isEqualTo(world.getId().toString());
        assertThat(habitat.path("subject").path("name").asText()).isEqualTo("합성 종족");assertThat(habitat.path("settingName").asText()).isEqualTo("서식지");
        assertThat(habitat.path("settingKey").asText()).isEqualTo("[null,\"서식지\"]");
        var free=row(page,"Q3");
        assertThat(free.path("subject").path("name").asText()).as("subject without a matching slot").isEqualTo("합성 인물");
        assertThat(free.path("settingName").isNull()).isTrue();assertThat(free.path("settingKey").isNull()).as("free text is never guessed into a slot").isTrue();
        var none=row(page,"Q4");
        assertThat(none.path("subject").isNull()).as("no subject_refs and no BASELINE entry").isTrue();assertThat(none.path("settingName").isNull()).isTrue();
        for(var typo:findings(report,"?branch=TYPO").path("content")) {assertThat(typo.path("subject").isNull()).isTrue();assertThat(typo.path("settingKey").isNull()).isTrue();}
        var detail=detail(report,age);
        assertThat(detail.path("subject")).isEqualTo(age.path("subject"));assertThat(detail.path("settingName").asText()).isEqualTo("나이");assertThat(detail.path("settingKey").asText()).isEqualTo("AGE:age");
        // 저장된 값은 비정규화 컬럼이며 이후 현재 설정 변경으로 다시 계산하지 않는다.
        var columns=em.createQuery("select f.impact,f.subjectType,f.subjectId,f.subjectName,f.settingKey,f.settingName from ValidationFinding f where f.id=:id",Object[].class)
                .setParameter("id",UUID.fromString(age.path("findingId").asText())).getSingleResult();
        assertThat(Arrays.asList(columns)).containsExactly(org.monitoring.catchholebackend.domain.validation.type.ValidationImpact.CAUTION,
                org.monitoring.catchholebackend.domain.validation.type.ValidationSubjectType.CHARACTER,character.getId(),"합성 인물","AGE:age","나이");
        noExtraction();
    }

    @Test @DisplayName("impact·subjectType 필터는 SQL로 거르고 기존 필터와 함께 쓰이며 검토 요약 집계는 바뀌지 않는다")
    void filtersByImpactAndSubjectType() throws Exception {
        var report=completedWith(List.of(new Question(List.of("S1"),"age","CAUTION"),new Question(List.of("S2"),"서식지","SEVERE"),new Question(List.of("S1"),"손 사용","SEVERE")));
        assertThat(units(findings(report,"?impact=SEVERE"))).containsExactly("Q2","Q3");
        assertThat(findings(report,"?impact=SEVERE").path("totalElements").asLong()).isEqualTo(2);
        assertThat(units(findings(report,"?subjectType=CHARACTER"))).containsExactly("Q1","Q3");
        assertThat(units(findings(report,"?subjectType=WORLD_SETTING&impact=SEVERE"))).containsExactly("Q2");
        assertThat(units(findings(report,"?subjectType=CHARACTER&impact=SEVERE&judgment=PENDING&outcome=CONFLICT"))).containsExactly("Q3");
        assertThat(findings(report,"?branch=TYPO&impact=CAUTION").path("totalElements").asLong()).as("typo findings have no impact").isZero();
        assertThat(findings(report,"?impact=CAUTION&size=1&page=0").path("totalPages").asInt()).isEqualTo(1);
        assertThat(findings(report,"").path("totalElements").asLong()).isEqualTo(5);
        assertThat(code(error("/"+report+"/findings?impact=HIGH"))).isEqualTo("REQUEST_INVALID_ARGUMENT");
        assertThat(code(error("/"+report+"/findings?subjectType=PLACE"))).isEqualTo("REQUEST_INVALID_ARGUMENT");
        var counts=review(report).path("counts");
        assertThat(counts.path("settingTotal").asLong()).isEqualTo(3);assertThat(counts.path("typoTotal").asLong()).isEqualTo(2);
        noExtraction();
    }

    @Test @DisplayName("캐릭터 finding 상세의 basis와 단건 조회 기준값만으로 만든 정정 요청이 성공한다")
    void characterBasisRoundTripsIntoCorrection() throws Exception {
        var report=completedWith(List.of(new Question(List.of("S1"),"age","CAUTION")));
        var detail=detail(report,only(report,"SETTING"));
        assertThat(detail.path("basis").size()).isEqualTo(1);var basis=detail.path("basis").path(0);
        assertThat(basis.path("entryRef").asText()).isEqualTo("B1");assertThat(basis.path("subjectType").asText()).isEqualTo("CHARACTER");
        assertThat(basis.path("targetId").asText()).isEqualTo(character.getId().toString());assertThat(basis.path("subjectName").asText()).isEqualTo("합성 인물");
        assertThat(basis.path("factType").asText()).isEqualTo("AGE");assertThat(basis.path("factKey").asText()).isEqualTo("age");
        assertThat(basis.path("currentVersion").asLong()).isEqualTo(character.getSnapshotVersion());
        assertThat(basis.path("entryHash").asText()).isEqualTo(journal.hash(stored(get(report)).getBaselineEntries().get(0)));
        assertThat(basis.path("scopeName").isNull()&&basis.path("settingName").isNull()).isTrue();
        var staging=get(report);
        Map<String,Object> command=new LinkedHashMap<>();command.put("targetId",basis.path("targetId").asText());command.put("expectedSnapshotVersion",basis.path("currentVersion").asLong());
        command.put("factType",basis.path("factType").asText());command.put("factKey",basis.path("factKey").asText());command.put("edit",characterEdit(21,1));
        var receipt=postOk("/"+report+"/corrections",correctionFrom(staging,basis,"basis-character",Map.of("character",command)));
        assertThat(receipt.path("correctionId").isTextual()).isTrue();em.refresh(character);assertThat(character.getCurrentAge()).isEqualTo(21);
        noExtraction();
    }

    @Test @DisplayName("세계관 finding 상세의 basis 경로·version으로 만든 정정 요청이 성공한다")
    void worldBasisRoundTripsIntoCorrection() throws Exception {
        var report=completedWith(List.of(new Question(List.of("S2"),"서식지","SEVERE")));
        var basis=detail(report,only(report,"SETTING")).path("basis").path(0);
        assertThat(basis.path("entryRef").asText()).isEqualTo("B2");assertThat(basis.path("subjectType").asText()).isEqualTo("WORLD_SETTING");
        assertThat(basis.path("scopeName").isNull()).isTrue();assertThat(basis.path("settingName").asText()).isEqualTo("서식지");
        assertThat(basis.path("factType").isNull()).isTrue();assertThat(basis.path("currentVersion").asLong()).isEqualTo(world.getVersion());
        var edit=new LinkedHashMap<String,Object>();edit.put("currentScopeName",basis.path("scopeName").isNull()?null:basis.path("scopeName").asText());
        edit.put("currentSettingName",basis.path("settingName").asText());edit.put("settingName",basis.path("settingName").asText());edit.put("settingValue","남부");edit.put("version",basis.path("currentVersion").asLong());
        Map<String,Object> worldSetting=Map.of("targetId",basis.path("targetId").asText(),"expectedVersion",basis.path("currentVersion").asLong(),"edit",edit);
        postOk("/"+report+"/corrections",correctionFrom(get(report),basis,"basis-world",Map.of("worldSetting",worldSetting)));
        assertThat(world.getPropertyValue("서식지")).isEqualTo("남부");noExtraction();
    }

    @Test @DisplayName("질문 속성이 slot과 맞지 않아도 basis는 대상 항목을 주고 slot은 비워 둔다")
    void basisWithoutMatchingSlotLeavesSlotEmpty() throws Exception {
        var report=completedWith(List.of(new Question(List.of("S1","S2"),"손 사용","CAUTION")));
        var detail=detail(report,only(report,"SETTING"));
        assertThat(detail.path("subject").path("type").asText()).as("first subject_ref").isEqualTo("CHARACTER");
        assertThat(detail.path("basis").size()).isEqualTo(2);
        for(var basis:detail.path("basis")) {assertThat(basis.path("factType").isNull()&&basis.path("factKey").isNull()&&basis.path("settingName").isNull()).isTrue();assertThat(basis.path("entryHash").asText()).hasSize(64);}
        var refs=new ArrayList<String>();detail.path("basis").forEach(b->refs.add(b.path("entryRef").asText()));assertThat(refs).containsExactly("B1","B2");
        var typo=detail(report,only(report,"TYPO"));assertThat(typo.path("basis").isArray()&&typo.path("basis").isEmpty()).isTrue();assertThat(typo.path("subject").isNull()).isTrue();
    }

    Map<String,Object> correctionFrom(JsonNode staging,JsonNode basis,String key,Map<String,Object> target) {
        var input=new LinkedHashMap<String,Object>();
        input.put("idempotencyKey",key);input.put("expectedReportVersion",staging.path("reportVersion").asLong());input.put("baselineId",staging.path("baselineId").asText());
        input.put("expectedBaselineRevision",staging.path("baselineRevision").asLong());input.put("expectedBaselineHash",staging.path("baselineHash").asText());
        input.put("entryRef",basis.path("entryRef").asText());input.put("expectedEntryHash",basis.path("entryHash").asText());input.putAll(target);return input;
    }
}
