package org.monitoring.catchholebackend.domain.relationship.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipAssertion;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;

@DisplayName("현재 프로필의 명시적 가족 관계 문법")
class ExplicitFamilyRelationshipParserTest {

    private final ExplicitFamilyRelationshipParser parser = new ExplicitFamilyRelationshipParser();

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "아버지: 홍길동|CHILD_OF|UNSPECIFIED",
            "어머니는 홍길동|CHILD_OF|UNSPECIFIED",
            "홍길동의 아들|CHILD_OF|UNSPECIFIED",
            "홍길동의 딸이다.|CHILD_OF|UNSPECIFIED",
            "아들: 홍길동|PARENT_OF|UNSPECIFIED",
            "홍길동의 아버지|PARENT_OF|UNSPECIFIED",
            "남편은 홍길동|SPOUSE|UNSPECIFIED",
            "아내: 홍길동|SPOUSE|UNSPECIFIED",
            "홍길동의 배우자입니다|SPOUSE|UNSPECIFIED",
            "홍길동의 형제|SIBLING|UNSPECIFIED",
            "친아버지: 홍길동|CHILD_OF|BIOLOGICAL",
            "홍길동의 친아들|CHILD_OF|BIOLOGICAL",
            "양부: 홍길동|CHILD_OF|ADOPTIVE",
            "양녀: 홍길동|PARENT_OF|ADOPTIVE",
            "의붓어머니: 홍길동|CHILD_OF|STEP",
            "홍길동의 의붓아들|CHILD_OF|STEP",
            "홍길동의 친형제|SIBLING|BIOLOGICAL",
            "홍길동의 이복형제|SIBLING|HALF",
            "이부자매: 홍길동|SIBLING|HALF",
            "의붓남매: 홍길동|SIBLING|STEP"
    })
    @DisplayName("역할 표기와 소유격의 부모 방향을 구분하고 명시된 세부 관계만 보존한다")
    void parsesExplicitFamilyGrammar(String text, RelationshipKind kind, RelationshipSubtype subtype) {
        FamilyRelationshipParseResult result = parser.parse(current(text));

        assertThat(result.familyMentioned()).isTrue();
        assertThat(result.reviewReasons()).isEmpty();
        assertThat(result.relationships()).singleElement().satisfies(relation -> {
            assertThat(relation.objectMention()).isEqualTo("홍길동");
            assertThat(relation.kind()).isEqualTo(kind);
            assertThat(relation.subtype()).isEqualTo(subtype);
            assertThat(relation.temporalScope()).isEqualTo(RelationshipTemporalScope.CURRENT);
            assertThat(relation.timeLabel()).isNull();
            assertThat(relation.assertion()).isEqualTo(RelationshipAssertion.ASSERTED);
            assertThat(relation.reviewReasons()).isEmpty();
        });
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "홍길동의 아들이 아니다|NEGATED",
            "아버지: 홍길동이 아님|NEGATED",
            "홍길동의 아들로 추정된다|UNCERTAIN",
            "아버지: 홍길동 (추정)|UNCERTAIN",
            "남편은 홍길동?|UNCERTAIN",
            "홍길동의 아버지 같은 존재|FIGURATIVE",
            "홍길동의 어머니처럼 여긴다|FIGURATIVE"
    })
    @DisplayName("부정 추정 비유는 명제를 보여주되 자동 확정 가능한 단정으로 바꾸지 않는다")
    void preservesNonAssertedClaims(String text, RelationshipAssertion assertion) {
        var result = parser.parse(current(text));

        assertThat(result.relationships()).singleElement().satisfies(relation -> {
            assertThat(relation.objectMention()).isEqualTo("홍길동");
            assertThat(relation.assertion()).isEqualTo(assertion);
            assertThat(relation.reviewReasons()).contains(assertion.name());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"전 남편: 홍길동", "홍길동의 전 아내", "과거 홍길동의 배우자", "홍길동의 배우자였다"})
    @DisplayName("명시된 과거 표현은 현재 관계로 승격하지 않는다")
    void preservesExplicitHistoricalScope(String text) {
        assertThat(parser.parse(current(text)).relationships()).singleElement().satisfies(relation -> {
            assertThat(relation.objectMention()).isEqualTo("홍길동");
            assertThat(relation.temporalScope()).isEqualTo(RelationshipTemporalScope.HISTORICAL);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"형", "오빠", "언니", "동생"})
    @DisplayName("친족 외에도 사용하는 형제 호칭은 검토에 남긴다")
    void holdsAmbiguousSiblingTitles(String role) {
        assertThat(parser.parse(current(role + ": 홍길동")).relationships()).singleElement().satisfies(relation -> {
            assertThat(relation.assertion()).isEqualTo(RelationshipAssertion.UNCERTAIN);
            assertThat(relation.reviewReasons()).contains("AMBIGUOUS_FAMILY_ROLE");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"profile.아버지", "profile_family/아버지", "아버지"})
    @DisplayName("역할 자체인 프로필 키는 단순 상대명에만 의미를 보완한다")
    void supportsExplicitRoleKeys(String key) {
        assertThat(parser.parse(key, current("홍길동")).relationships()).singleElement().satisfies(relation -> {
            assertThat(relation.objectMention()).isEqualTo("홍길동");
            assertThat(relation.kind()).isEqualTo(RelationshipKind.CHILD_OF);
            assertThat(relation.reviewReasons()).isEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"profile.전_남편", "profile.전__남편", "profile.전_아내",
            "profile_family/전_남편", "전_남편"})
    @DisplayName("키 항목명 안의 underscore는 공백이며 명시된 과거 역할을 보존한다")
    void preservesHistoricalRoleInWholeKeyLabel(String key) {
        var result = parser.parse(key, current("홍길동"));

        assertThat(result.reviewReasons()).isEmpty();
        assertThat(result.relationships()).singleElement().satisfies(relation -> {
            assertThat(relation.kind()).isEqualTo(RelationshipKind.SPOUSE);
            assertThat(relation.objectMention()).isEqualTo("홍길동");
            assertThat(relation.assertion()).isEqualTo(RelationshipAssertion.ASSERTED);
            assertThat(relation.temporalScope()).isEqualTo(RelationshipTemporalScope.HISTORICAL);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"profile.과거_아버지", "profile.추정_아버지", "profile.아버지_추정",
            "profile.아버지(추정)", "profile_family/추정_아버지", "profile.미래_배우자",
            "profile.의붓_아버지", "profile_아버지", "profile.추정/아버지", "profile.과거.아버지"})
    @DisplayName("미지원 가족 항목명의 한정어나 경로를 버리고 마지막 역할만 연결하지 않는다")
    void holdsUnsupportedWholeFamilyKeyForBareTarget(String key) {
        var result = parser.parse(key, current("홍길동"));

        assertThat(result.relationships()).isEmpty();
        assertThat(result.familyMentioned()).isTrue();
        assertThat(result.reviewReasons()).contains("UNSUPPORTED_FAMILY_SYNTAX");
    }

    @ParameterizedTest
    @ValueSource(strings = {"profile.과거_아버지", "profile.추정_아버지", "profile.아버지_추정",
            "profile.추정_가족", "profile.아버지(추정)", "profile.소문_가족", "profile.의붓_아버지",
            "profile.추정/아버지", "profile.과거.아버지"})
    @DisplayName("값이 완결된 관계 문장이어도 키의 한정어 문맥을 무시하지 않는다")
    void holdsQualifiedKeyEvenWhenValueIsCompleteClaim(String key) {
        for (boolean hasStructuredValue : List.of(false, true)) {
            ObjectNode wrapper = current("아버지: 홍길동");
            if (hasStructuredValue) {
                wrapper.set("valueJson", JsonNodeFactory.instance.objectNode()
                        .put("value", "아버지: 홍길동").put("name", "family"));
            }

            assertThat(parser.parse(key, wrapper).relationships()).singleElement().satisfies(relation -> {
                assertThat(relation.assertion()).isEqualTo(RelationshipAssertion.UNCERTAIN);
                assertThat(relation.reviewReasons()).contains("UNSUPPORTED_FAMILY_SYNTAX");
            });
        }
    }

    @Test
    @DisplayName("과거 키와 현재 문장의 상충을 현재 단정으로 해소하지 않는다")
    void holdsConflictingKeyAndValueTime() {
        assertThat(parser.parse("profile.과거_배우자", current("현재 배우자: 홍길동")).relationships())
                .singleElement().satisfies(relation -> {
                    assertThat(relation.assertion()).isEqualTo(RelationshipAssertion.UNCERTAIN);
                    assertThat(relation.reviewReasons()).contains("UNSUPPORTED_FAMILY_SYNTAX");
                });
    }

    @Test
    @DisplayName("서로 같은 두 표현 채널과 반복된 명제는 한 번만 반환한다")
    void deduplicatesEquivalentChannelsAndRepeatedClaims() {
        ObjectNode wrapper = current("아버지: 홍길동; 아버지: 홍길동");
        wrapper.set("valueJson", JsonNodeFactory.instance.objectNode().put("value", wrapper.path("factValue").asText()));

        var result = parser.parse(wrapper);

        assertThat(result.relationships()).hasSize(1);
        assertThat(result.reviewReasons()).isEmpty();
    }

    @Test
    @DisplayName("표시값과 구조화 현재값이 다르면 어느 한쪽도 자동 확정하지 않는다")
    void holdsConflictingCurrentChannels() {
        ObjectNode wrapper = current("아버지: 홍길동");
        wrapper.set("valueJson", JsonNodeFactory.instance.objectNode().put("value", "아버지: 김철수"));

        var result = parser.parse(wrapper);

        assertThat(result.relationships()).hasSize(2).allSatisfy(relation -> {
            assertThat(relation.assertion()).isEqualTo(RelationshipAssertion.CONFLICTING);
            assertThat(relation.reviewReasons()).contains("CURRENT_VALUE_CONFLICT");
        });
    }

    @Test
    @DisplayName("줄바꿈과 세미콜론으로 분리한 온전한 명제를 순서대로 반환한다")
    void parsesOnlyCompleteDelimitedClauses() {
        var result = parser.parse(current("아버지: 홍길동\n남편은 김철수; 형제: 박영수"));

        assertThat(result.relationships()).extracting(ParsedFamilyRelationship::objectMention)
                .containsExactly("홍길동", "김철수", "박영수");
        assertThat(result.reviewReasons()).isEmpty();
    }

    @Test
    @DisplayName("뒤의 부정 문장을 버리고 앞의 관계만 자동 확정하지 않는다")
    void doesNotDropUnparsedQualifyingClause() {
        var result = parser.parse(current("아버지: 홍길동; 사실은 그렇지 않다"));

        assertThat(result.relationships()).singleElement().satisfies(relation -> {
            assertThat(relation.assertion()).isEqualTo(RelationshipAssertion.UNCERTAIN);
            assertThat(relation.reviewReasons()).contains("UNSUPPORTED_FAMILY_SYNTAX");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"홍길동의 아들이며 김철수의 형제", "아버지: 홍길동, 김철수", "홍길동의 의형제",
            "홍길동의 아들의 아버지", "아버지: 미상", "아버지는 홍길동이 아닌 김철수", "아버지: 그"})
    @DisplayName("지원하지 않는 복합 추론과 미해결 대명사는 관계로 만들지 않는다")
    void doesNotGuessUnsupportedFamilyClaims(String text) {
        var result = parser.parse(current(text));

        assertThat(result.relationships()).isEmpty();
        assertThat(result.familyMentioned()).isTrue();
        assertThat(result.reviewReasons()).contains("UNSUPPORTED_FAMILY_SYNTAX");
    }

    @ParameterizedTest
    @ValueSource(strings = {"그녀", "그 사람", "그 남자", "본인", "자신", "우리", "누군가", "알 수 없음"})
    @DisplayName("대명사와 미상 호칭은 같은 이름의 캐릭터가 있어도 자동 연결할 명제로 반환하지 않는다")
    void doesNotReturnPronounsAsTargetNames(String mention) {
        assertThat(parser.parse(current("아버지: " + mention)).relationships()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"외형은 아름답다", "양자역학을 연구한다", "그는 형사다", "홍길동을 만났다", "검을 좋아한다"})
    @DisplayName("친족 단어의 일부가 들어간 일반 설정에서 관계를 추측하지 않는다")
    void ignoresUnrelatedProfileText(String text) {
        var result = parser.parse(current(text));

        assertThat(result.familyMentioned()).isFalse();
        assertThat(result.relationships()).isEmpty();
        assertThat(result.reviewReasons()).isEmpty();
    }

    @Test
    @DisplayName("원문 근거와 임의 중첩 속성에서 관계를 추출하지 않는다")
    void doesNotTraverseMetadataOrEvidence() {
        ObjectNode wrapper = current("검은 머리");
        wrapper.set("evidence", JsonNodeFactory.instance.objectNode().put("quote", "홍길동의 아들"));
        wrapper.set("valueJson", JsonNodeFactory.instance.objectNode().set("details",
                JsonNodeFactory.instance.objectNode().put("value", "남편은 김철수")));

        assertThat(parser.parse(wrapper).relationships()).isEmpty();
    }

    @Test
    @DisplayName("지원하지 않는 구조화 조건이 있으면 표시 문자열만 잘라 자동 반영하지 않는다")
    void holdsAdditionalStructuredContext() {
        ObjectNode wrapper = current("아버지: 홍길동");
        wrapper.set("valueJson", JsonNodeFactory.instance.objectNode().put("value", "아버지: 홍길동").put("uncertain", true));

        assertThat(parser.parse(wrapper).relationships()).singleElement().satisfies(relation -> {
            assertThat(relation.assertion()).isEqualTo(RelationshipAssertion.UNCERTAIN);
            assertThat(relation.reviewReasons()).contains("UNSUPPORTED_STRUCTURED_CONTEXT");
        });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "profile.family|family",
            "profile.family_relations|family relations",
            "profile.family__relations|family   relations",
            "profile.가족_관계|가족 관계",
            "profile.manual_123|가족 관계",
            "profile.manual_123|가족",
            "profile.manual_123|가족관계",
            "profile.manual_123|family"
    })
    @DisplayName("직접 편집 API의 현재 value와 검증된 표시명 name은 추가 의미로 보류하지 않는다")
    void acceptsCurrentProfileDisplayNameMetadata(String factKey, String displayName) {
        ObjectNode wrapper = current("배우자: 민지");
        wrapper.set("valueJson", JsonNodeFactory.instance.objectNode()
                .put("value", "배우자: 민지").put("name", displayName));

        var result = parser.parse(factKey, wrapper);

        assertThat(result.reviewReasons()).isEmpty();
        assertThat(result.relationships()).singleElement().satisfies(relation -> {
            assertThat(relation.kind()).isEqualTo(RelationshipKind.SPOUSE);
            assertThat(relation.objectMention()).isEqualTo("민지");
            assertThat(relation.assertion()).isEqualTo(RelationshipAssertion.ASSERTED);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"거짓말", "과거 가족", "가족 관계 추정", "아버지: 홍길동"})
    @DisplayName("키 표시명이나 가족 일반명과 다른 name의 의미를 버리지 않는다")
    void holdsUnverifiedDisplayNames(String displayName) {
        ObjectNode wrapper = current("배우자: 민지");
        wrapper.set("valueJson", JsonNodeFactory.instance.objectNode()
                .put("value", "배우자: 민지").put("name", displayName));

        assertThat(parser.parse("profile.family", wrapper).relationships()).singleElement().satisfies(relation -> {
            assertThat(relation.assertion()).isEqualTo(RelationshipAssertion.UNCERTAIN);
            assertThat(relation.reviewReasons()).contains("UNSUPPORTED_STRUCTURED_CONTEXT");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"추정 가족관계", "가족관계(추정)", "과거 가족", "possible family"})
    @DisplayName("키와 일치한 표시명도 확실성이나 시점을 바꾸는 한정어가 있으면 보류한다")
    void holdsQualifyingDisplayNamesEvenWhenMatchingKey(String displayName) {
        ObjectNode wrapper = current("배우자: 민지");
        wrapper.set("valueJson", JsonNodeFactory.instance.objectNode()
                .put("value", "배우자: 민지").put("name", displayName));

        assertThat(parser.parse("profile." + displayName.replace(' ', '_'), wrapper).reviewReasons())
                .contains("UNSUPPORTED_STRUCTURED_CONTEXT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "42", "true", "{}", "[]", "\"\""})
    @DisplayName("표시명 name이 빈 값이나 문자열 이외의 구조이면 보류한다")
    void holdsMalformedDisplayNames(String rawName) throws Exception {
        ObjectNode wrapper = current("배우자: 민지");
        ObjectNode value = JsonNodeFactory.instance.objectNode().put("value", "배우자: 민지");
        value.set("name", new ObjectMapper().readTree(rawName));
        wrapper.set("valueJson", value);

        assertThat(parser.parse("profile.family", wrapper).reviewReasons())
                .contains("UNSUPPORTED_STRUCTURED_CONTEXT");
    }

    @Test
    @DisplayName("허용된 표시명 외의 추가 조건은 여전히 검토에 남긴다")
    void doesNotIgnoreSemanticMetadataAlongsideDisplayName() {
        ObjectNode wrapper = current("배우자: 민지");
        wrapper.set("valueJson", JsonNodeFactory.instance.objectNode().put("value", "배우자: 민지")
                .put("name", "family").put("uncertain", true));

        assertThat(parser.parse("profile.family", wrapper).reviewReasons())
                .contains("UNSUPPORTED_STRUCTURED_CONTEXT");
    }

    @Test
    @DisplayName("표시명 name에서 새로운 관계 명제를 추출하지 않는다")
    void doesNotExtractRelationshipFromDisplayName() {
        ObjectNode wrapper = current("검은 머리");
        wrapper.set("valueJson", JsonNodeFactory.instance.objectNode()
                .put("value", "검은 머리").put("name", "배우자: 민지"));

        assertThat(parser.parse("profile.family", wrapper).relationships()).isEmpty();
    }

    @Test
    @DisplayName("상대명 내부 공백과 기호는 정확한 이름 연결을 위해 그대로 보존한다")
    void preservesExactTargetMention() {
        var result = parser.parse(current("아버지: 장 드라크·마리"));

        assertThat(result.relationships()).singleElement()
                .extracting(ParsedFamilyRelationship::objectMention).isEqualTo("장 드라크·마리");
    }

    @Test
    @DisplayName("입력 한도를 넘으면 앞부분만 해석하지 않고 전체를 보류한다")
    void rejectsOverLimitInputWithoutPartialInterpretation() {
        var tooLong = parser.parse(current("아버지: 홍길동;" + "가".repeat(4096)));
        var tooMany = parser.parse(current(String.join(";", java.util.Collections.nCopies(21, "아버지: 홍길동"))));

        for (FamilyRelationshipParseResult result : List.of(tooLong, tooMany)) {
            assertThat(result.relationships()).isEmpty();
            assertThat(result.reviewReasons()).contains("INPUT_LIMIT_EXCEEDED");
        }
    }

    @Test
    @DisplayName("현재 슬롯 wrapper가 아닌 원시 텍스트를 입력으로 사용하지 않는다")
    void rejectsUnwrappedText() {
        JsonNode raw = JsonNodeFactory.instance.textNode("홍길동의 아들");

        assertThat(parser.parse(raw).relationships()).isEmpty();
        assertThat(parser.parse(raw).reviewReasons()).contains("UNSUPPORTED_CURRENT_VALUE");
    }

    @Test
    @DisplayName("공유 문법 fixture를 실제 파서로 검증한다")
    void executesSharedProcessingFixture() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode fixture = json.readTree(Files.readString(Path.of("docs/contracts/family-relationship-processing-v1.json")));
        assertThat(fixture.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(fixture.path("cases")).hasSize(32);
        for (JsonNode testCase : fixture.path("cases")) {
            String key = testCase.path("factKey").isNull() ? null : testCase.path("factKey").asText();
            FamilyRelationshipParseResult result = parser.parse(key, testCase.path("currentValue"));
            assertThat((JsonNode) json.valueToTree(result)).as(testCase.path("name").asText())
                    .isEqualTo(testCase.path("expected"));
        }
    }

    private ObjectNode current(String value) {
        ObjectNode wrapper = JsonNodeFactory.instance.objectNode().put("factValue", value);
        wrapper.putNull("valueJson");
        return wrapper;
    }
}
