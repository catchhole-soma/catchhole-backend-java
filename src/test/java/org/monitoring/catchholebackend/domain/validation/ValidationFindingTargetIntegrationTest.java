package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.type.*;
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
    /** 질문 하나의 subject_refs·property_or_rule·영향 등급과 판정 근거 문장(basis_quotes, 없으면 넣지 않는다). */
    record Question(List<String> subjects,String property,String impact,List<?> quotes) {
        Question(List<String> subjects,String property,String impact) {this(subjects,property,impact,List.of());}
    }

    UUID completedWith(List<Question> questions) throws Exception {return completedWith(questions,false);}
    /** 질문마다 SETTING finding을 저장하고 오탈자 2건과 함께 실행을 끝낸다. legacy면 #381 이전 seed report로 실행한다. */
    UUID completedWith(List<Question> questions,boolean legacy) throws Exception {
        var job=legacy?beginLegacy():begin();var report=reportId(job);
        var drafts=new ArrayList<Map<String,Object>>();
        for(int i=0;i<questions.size();i++) {var d=draft("local-"+(i+1),List.of(SPAN));d.put("subject_refs",questions.get(i).subjects());d.put("property_or_rule",questions.get(i).property());drafts.add(d);}
        var planner=publication(job,"SETTING","PLANNER","TSEG1",batch("TSEG1",drafts));planner.put("citations",List.of(cite(0,2,"처음")));internal(path(job,"/plans"),job,planner,200);
        var merged=new ArrayList<Object>();for(int i=0;i<drafts.size();i++)merged.add(Map.of("localRef","Q"+(i+1),"originalRefs",List.of("PLANNER:TSEG1:local-"+(i+1)),"state",Map.of("draft",drafts.get(i))));
        var plan=publication(job,"SETTING","MERGED_PLAN","PLAN",Map.of("questions",merged));plan.put("citations",List.of(cite(0,2,"처음")));
        var checks=new ArrayList<JsonNode>();internal(path(job,"/plans"),job,plan,200).path("checks").forEach(checks::add);
        var model=modelSettings(report);
        for(int i=0;i<checks.size();i++) {
            var check=checks.get(i);var question=questions.get(i);
            var decision=conflict(check,false);decision.put("impact",question.impact());if(!question.quotes().isEmpty())decision.put("basis_quotes",question.quotes());
            @SuppressWarnings("unchecked") var bundle=(Map<String,Object>)decision.get("evidence_bundle");bundle.put("question",drafts.get(i));
            // 검증기는 질문 대상의 고정 기준 항목 전부를 entry_ref·subject_ref 행으로 요구한다(GH349, 내용 사본은 받지 않아도 된다).
            var settings=new ArrayList<Object>();model.forEach((ref,setting)->{if(question.subjects().contains(setting.path("subject_ref").asText()))settings.add(Map.of("entry_ref",ref,"subject_ref",setting.path("subject_ref").asText()));});
            bundle.put("settings",settings);
            var command=unit(job,"SETTING",check.path("localRef").asText(),"CONFLICT",decision);command.put("idempotencyKey","unit-"+check.path("localRef").asText());
            command.put("checkId",check.path("checkId").asText());command.put("expectedCheckRevision",check.path("revision").asLong());command.put("checkInputDigest",check.path("checkInputDigest").asText());
            command.put("citations",List.of(cite(0,2,"처음"),cite(0,8,"처음 합성 원고"),priorCite()));internal(path(job,"/units"),job,command,200);
        }
        typos(job);assertThat(finish(job,"done")).isEqualTo("COMPLETED");return report;
    }
    static Map<String,Object> settingQuote(String ref,String quote) {var q=new LinkedHashMap<String,Object>();q.put("ref",ref);q.put("source_ref",null);q.put("start",null);q.put("end",null);q.put("quote",quote);return q;}
    /** fixture 판정 묶음의 이전 회차 조각 E1(R1 0~2 "합성")을 그대로 옮긴 원문 근거 문장. completedWith가 같은 citation을 보낸다. */
    static Map<String,Object> priorQuote() {var q=new LinkedHashMap<String,Object>();q.put("ref","E1");q.put("source_ref","R1");q.put("start",0);q.put("end",2);q.put("quote","합성");return q;}
    void ageSchema() {
        // schema 표시명은 기존 캐릭터 설정 표시명 규칙으로 해석한다(실제 seed와 같은 age → 나이).
        em.persist(CharacterSettingSchema.create(work,"age",null,"나이",CharacterFactType.AGE,SettingValueType.NUMBER,CharacterSettingValueSemantics.BASE_VALUE,
                CharacterSettingMergePolicy.REPLACE,com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode(),CharacterSettingSchemaSource.SYSTEM_SEED,true));em.flush();
    }
    /** 현재 확정 설정에서 주체 하나(B 참조)의 slot 표시값. */
    String liveSlotValue(String entryRef,String slot) {
        var index=liveSettings.index(work.getId());int number=Integer.parseInt(entryRef.substring(1));
        return liveSettings.entries(index,List.of(entryRef)).get(number-1).path("state").path("slots").path(slot).path("factValue").asText();
    }
    /** SETTING finding unitRef → quoted_subjects 열. 오탈자 finding은 "TYPO:<start>"이다. */
    Map<String,JsonNode> quoted(UUID report) {
        var result=new HashMap<String,JsonNode>();
        for(var f:em.createQuery("select f from ValidationFinding f where f.report.id=:r",org.monitoring.catchholebackend.domain.validation.entity.ValidationFinding.class).setParameter("r",report).getResultList())
            result.put(f.getBranch()==org.monitoring.catchholebackend.domain.validation.type.ValidationBranch.TYPO?"TYPO:"+f.getPayload().path("span").path("start").asInt():f.getUnitRef(),f.getQuotedSubjects());
        return result;
    }
    org.hibernate.stat.Statistics statistics() {return em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();}
    long loads(Class<?> type) {return statistics().getEntityStatistics(type.getName()).getLoadCount();}
    static final List<Class<?>> NEVER_LOADED=List.of(org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.class,
            org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate.class,org.monitoring.catchholebackend.domain.character.entity.CharacterFact.class,
            org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource.class,org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.class);
    /** 주체 하나를 대상으로 한 질문을 계획까지 게시하고 서버 check를 돌려준다. */
    JsonNode plannedFor(JsonNode job,Map<String,Object> draft) throws Exception {
        var planner=publication(job,"SETTING","PLANNER","TSEG1",batch("TSEG1",List.of(draft)));planner.put("citations",List.of(cite(0,2,"처음")));internal(path(job,"/plans"),job,planner,200);
        var plan=publication(job,"SETTING","MERGED_PLAN","PLAN",Map.of("questions",List.of(Map.of("localRef","Q1","originalRefs",List.of("PLANNER:TSEG1:local-1"),"state",Map.of("draft",draft)))));
        plan.put("citations",List.of(cite(0,2,"처음")));return internal(path(job,"/plans"),job,plan,200).path("checks").path(0);
    }
    /** check 하나의 CONFLICT 판정 unit. settings는 묶음 설정 행(entry_ref·subject_ref), quotes는 판정 근거 문장이다. */
    Map<String,Object> unitFor(JsonNode job,JsonNode check,Map<String,Object> draft,List<?> settings,List<?> quotes,String key) {
        var decision=conflict(check,false);
        @SuppressWarnings("unchecked") var bundle=(Map<String,Object>)decision.get("evidence_bundle");bundle.put("question",draft);bundle.put("settings",settings);
        if(!quotes.isEmpty())decision.put("basis_quotes",quotes);
        var command=unit(job,"SETTING","Q1","CONFLICT",decision);command.put("idempotencyKey",key);
        command.put("checkId",check.path("checkId").asText());command.put("expectedCheckRevision",check.path("revision").asLong());command.put("checkInputDigest",check.path("checkInputDigest").asText());
        command.put("citations",List.of(cite(0,2,"처음"),cite(0,8,"처음 합성 원고"),priorCite()));return command;
    }
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

    @Test @DisplayName("#381 live 게시는 B 인용마다 주체·설정 이름표(quoted_subjects)를 이름·key로만 쓰고 원문 인용은 건너뛰며 근거가 없으면 빈 배열, 오탈자는 null이다")
    void liveFindingsRecordQuotedSubjects() throws Exception {
        ageSchema();String age=liveSlotValue("B1","AGE:age");
        var report=completedWith(List.of(new Question(List.of("S1","S2"),"서식지","SEVERE",List.of(settingQuote("B2","북"),priorQuote(),settingQuote("B1",age))),
                new Question(List.of("S1"),"age","CAUTION")));
        assertThat(stored(get(report)).getBaselineSeed()).as("live report").isNull();
        var expected=json.createArrayNode();
        expected.addObject().put("ref","B2").put("subjectType","WORLD_SETTING").put("subjectId",world.getId().toString()).put("subjectName","합성 종족")
                .put("settingKey","[null,\"서식지\"]").put("settingName","서식지");
        expected.addObject().put("ref","B1").put("subjectType","CHARACTER").put("subjectId",character.getId().toString()).put("subjectName","합성 인물")
                .put("settingKey","AGE:age").put("settingName","나이");
        var columns=quoted(report);
        assertThat(columns.get("Q1")).as("one label per setting quote, in quote order").isEqualTo(expected);
        assertThat(columns.get("Q1").toString()).as("names and keys only, never the setting value").doesNotContain("북부");
        assertThat(columns.get("Q2")).as("no setting quote").isEqualTo(json.createArrayNode());
        assertThat(columns.get("TYPO:0")).isNull();assertThat(columns.get("TYPO:6")).isNull();
        // 대표 대상(V102)은 그대로 첫 B 인용의 항목이다.
        var q1=row(findings(report,"?branch=SETTING"),"Q1");
        assertThat(q1.path("subject").path("name").asText()).isEqualTo("합성 종족");assertThat(q1.path("settingKey").asText()).isEqualTo("[null,\"서식지\"]");
        noExtraction();
    }

    @Test @DisplayName("#381 legacy(seed) report의 게시는 저장 기준 항목으로 같은 대상·설정명을 기록하고 quoted_subjects는 null로 둔다")
    void legacyFindingsKeepStoredBaselineTargets() throws Exception {
        ageSchema();
        var report=completedWith(List.of(new Question(List.of("S1"),"나이","CAUTION"),new Question(List.of("S1","S2"),"서식지","SEVERE",List.of(settingQuote("B2","북"))),
                new Question(List.of("S1"),"손 사용","REFERENCE")),true);
        assertThat(stored(get(report)).getBaselineSeed()).as("legacy report").isNotNull();
        var page=findings(report,"?branch=SETTING");
        var age=row(page,"Q1");
        assertThat(age.path("subject").path("id").asText()).isEqualTo(character.getId().toString());assertThat(age.path("subject").path("name").asText()).isEqualTo("합성 인물");
        assertThat(age.path("settingKey").asText()).isEqualTo("AGE:age");assertThat(age.path("settingName").asText()).isEqualTo("나이");
        var habitat=row(page,"Q2");
        assertThat(habitat.path("subject").path("id").asText()).as("first setting quote").isEqualTo(world.getId().toString());
        assertThat(habitat.path("settingKey").asText()).isEqualTo("[null,\"서식지\"]");assertThat(habitat.path("settingName").asText()).isEqualTo("서식지");
        var free=row(page,"Q3");assertThat(free.path("subject").path("name").asText()).isEqualTo("합성 인물");assertThat(free.path("settingKey").isNull()).isTrue();
        assertThat(quoted(report)).hasSize(5).allSatisfy((unit,column)->assertThat(column).as(unit).isNull());
        noExtraction();
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={0,30})
    @DisplayName("#381 live 판정 게시는 질문·근거 문장·묶음 설정이 가리키는 주체만 id로 읽고 후보·Fact·Job 엔티티를 읽지 않으며 SQL 수가 작품 크기와 무관하다")
    void livePublicationReadsOnlyNeededSubjects(int extraCharacters) throws Exception {
        ageSchema();
        for(int i=0;i<extraCharacters;i++)em.persist(org.monitoring.catchholebackend.domain.character.entity.WorkCharacter.create(work,"추가 인물 "+i,null,30+i,1,null,null,null,null,null,null));
        em.flush();
        var job=begin();var report=reportId(job);
        var index=liveSettings.index(work.getId());assertThat(index.subjects()).hasSize(extraCharacters+2);
        var subject=index.subjects().stream().filter(x->x.id().equals(character.getId())).findFirst().orElseThrow();
        var other=index.subjects().stream().filter(x->x.id().equals(world.getId())).findFirst().orElseThrow();
        var d=draft("local-1",List.of(SPAN));d.put("subject_refs",List.of(subject.subjectRef()));
        var check=plannedFor(job,d);String age=liveSlotValue(subject.entryRef(),"AGE:age");
        var command=unitFor(job,check,d,List.of(Map.of("entry_ref",subject.entryRef(),"subject_ref",subject.subjectRef())),List.of(settingQuote(subject.entryRef(),age)),"unit-loads");
        // 값을 대조하지 않는 자유 문장에 다른 주체 참조처럼 보이는 글이 있어도 그 주체는 읽지 않는다.
        @SuppressWarnings("unchecked") var payload=(Map<String,Object>)command.get("payload");payload.put("impact_reason",other.entryRef());
        em.flush();em.clear();
        var statistics=statistics();boolean enabled=statistics.isStatisticsEnabled();statistics.setStatisticsEnabled(true);statistics.clear();
        try {
            internal(path(job,"/units"),job,command,200);
            assertThat(loads(org.monitoring.catchholebackend.domain.character.entity.WorkCharacter.class)).isEqualTo(1);
            assertThat(loads(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.class)).isZero();
            for(var forbidden:NEVER_LOADED)assertThat(loads(forbidden)).as(forbidden.getSimpleName()).isZero();
            assertThat(statistics.getPrepareStatementCount()).as("statements of one live unit publication").isEqualTo(LIVE_UNIT_STATEMENTS);
        } finally {statistics.setStatisticsEnabled(enabled);}
        var expected=json.createArrayNode();
        expected.addObject().put("ref",subject.entryRef()).put("subjectType","CHARACTER").put("subjectId",character.getId().toString()).put("subjectName","합성 인물")
                .put("settingKey","AGE:age").put("settingName","나이");
        assertThat(quoted(report).get("Q1")).isEqualTo(expected);
    }
    /**
     * 판정 unit 게시 한 번 전체(세 트랜잭션의 lease·Work 잠금·설정 버전 지문 확인, INPUT·READ_SOURCE·VALIDATE 읽기, check·stage·receipt 조회, finding·근거 저장)의
     * SQL 수. 설정 읽기는 색인 scalar 3번과 요청 주체의 id 조회뿐이라 주체가 2개든 32개든 같다.
     */
    static final long LIVE_UNIT_STATEMENTS=106;

    @Test @DisplayName("#381 live 게시가 전체 상태로 읽을 주체가 100개를 넘으면 읽기 전에 거절하고, 100개까지는 읽어서 검증한다")
    void livePublicationNeedingMoreThanHundredSubjectsIsRejectedBeforeReading() throws Exception {
        for(int i=0;i<100;i++)em.persist(org.monitoring.catchholebackend.domain.character.entity.WorkCharacter.create(work,"추가 인물 "+i,null,30+i,1,null,null,null,null,null,null));
        em.flush();
        var job=begin();
        var index=liveSettings.index(work.getId());assertThat(index.subjects()).hasSize(102);
        var subject=index.subjects().stream().filter(x->x.id().equals(character.getId())).findFirst().orElseThrow();
        var others=index.subjects().stream().filter(x->!x.equals(subject)&&x.domain().equals("characters")).toList();
        var d=draft("local-1",List.of(SPAN));d.put("subject_refs",List.of(subject.subjectRef()));
        var check=plannedFor(job,d);
        for(int count:List.of(100,101)) {
            // 질문 대상 하나 + 다른 인물 count-1명을 묶음 설정 행으로 보낸다(서로 다른 주체 count개).
            var settings=new ArrayList<Object>();settings.add(Map.of("entry_ref",subject.entryRef(),"subject_ref",subject.subjectRef()));
            for(var x:others.subList(0,count-1))settings.add(Map.of("entry_ref",x.entryRef(),"subject_ref",x.subjectRef()));
            var command=unitFor(job,check,d,settings,List.of(),"unit-"+count);
            em.flush();em.clear();
            var statistics=statistics();boolean enabled=statistics.isStatisticsEnabled();statistics.setStatisticsEnabled(true);statistics.clear();
            try {
                assertThat(code(internal(path(job,"/units"),job,command,400))).as(count+" subjects").isEqualTo("VALIDATION_RESULT_INVALID");
                assertThat(loads(org.monitoring.catchholebackend.domain.character.entity.WorkCharacter.class)).as(count+" subjects").isEqualTo(count==100?100:0);
            } finally {statistics.setStatisticsEnabled(enabled);}
        }
        assertThat(count("validation_findings",reportId(job))).isZero();
    }

    Map<String,Object> correctionFrom(JsonNode staging,JsonNode basis,String key,Map<String,Object> target) {
        var input=new LinkedHashMap<String,Object>();
        input.put("idempotencyKey",key);input.put("expectedReportVersion",staging.path("reportVersion").asLong());input.put("baselineId",staging.path("baselineId").asText());
        input.put("expectedBaselineRevision",staging.path("baselineRevision").asLong());input.put("expectedBaselineHash",staging.path("baselineHash").asText());
        input.put("entryRef",basis.path("entryRef").asText());input.put("expectedEntryHash",basis.path("entryHash").asText());input.putAll(target);return input;
    }
}
