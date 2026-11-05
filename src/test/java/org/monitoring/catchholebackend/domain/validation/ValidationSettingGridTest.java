package org.monitoring.catchholebackend.domain.validation;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.validation.dto.ValidationExecutionContract.Scope;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationDataRequest;
import org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper;
import org.monitoring.catchholebackend.domain.validation.processor.*;
import org.monitoring.catchholebackend.domain.validation.service.ValidationDataService;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.global.exception.AppException;

/** 모든 원고·설정은 합성 값이다. #349 개편 SETTING grid v4의 구간 계산과 PLANNER 단계 검증 규칙만 본다. */
@DisplayName("검수 SETTING grid v4의 구간 나누기와 PLANNER 단계 검증 (#349 개편)")
class ValidationSettingGridTest {
    final ObjectMapper json=new ObjectMapper();final AnalysisStateJournal journal=new AnalysisStateJournal();final ValidationDataMapper mapper=new ValidationDataMapper();
    final ValidationPublicationValidator validator=new ValidationPublicationValidator(data(),new ValidationDecisionValidator(mapper,journal),mapper);
    String text;

    ValidationDataService data() {
        var service=mock(ValidationDataService.class);
        when(service.readValidationData(anyLong(),any(),any(),any(),any(),any())).thenAnswer(call->{
            var request=call.getArgument(3,ValidationDataRequest.class);
            if(request.operation().equals("INPUT")) {var input=json.createObjectNode().put("status","READY").put("entryCount",1);input.putObject("target").put("contentS3Version","v1");input.putArray("sources");return input;}
            return json.createObjectNode().put("status","READY").put("text",text).put("totalCodePoints",text.codePointCount(0,text.length()));
        });
        return service;
    }
    ValidationPublicationValidator.Prepared prepare(String target,int segmentChars,int contextChars) {
        text=target;var configuration=json.createObjectNode();var budgets=configuration.putObject("budgets");
        for(String name:List.of("setting","typo"))budgets.putObject(name).put("segmentChars",segmentChars).put("contextChars",contextChars);
        String hash="a".repeat(64);var entries=json.createArrayNode();entries.addObject().put("domain","characters").put("subjectRef","synthetic").putObject("state").put("name","민수");
        var context=new ValidationPublicationValidator.Context(UUID.randomUUID(),UUID.randomUUID(),new Scope(1,UUID.randomUUID(),1,hash),1L,UUID.randomUUID(),hash,hash,configuration,entries,Map.of());
        return validator.prepare(context,ValidationBranch.SETTING);
    }
    static int[] cps(String s) {return s.codePoints().toArray();}
    static List<Integer> cuts(String text,int unit) {var segments=ValidationPublicationValidator.settingSegments(cps(text),unit,0);var result=new ArrayList<Integer>();for(int i=1;i<segments.size();i++)result.add(segments.get(i).path("start").asInt());return result;}
    ObjectNode question(String local,int start,int end,String quote) {
        var q=json.createObjectNode().put("local_ref",local);q.putArray("target_spans").addObject().put("source_ref","T1").put("start",start).put("end",end).put("quote",quote);
        q.putArray("subject_refs").add("S1");q.putArray("subject_mentions").add("민수");q.put("property_or_rule","손").put("time_context","현재");q.putArray("conditions");
        return q.put("assertion_mode","NARRATION").put("check_intent","고정 설정과 대조").put("reason","합성 질문");
    }
    ObjectNode planner(String segment,String status,ObjectNode... questions) {
        var body=json.createObjectNode();var array=body.putArray("questions");for(var q:questions)array.add(q);
        body.putArray("segments").addObject().put("segment_ref",segment).put("status",status).put("reason","합성 처리");return body;
    }
    void invalid(Runnable call) {assertThatThrownBy(call::run).isInstanceOf(AppException.class).extracting("resultCode").isEqualTo(ValidationErrorCode.VALIDATION_RESULT_INVALID);}

