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
 * 오류 리포트 가독성(2026-11-02): 판정 근거 문장(basis_quotes)의 서버 검증과 상세 화면용 표기(topic·회차 번호·근거 label·사용한 조각).
 * 고정 기준은 fixture의 캐릭터(B1=S1 "합성 인물")와 세계관(B2=S2 "합성 종족", 서식지=북부), 이전 회차 R1=100화("합성 앞 원고"), 이번 원고 101화다.
 */
@SpringBootTest(properties="spring.config.import=") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("오류 리포트 가독성: 판정 근거 문장 검증과 상세 표기")
class ValidationFindingReadabilityIntegrationTest extends ValidationReviewFixture {
    static final Map<String,Object> CHUNK=Map.of("ref","E1","source_ref","R1","source_kind","EPISODE","start",0,"end",7,"quote","합성 앞 원고","method","VECTOR","source_context",Map.of("episodeNo",100));

    Map<String,Object> priorChunkCite(int start,int end,String quote) {return Map.of("sourceRef","R1","sourceHash",priorHash,"sourceVersion",prior.getContentS3Version(),"start",start,"end",end,"quote",quote);}
    static Map<String,Object> eQuote(int start,int end,String quote) {var q=new LinkedHashMap<String,Object>();q.put("ref","E1");q.put("source_ref","R1");q.put("start",start);q.put("end",end);q.put("quote",quote);return q;}
    static Map<String,Object> bQuote(String ref,String quote) {var q=new LinkedHashMap<String,Object>();q.put("ref",ref);q.put("source_ref",null);q.put("start",null);q.put("end",null);q.put("quote",quote);return q;}

    record Published(JsonNode job,UUID report,List<JsonNode> checks) {}

    /** 질문 하나(subject_refs·property_or_rule)를 계획까지 게시한다. */
    Published planned(List<String> subjects,String property) throws Exception {
        var job=begin();var report=reportId(job);
        var d=draft("local-1",List.of(SPAN));d.put("subject_refs",subjects);d.put("property_or_rule",property);
        var planner=publication(job,"SETTING","PLANNER","TSEG1",batch("TSEG1",List.of(d)));planner.put("citations",List.of(cite(0,2,"처음")));internal(path(job,"/plans"),job,planner,200);
        var plan=publication(job,"SETTING","MERGED_PLAN","PLAN",Map.of("questions",List.of(Map.of("localRef","Q1","originalRefs",List.of("PLANNER:TSEG1:local-1"),"state",Map.of("draft",d)))));
        plan.put("citations",List.of(cite(0,2,"처음")));
        var checks=new ArrayList<JsonNode>();internal(path(job,"/plans"),job,plan,200).path("checks").forEach(checks::add);
        return new Published(job,report,checks);
    }
    /** 질문 대상의 설정 행과 이전 회차 조각 E1을 묶은 CONFLICT 판정 단위. */
    Map<String,Object> command(Published p,List<String> subjects,String property,List<?> quotes,List<?> extraCitations) throws Exception {
        var check=p.checks().getFirst();var decision=conflict(check,false);
        @SuppressWarnings("unchecked") var bundle=(Map<String,Object>)decision.get("evidence_bundle");
        @SuppressWarnings("unchecked") var question=new LinkedHashMap<String,Object>((Map<String,Object>)bundle.get("question"));
        question.put("subject_refs",subjects);question.put("property_or_rule",property);bundle.put("question",question);
        var settings=new ArrayList<Object>();modelSettings(p.report()).forEach((ref,row)->{if(subjects.contains(row.path("subject_ref").asText()))settings.add(Map.of("entry_ref",ref,"subject_ref",row.path("subject_ref").asText()));});
        bundle.put("settings",settings);bundle.put("evidence",List.of(CHUNK));
        decision.put("evidence_refs",List.of("T1","E1"));decision.put("basis_quotes",quotes);
        var command=unit(p.job(),"SETTING","Q1","CONFLICT",decision);
        command.put("checkId",check.path("checkId").asText());command.put("expectedCheckRevision",check.path("revision").asLong());command.put("checkInputDigest",check.path("checkInputDigest").asText());
        var citations=new ArrayList<Object>(List.of(cite(0,2,"처음"),cite(0,8,"처음 합성 원고"),priorChunkCite(0,7,"합성 앞 원고")));citations.addAll(extraCitations);command.put("citations",citations);
        return command;
    }
    String firstFactValue(UUID report,String entryRef) throws Exception {
        for(var slot:modelSettings(report).get(entryRef).path("state").path("slots").properties())if(slot.getValue().path("factValue").isTextual())return slot.getValue().path("factValue").asText();
        throw new AssertionError("no textual factValue");
    }

