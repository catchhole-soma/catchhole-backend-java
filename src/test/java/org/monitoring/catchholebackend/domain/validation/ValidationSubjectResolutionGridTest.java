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
import org.monitoring.catchholebackend.domain.validation.entity.ValidationStage;
import org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper;
import org.monitoring.catchholebackend.domain.validation.processor.*;
import org.monitoring.catchholebackend.domain.validation.service.ValidationDataService;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.global.exception.AppException;

/** 모든 원고·설정은 합성 값이다. 서버 grid v2의 기계 주체 매칭과 SUBJECT_RESOLUTION·COVERAGE 검증 규칙만 본다. */
@DisplayName("검수 grid v2의 주체 매칭과 SUBJECT_RESOLUTION 검증 (GH349)")
class ValidationSubjectResolutionGridTest {
    final ObjectMapper json=new ObjectMapper();final AnalysisStateJournal journal=new AnalysisStateJournal();final ValidationDataMapper mapper=new ValidationDataMapper(journal);
    final ValidationPublicationValidator validator=new ValidationPublicationValidator(data(),new ValidationDecisionValidator(mapper,journal),new ValidationClueCollector(),mapper);
    String text;

    ValidationDataService data() {
        var service=mock(ValidationDataService.class);
        when(service.readValidationData(anyLong(),any(),any(),any(),any(),any())).thenAnswer(call->{
            var request=call.getArgument(3,ValidationDataRequest.class);
            if(request.operation().equals("INPUT")) {var input=json.createObjectNode().put("status","READY").put("entryCount",0);input.putObject("target").put("contentS3Version","v1");input.putArray("sources");return input;}
            return json.createObjectNode().put("status","READY").put("text",text).put("totalCodePoints",text.codePointCount(0,text.length()));
        });
        return service;
    }
    ValidationPublicationValidator.Prepared prepare(String target,int segmentChars,int contextChars,ArrayNode entries,ValidationBranch branch) {
        text=target;var configuration=json.createObjectNode();var budgets=configuration.putObject("budgets");
        for(String name:List.of("setting","typo"))budgets.putObject(name).put("segmentChars",segmentChars).put("contextChars",contextChars).put("cataloguePageSize",2);
        String hash="a".repeat(64);
        var context=new ValidationPublicationValidator.Context(UUID.randomUUID(),UUID.randomUUID(),new Scope(1,UUID.randomUUID(),1,hash),1L,UUID.randomUUID(),hash,hash,configuration,entries,Map.of());
        return validator.prepare(context,branch,null,null);
    }
    ValidationPublicationValidator.Prepared prepare(String target,ArrayNode entries) {return prepare(target,100,10,entries,ValidationBranch.SETTING);}
    ObjectNode entry(String subjectRef,String name,String... aliases) {
        var row=json.createObjectNode().put("domain","characters").put("subjectRef",subjectRef);var state=row.putObject("state");
        if(name==null)state.putNull("name");else state.put("name",name);var array=state.putArray("aliases");for(String alias:aliases)array.add(alias);return row;
    }
    ObjectNode world(String settingId,String subjectName) {
        var row=json.createObjectNode().put("domain","worldSettings");row.putObject("state").put("targetWorldSettingId",settingId).put("subjectName",subjectName);return row;
    }
    ArrayNode entries(ObjectNode... rows) {var array=json.createArrayNode();for(var row:rows)array.add(row);return array;}
    List<String> subjects(ValidationPublicationValidator.Prepared p,String segment) {
        for(var row:p.grid().path("segmentSubjects"))if(row.path("segmentRef").asText().equals(segment)) {var refs=new ArrayList<String>();row.path("subjectRefs").forEach(r->refs.add(r.asText()));return refs;}
        throw new AssertionError("segmentSubjects에 "+segment+"가 없다");
    }
    ObjectNode mention(String mention,String subject,String reason) {var m=json.createObjectNode().put("mention",mention).put("reason",reason);if(subject==null)m.putNull("subject_ref");else m.put("subject_ref",subject);return m;}
    ObjectNode resolution(ObjectNode... mentions) {var body=json.createObjectNode();var array=body.putArray("mentions");for(var m:mentions)array.add(m);return body;}
    ObjectNode coverage(String... rules) {
        var body=json.createObjectNode();body.putArray("questions");body.putArray("segments").addObject().put("segment_ref","TSEG1").put("status","NO_CHECK").put("reason","합성 확인");
        var array=body.putArray("rules");for(String rule:rules)array.addObject().put("entry_ref",rule).put("status","CHECKED").put("reason","합성 규칙 확인");body.putArray("clue_checks");return body;
    }
    ValidationStage stored(ValidationStageKind kind,String ref,JsonNode payload,boolean successful) {return ValidationStage.create(null,ValidationBranch.SETTING,kind,ref,journal.hash(payload),payload,successful);}
    void invalid(ThrowingCallable call) {assertThatThrownBy(call::call).isInstanceOf(AppException.class).extracting("resultCode").isEqualTo(ValidationErrorCode.VALIDATION_RESULT_INVALID);}
    interface ThrowingCallable {void call();}

