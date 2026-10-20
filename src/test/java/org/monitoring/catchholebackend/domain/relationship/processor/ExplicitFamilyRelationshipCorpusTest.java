package org.monitoring.catchholebackend.domain.relationship.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.monitoring.catchholebackend.domain.relationship.type.FamilyTreeRole;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipAssertion;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;

@DisplayName("합성 가족 설정 17개 문장 유형의 인식·보류·호칭 제외 회귀")
class ExplicitFamilyRelationshipCorpusTest {

    private final ExplicitFamilyRelationshipParser parser = new ExplicitFamilyRelationshipParser();

    // 개인 원고를 저장하지 않고, 관찰한 문법과 현재/이력 구분만 합성 인명으로 재현한다.
    private static final List<CorpusCase> CASES = List.of(
            asserted("현재 두 번째 아들", "profile.가족관계", "도윤의 두 번째 아들", "도윤", RelationshipKind.CHILD_OF, FamilyTreeRole.SON),
            asserted("현재 넷째 아들", "profile.가족_관계", "태준의 넷째 아들", "태준", RelationshipKind.CHILD_OF, FamilyTreeRole.SON),
            asserted("현재 세 번째 아들 가족 항목", "profile.가족관계", "도현의 세 번째 아들", "도현", RelationshipKind.CHILD_OF, FamilyTreeRole.SON),
            asserted("현재 세 번째 아들 출생 순서 항목", "profile.출생_순서", "도현의 세 번째 아들", "도현", RelationshipKind.CHILD_OF, FamilyTreeRole.SON),
            asserted("현재 두 번째 딸", "profile.가족관계", "서하의 두 번째 딸", "서하", RelationshipKind.CHILD_OF, FamilyTreeRole.DAUGHTER),
            asserted("현재 아들 가족 항목", "profile.가족", "현우의 아들", "현우", RelationshipKind.CHILD_OF, FamilyTreeRole.SON),
            asserted("현재 아들 중복 가족 관계 항목", "profile.가족_관계", "현우의 아들", "현우", RelationshipKind.CHILD_OF, FamilyTreeRole.SON),
            asserted("현재 다른 인물의 아들", "profile.가족관계", "지호의 아들", "지호", RelationshipKind.CHILD_OF, FamilyTreeRole.SON),
            asserted("현재 직책으로 지칭한 부모", "profile.가족관계", "영주의 자녀", "영주", RelationshipKind.CHILD_OF, FamilyTreeRole.CHILD),
            asserted("현재 상대 인물의 아버지", "profile.가족관계", "서진의 아버지", "서진", RelationshipKind.PARENT_OF, FamilyTreeRole.FATHER),
            asserted("현재 특징의 언니와 직업 설명", "profile.특징", "은하의 언니인 6등급 탐험가", "은하", RelationshipKind.SIBLING, null),
            new CorpusCase("현재 설명으로 지칭한 상대의 동생", "profile.가족관계", "청암 클랜 수인 남성의 동생", false,
                    true, "청암 클랜 수인 남성", RelationshipKind.SIBLING, RelationshipAssertion.UNCERTAIN, null),
            new CorpusCase("현재 스승에게 붙인 할머니 호칭", "profile.스승", "백운 할머니", false,
                    false, null, null, null, null),
            new CorpusCase("이력에 중복된 아들 명제", "profile.가족", "현우의 아들", true,
                    true, "현우", RelationshipKind.CHILD_OF, RelationshipAssertion.ASSERTED, FamilyTreeRole.SON),
            new CorpusCase("이력에만 남은 아들 명제", "profile.특징", "하윤의 아들", true,
                    true, "하윤", RelationshipKind.CHILD_OF, RelationshipAssertion.ASSERTED, FamilyTreeRole.SON),
            new CorpusCase("이력의 이름 없는 부모 상황", "profile.가족관계", "부모는 사망했고 아버지의 얼굴을 모름", true,
                    true, null, null, null, null),
            new CorpusCase("이력의 가문 직계", "profile.특징", "청연 가문의 직계", true,
                    true, null, null, null, null)
    );

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    @DisplayName("현재·이력의 문법을 분류하되 호칭·상황·혈통을 임의 간선으로 만들지 않는다")
    void classifiesObservedGrammarWithoutInventingRelationships(CorpusCase testCase) {
        var result = parser.parse(testCase.key(), current(testCase));

        assertThat(result.familyMentioned()).isEqualTo(testCase.familyMentioned());
        if (testCase.kind() == null) {
            assertThat(result.relationships()).isEmpty();
            if (testCase.familyMentioned()) {
                assertThat(result.reviewReasons()).isNotEmpty();
            } else {
                assertThat(result.reviewReasons()).isEmpty();
            }
            return;
        }

        assertThat(result.relationships()).singleElement().satisfies(claim -> {
            assertThat(claim.objectMention()).isEqualTo(testCase.mention());
            assertThat(claim.kind()).isEqualTo(testCase.kind());
            assertThat(claim.subtype()).isEqualTo(RelationshipSubtype.UNSPECIFIED);
            // 저장 이력 여부는 문법의 시점이 아니다. 실제 입력 채택은 current provenance 경계가 결정한다.
            assertThat(claim.temporalScope()).isEqualTo(RelationshipTemporalScope.CURRENT);
            assertThat(claim.assertion()).isEqualTo(testCase.assertion());
            assertThat(claim.subjectRole()).isEqualTo(testCase.subjectRole());
            if (testCase.assertion() == RelationshipAssertion.ASSERTED) {
                assertThat(result.reviewReasons()).isEmpty();
                assertThat(claim.reviewReasons()).isEmpty();
            } else {
                assertThat(result.reviewReasons()).contains("AMBIGUOUS_FAMILY_ROLE");
                assertThat(claim.reviewReasons()).contains("AMBIGUOUS_FAMILY_ROLE");
            }
        });
    }