    @Test @DisplayName("세계관 설정값·이전 회차 근거 문장은 게시되고 상세는 내부 참조 없이 회차·설정 표기와 사용한 조각만 표시한다")
    void worldAndEpisodeQuotesBecomeReadableDetail() throws Exception {
        var p=planned(List.of("S2"),"서식지");
        var command=command(p,List.of("S2"),"서식지",List.of(bQuote("B2","북부"),eQuote(3,7,"앞 원고")),List.of(priorChunkCite(3,7,"앞 원고")));
        internal(path(p.job(),"/units"),p.job(),command,200);
        var row=only(p.report(),"SETTING");
        assertThat(row.path("topic").asText()).isEqualTo("서식지");
        var detail=detail(p.report(),row);
        assertThat(detail.path("topic").asText()).isEqualTo("서식지");
        assertThat(detail.path("targetEpisodeNo").asInt()).isEqualTo(101);
        var quotes=detail.path("basisQuotes");assertThat(quotes.size()).isEqualTo(2);
        assertThat(quotes.path(0).path("kind").asText()).isEqualTo("SETTING");assertThat(quotes.path(0).path("label").asText()).isEqualTo("설정 · 합성 종족 · 서식지");
        assertThat(quotes.path(0).path("quote").asText()).isEqualTo("북부");assertThat(quotes.path(0).path("sourceRef").isNull()).isTrue();
        assertThat(quotes.path(1).path("kind").asText()).isEqualTo("EPISODE");assertThat(quotes.path(1).path("label").asText()).isEqualTo("100화");
        assertThat(quotes.path(1).path("episodeNo").asInt()).isEqualTo(100);assertThat(quotes.path(1).path("start").asInt()).isEqualTo(3);assertThat(quotes.path(1).path("end").asInt()).isEqualTo(7);
        // 근거 문장 자체의 인용(R1 3~7)은 조각 목록에서 빠지고, 판정이 쓴 조각(R1 0~7)은 used·강조 구간과 함께 남는다.
        JsonNode chunk=null;var labels=new ArrayList<String>();
        for(var e:detail.path("evidence")) {
            labels.add(e.path("label").asText());
            assertThat(e.path("label").asText()).as("no internal refs").doesNotContainPattern("(?<![A-Za-z0-9])(T1|R[0-9]+|E[0-9]+|B[0-9]+)(?![0-9])");
            if("R1".equals(e.path("sourceRef").asText()))chunk=e;
            if("T1".equals(e.path("sourceRef").asText())) {assertThat(e.path("episodeNo").asInt()).isEqualTo(101);assertThat(e.path("usedByDecision").asBoolean()).isFalse();}
        }
        assertThat(chunk).isNotNull();
        assertThat(labels).contains("100화 · 1~7자","101화 · 1~2자").doesNotContain("100화 · 4~7자");
        assertThat(chunk.path("usedByDecision").asBoolean()).isTrue();assertThat(chunk.path("episodeNo").asInt()).isEqualTo(100);assertThat(chunk.path("sourceKind").asText()).isEqualTo("EPISODE");
        assertThat(chunk.path("highlights").size()).isEqualTo(1);assertThat(chunk.path("highlights").path(0).path("start").asInt()).isEqualTo(3);assertThat(chunk.path("highlights").path(0).path("end").asInt()).isEqualTo(7);
        // 공개 payload는 이전과 같은 필드만 옮기고 근거 문장은 서버 표기(basisQuotes)로만 준다.
        assertThat(detail.path("payload").has("basis_quotes")).isFalse();
        assertThat(stored(get(p.report())).getId()).isEqualTo(p.report());
        noExtraction();
    }