    @Nested @DisplayName("기계 주체 매칭")
    class MechanicalMatching {
        @Test @DisplayName("이름·별칭이 구간 문맥에 그대로 있으면 주체를 고르고 없는 이름과 이름 없는 항목은 고르지 않는다")
        void nameAndAliasMatchOthersDoNot() {
            var p=prepare("검은 매가 북부에서 서윤을 보았다",entries(entry("seoyun","서윤"),entry("doyun","도윤","검은 매"),entry("seoyun","서윤","윤"),entry("nameless",null),entry("blank",null,""),entry("absent","없는이"),world("w1","북부")));
            // S1 서윤(B1·B3), S2 도윤(별칭 검은 매), S3 이름·별칭 없음, S4 빈 별칭, S5 없는이, S6 세계관 북부
            assertThat(subjects(p,"TSEG1")).containsExactly("S1","S2","S6");
        }
        @Test @DisplayName("subjectRefs는 S 번호 순이며 같은 주체를 한 번만 담는다")
        void subjectRefsAreNumericallySortedAndUnique() {
            var rows=new ArrayList<ObjectNode>();String[] names={"갑돌","을순","병철","정희","무영","기남","경자","신우","임호","계월"};
            for(int i=0;i<names.length;i++)rows.add(entry("s"+i,names[i]));rows.add(entry("s1","을순","을순"));
            var p=prepare("계월이 을순을 만났다",entries(rows.toArray(ObjectNode[]::new)));
            assertThat(subjects(p,"TSEG1")).containsExactly("S2","S10");
        }
        @Test @DisplayName("1글자 이름은 구간 문맥에 있어도 고르지 않고 2글자 이름은 고른다")
        void oneCodePointNameIgnoredTwoCodePointNameMatched() {
            var p=prepare("한이 하람을 불렀다",entries(entry("han","한"),entry("haram","하람")));
            assertThat(subjects(p,"TSEG1")).as("한은 원고에 있지만 1글자라 기계 일치가 아니다").containsExactly("S2");
        }
        @Test @DisplayName("1글자 별칭은 무시하고 같은 항목의 2글자 별칭은 일치로 본다")
        void oneCodePointAliasIgnoredTwoCodePointAliasMatched() {
            var rows=entries(entry("doyun","도윤","윤","검매"));
            assertThat(subjects(prepare("윤이 웃었다",rows),"TSEG1")).isEmpty();
            assertThat(subjects(prepare("검매가 웃었다",rows),"TSEG1")).containsExactly("S1");
        }
        @Test @DisplayName("길이는 code point로 세므로 UTF-16 두 단위인 이모지 1글자 이름도 무시한다")
        void surrogatePairNameCountsAsOneCodePoint() {
            assertThat("😀".length()).isEqualTo(2);
            var p=prepare("😀가 😀😀를 보았다",entries(entry("emoji","😀"),entry("pair","😀😀")));
            assertThat(subjects(p,"TSEG1")).containsExactly("S2");
        }
        @Test @DisplayName("정규화 없이 정확히 같은 문자열만 일치로 본다")
        void comparisonIsExactWithoutNormalization() {
            var p=prepare("서 윤과 SEOYUN이 왔다",entries(entry("seoyun","서윤","Seoyun")));
            assertThat(subjects(p,"TSEG1")).isEmpty();
        }
        @Test @DisplayName("구간 문맥 경계 밖의 이름은 그 구간의 주체가 아니며 위치는 code point 기준이다")
        void matchesOnlyInsideCodePointContextBounds() {
            // code point: 0..3 😀, 4 서, 5 윤. segmentChars=2, contextChars=1 → TSEG1 ctx[0,3) TSEG2 ctx[1,5) TSEG3 ctx[3,6)
            var p=prepare("😀😀😀😀서윤",2,1,entries(entry("seoyun","서윤"),entry("emoji","😀서")),ValidationBranch.SETTING);
            assertThat(p.grid().path("segments").size()).isEqualTo(3);
            assertThat(subjects(p,"TSEG1")).isEmpty();
            assertThat(subjects(p,"TSEG2")).as("서윤은 경계에 걸쳐 일치하지 않고 😀서만 일치한다").containsExactly("S2");
            assertThat(subjects(p,"TSEG3")).containsExactly("S1","S2");
        }
        @Test @DisplayName("SETTING grid v2는 구간마다 PLANNER·SUBJECT_RESOLUTION·COVERAGE만 두고 TYPO grid는 v1 그대로다")
        void settingGridV2UnitsAndUnchangedTypo() {
            var rows=entries(entry("seoyun","서윤"),entry("doyun","도윤"),entry("minsu","민수"));
            var setting=prepare("서윤😀😀도윤",2,0,rows,ValidationBranch.SETTING).grid();
            assertThat(setting.path("gridVersion").asInt()).isEqualTo(2);
            var units=new ArrayList<String>();setting.path("units").forEach(u->units.add(u.path("stage").asText()+":"+u.path("ref").asText()));
            assertThat(units).containsExactly("PLANNER:TSEG1","SUBJECT_RESOLUTION:TSEG1","COVERAGE:TSEG1","PLANNER:TSEG2","SUBJECT_RESOLUTION:TSEG2","COVERAGE:TSEG2",
                    "PLANNER:TSEG3","SUBJECT_RESOLUTION:TSEG3","COVERAGE:TSEG3","MERGED_PLAN:PLAN");
            assertThat(setting.path("segmentSubjects").toString()).isEqualTo("[{\"segmentRef\":\"TSEG1\",\"subjectRefs\":[\"S1\"]},{\"segmentRef\":\"TSEG2\",\"subjectRefs\":[]},{\"segmentRef\":\"TSEG3\",\"subjectRefs\":[\"S2\"]}]");
            var typo=prepare("서윤😀😀도윤",2,0,rows,ValidationBranch.TYPO).grid();
            assertThat(typo.path("gridVersion").asInt()).isEqualTo(1);assertThat(typo.has("segmentSubjects")).isFalse();
        }
    }