    @Test
    @DisplayName("현재 13개 중 명시 관계 11개·설명 상대 검토 1개·일반 호칭 1개와 이력 4개를 빠짐없이 검증한다")
    void accountsForEveryObservedCaseWithoutCountingHistoryAsCurrent() {
        assertThat(CASES).hasSize(17);
        var currentCases = CASES.stream().filter(testCase -> !testCase.historyOnly()).toList();
        assertThat(currentCases).hasSize(13);
        assertThat(currentCases.stream().filter(testCase -> testCase.assertion() == RelationshipAssertion.ASSERTED)).hasSize(11);
        assertThat(currentCases.stream().filter(testCase -> testCase.assertion() == RelationshipAssertion.UNCERTAIN)).hasSize(1);
        assertThat(currentCases.stream().filter(testCase -> !testCase.familyMentioned())).hasSize(1);
        assertThat(CASES.stream().filter(CorpusCase::historyOnly)).hasSize(4);
    }

    private static Stream<CorpusCase> corpus() {
        return CASES.stream();
    }

    private static CorpusCase asserted(String label, String key, String value, String mention,
            RelationshipKind kind, FamilyTreeRole subjectRole) {
        return new CorpusCase(label, key, value, false, true, mention, kind, RelationshipAssertion.ASSERTED, subjectRole);
    }

    private static ObjectNode current(CorpusCase testCase) {
        ObjectNode wrapper = JsonNodeFactory.instance.objectNode().put("factValue", testCase.value());
        wrapper.set("valueJson", JsonNodeFactory.instance.objectNode().put("value", testCase.value())
                .put("name", testCase.key().substring("profile.".length()).replace('_', ' ')));
        return wrapper;
    }

    private record CorpusCase(String label, String key, String value, boolean historyOnly, boolean familyMentioned,
            String mention, RelationshipKind kind, RelationshipAssertion assertion, FamilyTreeRole subjectRole) {
        @Override
        public String toString() {
            return label;
        }
    }
}