    @Test @DisplayName("캐릭터 slot 값 근거는 schema 표시명으로 표기하고 근거 문장이 없으면 빈 배열이다")
    void characterQuoteUsesSchemaDisplayName() throws Exception {
        em.persist(CharacterSettingSchema.create(work,"age",null,"나이",CharacterFactType.AGE,SettingValueType.NUMBER,CharacterSettingValueSemantics.BASE_VALUE,
                CharacterSettingMergePolicy.REPLACE,com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode(),CharacterSettingSchemaSource.SYSTEM_SEED,true));em.flush();
        var p=planned(List.of("S1"),"나이");var value=firstFactValue(p.report(),"B1");
        internal(path(p.job(),"/units"),p.job(),command(p,List.of("S1"),"나이",List.of(bQuote("B1",value)),List.of()),200);
        var detail=detail(p.report(),only(p.report(),"SETTING"));
        assertThat(detail.path("basisQuotes").path(0).path("label").asText()).isEqualTo("설정 · 합성 인물 · 나이");
        assertThat(detail.path("basisQuotes").path(0).path("settingName").asText()).isEqualTo("나이");
        assertThat(detail.path("basisQuotes").path(0).path("subjectName").asText()).isEqualTo("합성 인물");
    }

    @Test @DisplayName("이전 Worker의 판정(basis_quotes 없음)도 그대로 저장되고 상세의 근거 문장은 빈 배열이다")
    void legacyDecisionWithoutQuotes() throws Exception {
        var p=planned(List.of("S2"),"서식지");var command=command(p,List.of("S2"),"서식지",List.of(),List.of());
        @SuppressWarnings("unchecked") var payload=(Map<String,Object>)command.get("payload");payload.remove("basis_quotes");
        internal(path(p.job(),"/units"),p.job(),command,200);
        var row=only(p.report(),"SETTING");var detail=detail(p.report(),row);
        assertThat(detail.path("basisQuotes").isArray()&&detail.path("basisQuotes").isEmpty()).isTrue();
        // 판정 작성 제목(headline)도 없으므로 목록·상세 모두 null이고 화면이 대상·확인 항목으로 제목을 만든다.
        assertThat(row.path("headline").isNull()).isTrue();assertThat(detail.path("headline").isNull()).isTrue();
        // evidence_refs의 E1 조각은 판정이 쓴 조각이다.
        for(var e:detail.path("evidence"))if("R1".equals(e.path("sourceRef").asText()))assertThat(e.path("usedByDecision").asBoolean()).isTrue();
    }

    @Test @DisplayName("판정 작성 제목은 목록·상세의 headline으로 그대로 나오고 이전 결과·내부 참조처럼 보이는 제목은 null이다")
    void judgeHeadlineIsExposedOnListAndDetail() throws Exception {
        var p=planned(List.of("S2"),"서식지");var command=command(p,List.of("S2"),"서식지",List.of(bQuote("B2","북부")),List.of());
        @SuppressWarnings("unchecked") var payload=(Map<String,Object>)command.get("payload");payload.put("headline","합성 종족의 서식지가 기존 설정과 달라요");
        internal(path(p.job(),"/units"),p.job(),command,200);
        var row=only(p.report(),"SETTING");
        assertThat(row.path("headline").asText()).isEqualTo("합성 종족의 서식지가 기존 설정과 달라요");
        var detail=detail(p.report(),row);
        assertThat(detail.path("headline").asText()).isEqualTo("합성 종족의 서식지가 기존 설정과 달라요");
        assertThat(detail.path("payload").has("headline")).as("the title is a top-level field only").isFalse();
    }