    @Nested @DisplayName("구간 계산")
    class Segmentation {
        @Test @DisplayName("1만 자 이하는 한 구간, 넘으면 1만 자 기준 구간 수로 같은 길이로 나누고 단위 상한에 묶이지 않는다")
        void segmentCountFollowsTheUnitAndLengthsAreEqual() {
            assertThat(ValidationPublicationValidator.settingSegments(cps("가".repeat(10000)),10000,1000)).hasSize(1);
            var three=ValidationPublicationValidator.settingSegments(cps("가".repeat(25000)),10000,1000);
            assertThat(three).hasSize(3);
            assertThat(three.get(0).path("end").asInt()).isEqualTo(8333);assertThat(three.get(1).path("end").asInt()).isEqualTo(16666);
            assertThat(three.get(1).path("contextStart").asInt()).isEqualTo(8333-1000);assertThat(three.get(2).path("contextEnd").asInt()).isEqualTo(25000);
        }
        @Test @DisplayName("줄바꿈 바로 뒤에서 자르고 같은 거리면 앞쪽을, 창 안에 줄바꿈이 없으면 max(t, lo+1)을 쓴다")
        void cutsAfterNewlinesWithTieAndFallbackRules() {
            assertThat(cuts("가나다\n마\n사아자차",5)).containsExactly(4);
            assertThat(cuts("가나다라마바사아자차",4)).containsExactly(3,6);
            assertThat(cuts("😀😀\n😀😀",3)).containsExactly(3);
            String crlf="가나\r\n다라\r\n마바\r\n사아";var cut=cuts(crlf,7).getFirst();var points=cps(crlf);
            assertThat(points[cut-1]).isEqualTo('\n');assertThat(points[cut-2]).isEqualTo('\r');
        }
        @Test @DisplayName("임의 원고에서도 구간은 빈틈없이 이어지고 비지 않으며 창 안에서 잘린다")
        void randomTargetsKeepTheSafetyInvariants() {
            var random=new Random(349);String alphabet="가나다라마 \n\r😀a";int[] letters=alphabet.codePoints().toArray();
            for(int round=0;round<400;round++) {
                int length=1+random.nextInt(120),unit=1+random.nextInt(40),context=random.nextInt(16);var builder=new StringBuilder();
                for(int i=0;i<length;i++)builder.appendCodePoint(letters[random.nextInt(letters.length)]);
                int[] points=cps(builder.toString());var segments=ValidationPublicationValidator.settingSegments(points,unit,context);int n=(length+unit-1)/unit;
                assertThat(segments).hasSize(n);assertThat(segments.get(0).path("start").asInt()).isZero();assertThat(segments.get(n-1).path("end").asInt()).isEqualTo(length);
                for(int i=0;i<n;i++) {var s=segments.get(i);assertThat(s.path("ref").asText()).isEqualTo("TSEG"+(i+1));assertThat(s.path("start").asInt()).isLessThan(s.path("end").asInt());
                    assertThat(s.path("contextStart").asInt()).isEqualTo(Math.max(0,s.path("start").asInt()-context));assertThat(s.path("contextEnd").asInt()).isEqualTo(Math.min(length,s.path("end").asInt()+context));
                    if(i>0)assertThat(s.path("start").asInt()).isEqualTo(segments.get(i-1).path("end").asInt());}
                for(int k=1;k<n;k++) {long t=(long)k*length/n,lo=(2L*k-1)*length/(2L*n),hi=(2L*k+1)*length/(2L*n);int cut=segments.get(k).path("start").asInt();assertThat(cut).isGreaterThan((int)lo).isLessThanOrEqualTo((int)hi);}
            }
        }
        @Test @DisplayName("SETTING grid는 gridVersion 4·PLANNER 구간 단위·MERGED_PLAN만 담고 이전 grid의 주체·단서·설정집 키가 없다")
        void gridHasOnlyPlannerUnitsAndTheMergedPlan() {
            var grid=prepare("민수는 검을 쥐었다.\n그는 성문 앞에서 기다렸다.",10,2).grid();
            assertThat(grid.path("gridVersion").asInt()).isEqualTo(4);
            var names=new HashSet<String>();grid.fieldNames().forEachRemaining(names::add);assertThat(names).containsExactlyInAnyOrder("gridVersion","segments","units");
            var units=new ArrayList<String>();grid.path("units").forEach(u->units.add(u.path("stage").asText()+":"+u.path("ref").asText()));
            assertThat(units).containsExactly("PLANNER:TSEG1","PLANNER:TSEG2","PLANNER:TSEG3","MERGED_PLAN:PLAN");
            assertThat(ValidationPublicationValidator.currentSettingGrid(grid)).isTrue();
            assertThat(ValidationPublicationValidator.currentSettingGrid(json.createObjectNode().put("gridVersion",3))).isFalse();
        }
        @Test @DisplayName("저장 grid와 다시 계산한 grid가 다르면 엄격하게 충돌로 거절한다")
        void extendGridIsStrict() {
            var p=prepare("민수는 검을 쥐었다.",100,10);
            assertThatCode(()->validator.extendGrid(p,p.grid().deepCopy())).doesNotThrowAnyException();
            var changed=(ObjectNode)p.grid().deepCopy();changed.put("gridVersion",3);
            assertThatThrownBy(()->validator.extendGrid(p,changed)).isInstanceOf(AppException.class).extracting("resultCode").isEqualTo(ValidationErrorCode.VALIDATION_SOURCE_VERSION_CONFLICT);
        }
    }