    @Nested @DisplayName("SUBJECT_RESOLUTION payload")
    class SubjectResolutionPayload {
        ValidationPublicationValidator.Prepared p;
        @BeforeEach void grid() {p=prepare("그녀가 노인을 보았다",entries(entry("seoyun","서윤"),entry("doyun","도윤")));}
        @Test @DisplayName("고정 주체 참조와 null 참조, 빈 mentions를 받는다")
        void knownAndNullSubjectsAccepted() {
            assertThatCode(()->validator.stage(p,ValidationStageKind.SUBJECT_RESOLUTION,"TSEG1",resolution(mention("그녀","S1","직전 문장의 서윤을 가리킨다"),mention("노인",null,"카탈로그에 없는 인물")),List.of())).doesNotThrowAnyException();
            assertThatCode(()->validator.stage(p,ValidationStageKind.SUBJECT_RESOLUTION,"TSEG1",resolution(),List.of())).doesNotThrowAnyException();
        }
        @Test @DisplayName("catalogue에 없는 주체 참조를 거절한다")
        void unknownSubjectRejected() {invalid(()->validator.stage(p,ValidationStageKind.SUBJECT_RESOLUTION,"TSEG1",resolution(mention("그녀","S9","없는 주체")),List.of()));}
        @Test @DisplayName("같은 mention을 두 번 보내면 거절한다")
        void duplicateMentionRejected() {invalid(()->validator.stage(p,ValidationStageKind.SUBJECT_RESOLUTION,"TSEG1",resolution(mention("그녀","S1","서윤"),mention("그녀",null,"다른 해석")),List.of()));}
        @Test @DisplayName("빈 reason·빈 mention·subject_ref 누락·배열이 아닌 mentions를 거절한다")
        void blankOrMalformedRejected() {
            invalid(()->validator.stage(p,ValidationStageKind.SUBJECT_RESOLUTION,"TSEG1",resolution(mention("그녀","S1"," ")),List.of()));
            invalid(()->validator.stage(p,ValidationStageKind.SUBJECT_RESOLUTION,"TSEG1",resolution(mention(" ","S1","서윤")),List.of()));
            var missing=resolution(mention("그녀","S1","서윤"));((ObjectNode)missing.path("mentions").path(0)).remove("subject_ref");
            invalid(()->validator.stage(p,ValidationStageKind.SUBJECT_RESOLUTION,"TSEG1",missing,List.of()));
            invalid(()->validator.stage(p,ValidationStageKind.SUBJECT_RESOLUTION,"TSEG1",json.createObjectNode().put("mentions","그녀"),List.of()));
        }
        @Test @DisplayName("v2 grid에는 RELATED_SELECTION 단위가 없어 page 선별 게시를 거절한다")
        void relatedSelectionIsNotAV2Unit() {
            var entries=json.createObjectNode();entries.putArray("entries").addObject().put("entry_ref","B1").put("selected",false).put("reason","합성 비선정");
            invalid(()->validator.stage(p,ValidationStageKind.RELATED_SELECTION,"TSEG1:PAGE1",entries,List.of()));
            invalid(()->validator.stage(p,ValidationStageKind.SUBJECT_RESOLUTION,"TSEG2",resolution(),List.of()));
        }
    }