    @Test @DisplayName("판정 작성 제목이 공백이거나 80 code point를 넘으면 거절하고, 저장된 제목이 내부 참조처럼 보이면 화면에 주지 않는다")
    void invalidOrInternalHeadlines() throws Exception {
        var p=planned(List.of("S2"),"서식지");
        for(var bad:List.<Object>of("   ","가".repeat(81),7)) {
            var command=command(p,List.of("S2"),"서식지",List.of(),List.of());command.put("idempotencyKey","bad-headline-"+bad.hashCode());
            @SuppressWarnings("unchecked") var payload=(Map<String,Object>)command.get("payload");payload.put("headline",bad);
            assertThat(code(internal(path(p.job(),"/units"),p.job(),command,400))).as(String.valueOf(bad)).isEqualTo("VALIDATION_RESULT_INVALID");
        }
        var command=command(p,List.of("S2"),"서식지",List.of(),List.of());
        @SuppressWarnings("unchecked") var payload=(Map<String,Object>)command.get("payload");payload.put("headline","B2의 서식지가 달라요");
        internal(path(p.job(),"/units"),p.job(),command,200);
        var row=only(p.report(),"SETTING");
        assertThat(row.path("headline").isNull()).isTrue();assertThat(detail(p.report(),row).path("headline").isNull()).isTrue();
    }

    @Test @DisplayName("finding 대상은 첫 저장 설정값 근거 문장의 항목이고, 그런 근거가 없으면 질문의 첫 대상이다")
    void subjectFollowsTheFirstSettingBasisQuote() throws Exception {
        // 질문 대상 [S1 합성 인물(서술자처럼 앞에 온 대상), S2 합성 종족]. 판정이 S2의 서식지 값을 옮겼다.
        var quoted=planned(List.of("S1","S2"),"서식지");
        internal(path(quoted.job(),"/units"),quoted.job(),command(quoted,List.of("S1","S2"),"서식지",List.of(bQuote("B2","북부")),List.of()),200);
        var row=only(quoted.report(),"SETTING");
        assertThat(row.path("subject").path("type").asText()).isEqualTo("WORLD_SETTING");
        assertThat(row.path("subject").path("id").asText()).isEqualTo(world.getId().toString());
        assertThat(row.path("subject").path("name").asText()).isEqualTo("합성 종족");
        assertThat(row.path("settingName").asText()).as("the property is matched against the quoted subject").isEqualTo("서식지");
        var detail=detail(quoted.report(),row);
        assertThat(detail.path("subject")).isEqualTo(row.path("subject"));
        JsonNode linked=null;for(var basis:detail.path("basis"))if("B2".equals(basis.path("entryRef").asText()))linked=basis;
        assertThat(linked).isNotNull();assertThat(linked.path("settingName").asText()).as("the correction link follows the same subject").isEqualTo("서식지");
        assertThat(findings(quoted.report(),"?subjectType=WORLD_SETTING").path("totalElements").asLong()).isEqualTo(1);
    }

    @Test @DisplayName("저장 설정값 근거 문장이 없으면(근거 없음·원문 인용뿐) finding 대상은 이전 규칙대로 질문의 첫 대상이다")
    void subjectFallsBackToTheFirstQuestionSubject() throws Exception {
        var none=planned(List.of("S1","S2"),"서식지");
        internal(path(none.job(),"/units"),none.job(),command(none,List.of("S1","S2"),"서식지",List.of(),List.of()),200);
        assertThat(only(none.report(),"SETTING").path("subject").path("name").asText()).isEqualTo("합성 인물");
    }