    @Nested @DisplayName("PLANNER 단계 검증")
    class PlannerStage {
        final String target="민수는 검을 쥐었다.\n그는 성문 앞에서 기다렸다.";
        ValidationPublicationValidator.Prepared p() {return prepare(target,10,5);}
        int[] core(String ref) {var s=ValidationPublicationValidator.segment(p().grid(),ref);return new int[]{s.path("start").asInt(),s.path("end").asInt()};}
        @Test @DisplayName("핵심 구간 안 span의 PLANNED 단계와 질문 없는 NO_CHECK 단계를 받는다")
        void acceptsCoreSpansAndEmptyNoCheck() {
            var bounds=core("TSEG1");var quote=new String(cps(target),bounds[0],2);
            assertThatCode(()->validator.stage(p(),ValidationStageKind.PLANNER,"TSEG1",planner("TSEG1","PLANNED",question("a",bounds[0],bounds[0]+2,quote)),List.of())).doesNotThrowAnyException();
            assertThatCode(()->validator.stage(p(),ValidationStageKind.PLANNER,"TSEG2",planner("TSEG2","NO_CHECK"),List.of())).doesNotThrowAnyException();
        }
        @Test @DisplayName("읽기 전용 문맥에 걸친 span·rules·clue_checks·lookup_intents·PLANNED 규칙 위반을 거절한다")
        void rejectsContextSpansAndRetiredFields() {
            var bounds=core("TSEG2");
            invalid(()->validator.stage(p(),ValidationStageKind.PLANNER,"TSEG2",planner("TSEG2","PLANNED",question("a",bounds[0]-1,bounds[0]+1,"x")),List.of()));
            invalid(()->validator.stage(p(),ValidationStageKind.PLANNER,"TSEG2",planner("TSEG2","PLANNED",question("a",bounds[1]-1,bounds[1]+1,"x")),List.of()));
            var withRules=planner("TSEG2","NO_CHECK");withRules.putArray("rules");invalid(()->validator.stage(p(),ValidationStageKind.PLANNER,"TSEG2",withRules,List.of()));
            var withClues=planner("TSEG2","NO_CHECK");withClues.putArray("clue_checks");invalid(()->validator.stage(p(),ValidationStageKind.PLANNER,"TSEG2",withClues,List.of()));
            var intents=question("a",bounds[0],bounds[0]+1,"x");intents.putArray("lookup_intents");
            invalid(()->validator.stage(p(),ValidationStageKind.PLANNER,"TSEG2",planner("TSEG2","PLANNED",intents),List.of()));
            invalid(()->validator.stage(p(),ValidationStageKind.PLANNER,"TSEG2",planner("TSEG2","PLANNED"),List.of()));
            invalid(()->validator.stage(p(),ValidationStageKind.PLANNER,"TSEG2",planner("TSEG2","NO_CHECK",question("a",bounds[0],bounds[0]+1,"x")),List.of()));
            invalid(()->validator.stage(p(),ValidationStageKind.PLANNER,"TSEG2",planner("TSEG2","PLANNED",question("a",bounds[0],bounds[0]+1,"x"),question("a",bounds[0],bounds[0]+1,"x")),List.of()));
            invalid(()->validator.stage(p(),ValidationStageKind.PLANNER,"TSEG2",planner("TSEG1","NO_CHECK"),List.of()));
        }
        @Test @DisplayName("이전 grid의 단계 종류(SUBJECT_RESOLUTION·COVERAGE·BOOK_*·RELATED_SELECTION)와 없는 구간은 받지 않는다")
        void retiredStageKindsAreNotInTheGrid() {
            for(var kind:List.of(ValidationStageKind.SUBJECT_RESOLUTION,ValidationStageKind.COVERAGE,ValidationStageKind.BOOK_SOURCE,ValidationStageKind.BOOK_COVERAGE,ValidationStageKind.RELATED_SELECTION))
                invalid(()->validator.stage(p(),kind,"TSEG1",planner("TSEG1","NO_CHECK"),List.of()));
            invalid(()->validator.stage(p(),ValidationStageKind.PLANNER,"TSEG9",planner("TSEG9","NO_CHECK"),List.of()));
        }
    }
}