    @Nested @DisplayName("COVERAGE 필수 규칙")
    class CoverageRules {
        ValidationPublicationValidator.Prepared p;
        // S1 서윤(B1·B2), S2 도윤(B3), S3 민수(B4). 원고에는 서윤만 이름으로 나온다.
        @BeforeEach void grid() {
            p=prepare("서윤은 그녀를 보았다",entries(entry("seoyun","서윤"),entry("seoyun","서윤"),entry("doyun","도윤"),entry("minsu","민수")));
            assertThat(p.grid().path("modelClues")).isEmpty();assertThat(subjects(p,"TSEG1")).containsExactly("S1");
        }
        @Test @DisplayName("기계 일치 주체와 해소한 주체의 모든 항목을 요구한다")
        void expectedRulesAreMechanicalUnionResolved() {
            var stored=List.of(stored(ValidationStageKind.SUBJECT_RESOLUTION,"TSEG1",resolution(mention("그녀","S2","도윤을 가리킨다"),mention("서윤","S1","이미 기계 일치"),mention("노인",null,"카탈로그에 없음")),true));
            invalid(()->validator.stage(p,ValidationStageKind.COVERAGE,"TSEG1",coverage("B1","B2"),stored));
            invalid(()->validator.stage(p,ValidationStageKind.COVERAGE,"TSEG1",coverage("B1","B2","B3","B4"),stored));
            assertThatCode(()->validator.stage(p,ValidationStageKind.COVERAGE,"TSEG1",coverage("B1","B2","B3"),stored)).doesNotThrowAnyException();
        }
        @Test @DisplayName("해소 결과가 없으면 기계 일치 주체의 항목만 요구한다")
        void mechanicalOnly() {
            var stored=List.of(stored(ValidationStageKind.SUBJECT_RESOLUTION,"TSEG1",resolution(),true));
            assertThatCode(()->validator.stage(p,ValidationStageKind.COVERAGE,"TSEG1",coverage("B1","B2"),stored)).doesNotThrowAnyException();
        }
        @Test @DisplayName("관련 주체가 없으면 빈 rules가 유효하다")
        void emptyRelevantSetAllowsEmptyRules() {
            var empty=prepare("그녀는 노인을 보았다",entries(entry("seoyun","서윤"),entry("doyun","도윤")));
            var stored=List.of(stored(ValidationStageKind.SUBJECT_RESOLUTION,"TSEG1",resolution(mention("노인",null,"카탈로그에 없음")),true));
            assertThatCode(()->validator.stage(empty,ValidationStageKind.COVERAGE,"TSEG1",coverage(),stored)).doesNotThrowAnyException();
            invalid(()->validator.stage(empty,ValidationStageKind.COVERAGE,"TSEG1",coverage("B1"),stored));
        }
        @Test @DisplayName("저장된 성공 SUBJECT_RESOLUTION이 없으면 COVERAGE를 거절한다")
        void missingOrFailedResolutionRejected() {
            invalid(()->validator.stage(p,ValidationStageKind.COVERAGE,"TSEG1",coverage("B1","B2"),List.of()));
            var failed=resolution();failed.put("failure_code","MODEL_CALL_FAILED");
            invalid(()->validator.stage(p,ValidationStageKind.COVERAGE,"TSEG1",coverage("B1","B2"),List.of(stored(ValidationStageKind.SUBJECT_RESOLUTION,"TSEG1",failed,false))));
            invalid(()->validator.stage(p,ValidationStageKind.COVERAGE,"TSEG1",coverage("B1","B2"),List.of(stored(ValidationStageKind.SUBJECT_RESOLUTION,"TSEG2",resolution(),true))));
        }
    }