    @Test @DisplayName("이전 회차 원문 근거 문장만 있으면 finding 대상은 질문의 첫 대상이다")
    void episodeOnlyQuotesKeepTheFirstQuestionSubject() throws Exception {
        var episodeOnly=planned(List.of("S1","S2"),"서식지");
        internal(path(episodeOnly.job(),"/units"),episodeOnly.job(),command(episodeOnly,List.of("S1","S2"),"서식지",List.of(eQuote(3,7,"앞 원고")),List.of(priorChunkCite(3,7,"앞 원고"))),200);
        var row=only(episodeOnly.report(),"SETTING");
        assertThat(row.path("subject").path("name").asText()).isEqualTo("합성 인물");assertThat(row.path("subject").path("type").asText()).isEqualTo("CHARACTER");
    }

    @Test @DisplayName("내부 참조처럼 보이는 확인 항목은 topic을 비운다")
    void internalLookingTopicIsHidden() throws Exception {
        var p=planned(List.of("S2"),"E2와 서식지");
        internal(path(p.job(),"/units"),p.job(),command(p,List.of("S2"),"E2와 서식지",List.of(),List.of()),200);
        var row=only(p.report(),"SETTING");
        assertThat(row.path("topic").isNull()).isTrue();assertThat(detail(p.report(),row).path("topic").isNull()).isTrue();
    }

    @Test @DisplayName("설정값·조각과 맞지 않거나 인용 없는 근거 문장, 네 개 이상은 게시를 거절하고 아무것도 저장하지 않는다")
    void invalidQuotesAreRejected() throws Exception {
        var p=planned(List.of("S2"),"서식지");
        Map<String,List<?>[]> cases=new LinkedHashMap<>();
        cases.put("setting value mismatch",new List<?>[]{List.of(bQuote("B2","남부")),List.of()});
        cases.put("entry outside the bundle",new List<?>[]{List.of(bQuote("B1","20")),List.of()});
        cases.put("episode quote outside its chunk",new List<?>[]{List.of(eQuote(5,9,"원고 다음")),List.of()});
        cases.put("episode quote text differs",new List<?>[]{List.of(eQuote(3,7,"앞 원본")),List.of(priorChunkCite(3,7,"앞 원고"))});
        cases.put("episode quote without citation",new List<?>[]{List.of(eQuote(3,7,"앞 원고")),List.of()});
        cases.put("target reference",new List<?>[]{List.of(Map.of("ref","T1","quote","처음")),List.of()});
        cases.put("more than three",new List<?>[]{List.of(bQuote("B2","북부"),bQuote("B2","북"),bQuote("B2","부"),eQuote(3,7,"앞 원고")),List.of(priorChunkCite(3,7,"앞 원고"))});
        for(var e:cases.entrySet()) {
            var command=command(p,List.of("S2"),"서식지",e.getValue()[0],e.getValue()[1]);command.put("idempotencyKey","bad-"+e.getKey().hashCode());
            assertThat(code(internal(path(p.job(),"/units"),p.job(),command,400))).as(e.getKey()).isEqualTo("VALIDATION_RESULT_INVALID");
        }
        assertThat(findings(p.report(),"?branch=SETTING").path("totalElements").asLong()).isZero();
        // payload의 다른 객체에 근거 문장을 숨겨도 값 검사를 피할 수 없다.
        var hidden=command(p,List.of("S2"),"서식지",List.of(),List.of());hidden.put("idempotencyKey","hidden-basis");
        @SuppressWarnings("unchecked") var payload=(Map<String,Object>)hidden.get("payload");payload.put("note",Map.of("basis_quotes",List.of(bQuote("B2","남부"))));
        assertThat(code(internal(path(p.job(),"/units"),p.job(),hidden,400))).isEqualTo("VALIDATION_RESULT_INVALID");
        // 같은 질문은 올바른 판정으로 이어서 게시할 수 있다(거절은 아무것도 저장하지 않았다).
        internal(path(p.job(),"/units"),p.job(),command(p,List.of("S2"),"서식지",List.of(bQuote("B2","북부")),List.of()),200);
        assertThat(findings(p.report(),"?branch=SETTING").path("totalElements").asLong()).isEqualTo(1);
    }
}