    @Nested @DisplayName("grid 병합")
    class GridMerge {
        @Test @DisplayName("segmentSubjects가 다르거나 배포 전 v1 grid면 같은 실행으로 이어 가지 않는다")
        void differentSegmentSubjectsConflict() {
            var p=prepare("서윤은 그녀를 보았다",entries(entry("seoyun","서윤"),entry("doyun","도윤")));
            assertThatCode(()->validator.extendGrid(p,p.grid().deepCopy())).doesNotThrowAnyException();
            var changed=(ObjectNode)p.grid().deepCopy();((ArrayNode)changed.path("segmentSubjects").path(0).path("subjectRefs")).add("S2");
            assertThatThrownBy(()->validator.extendGrid(p,changed)).isInstanceOf(AppException.class).extracting("resultCode").isEqualTo(ValidationErrorCode.VALIDATION_SOURCE_VERSION_CONFLICT);
            var v1=(ObjectNode)p.grid().deepCopy();v1.put("gridVersion",1);v1.remove("segmentSubjects");
            var units=v1.putArray("units");units.addObject().put("stage","PLANNER").put("ref","TSEG1");units.addObject().put("stage","COVERAGE").put("ref","TSEG1");units.addObject().put("stage","RELATED_SELECTION").put("ref","TSEG1:PAGE1");units.addObject().put("stage","MERGED_PLAN").put("ref","PLAN");
            assertThatThrownBy(()->validator.extendGrid(p,v1)).isInstanceOf(AppException.class).extracting("resultCode").isEqualTo(ValidationErrorCode.VALIDATION_SOURCE_VERSION_CONFLICT);
        }
    }
}
