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
import org.monitoring.catchholebackend.domain.relationship.type.FamilyTreeRole;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipAssertion;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;

@DisplayName("현재 프로필의 명시적 가족 관계 문법")
class ExplicitFamilyRelationshipParserTest {

    private final ExplicitFamilyRelationshipParser parser = new ExplicitFamilyRelationshipParser();

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {"아르덴의 5대손|5", "아르덴의 5대손이다|5", "아르덴의 12 대손|12",
            "아르덴의 5대손 비칼|5", "아르덴의 5대손인 비칼|5"})
    @DisplayName("명시적 대손 수는 조상 관계 한 건에 보존하며 소유자 이름을 검증한다")
    void preservesExplicitAncestryWithoutInventingParents(String text, int gap) {
        var result = parser.parse("profile.특징", current(text), "비칼");
        assertThat(result.reviewReasons()).isEmpty();
        assertThat(result.relationships()).singleElement().satisfies(claim -> {
            assertThat(claim.kind()).isEqualTo(RelationshipKind.DESCENDANT_OF);
            assertThat(claim.generationGap()).isEqualTo(gap);
            assertThat(claim.objectMention()).isEqualTo("아르덴");
            assertThat(claim.temporalScope()).isEqualTo(RelationshipTemporalScope.CURRENT);
            assertThat(claim.assertion()).isEqualTo(RelationshipAssertion.ASSERTED);
            assertThat(claim.subjectRole()).isNull();
            assertThat(claim.objectRole()).isNull();
        });
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {"아르덴의 후손|DESCENDANT_OF", "조상: 아르덴|DESCENDANT_OF",
            "선조: 아르덴|DESCENDANT_OF", "아르덴의 조상|ANCESTOR_OF", "후손: 아르덴|ANCESTOR_OF"})
    @DisplayName("세대 수 없는 조상·후손은 미상으로 보존하고 부모 관계로 바꾸지 않는다")
    void leavesUnstatedGenerationUnknown(String text, RelationshipKind kind) {
        assertThat(parser.parse(current(text)).relationships()).singleElement().satisfies(claim -> {
            assertThat(claim.kind()).isEqualTo(kind);
            assertThat(claim.generationGap()).isNull();
            assertThat(claim.temporalScope()).isEqualTo(RelationshipTemporalScope.CURRENT);
        });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"아르덴의 5대손 다른사람", "아르덴의 5대손 비칼이 아니다", "아르덴의 후손처럼 강해지고 싶다",
            "아르덴 가문의 후손", "청연가문의 후손", "조상: 청연 가문", "청연 혈통", "아르덴의 0대손", "아르덴의 2147483648대손",
            "아르덴의 5세손", "아르덴의 5대손의 2대손", "아르덴의 비칼의 5대손", "조상: 아르덴의 비칼", "누군가의 후손"})
    @DisplayName("다른 인물·가문·비유·미지원 세대 표기를 명시된 개인 조상으로 잘못 연결하지 않는다")
    void rejectsUnsafeAncestryPhrases(String text) {
        assertThat(parser.parse("profile.가족", current(text), "비칼").relationships()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"왕조현", "클랜시"})
    @DisplayName("개인 이름의 일부가 가문 용어와 같아도 이름을 버리지 않는다")
    void retainsNamesContainingGroupWords(String name) {
        assertThat(parser.parse(current(name + "의 5대손")).relationships()).singleElement()
                .satisfies(claim -> assertThat(claim.objectMention()).isEqualTo(name));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {"아르덴의 5대손이 아니다|NEGATED", "아르덴의 후손이 아니다|NEGATED",
            "아르덴의 후손일지도 모른다|UNCERTAIN", "아르덴의 5대손이라고 한다|UNCERTAIN"})
    @DisplayName("조상·후손 명제의 부정과 추측을 단정으로 승격하지 않는다")
    void retainsAncestryQualifiers(String text, RelationshipAssertion assertion) {
        assertThat(parser.parse(current(text)).relationships()).singleElement().satisfies(claim ->
                assertThat(claim.assertion()).isEqualTo(assertion));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "홍길동의 아들|CHILD_OF|SON|PARENT", "홍길동의 딸|CHILD_OF|DAUGHTER|PARENT",
            "아버지: 홍길동|CHILD_OF|CHILD|FATHER", "어머니: 홍길동|CHILD_OF|CHILD|MOTHER",
            "아들: 홍길동|PARENT_OF|PARENT|SON", "딸: 홍길동|PARENT_OF|PARENT|DAUGHTER",
            "홍길동의 아버지|PARENT_OF|FATHER|CHILD", "홍길동의 어머니|PARENT_OF|MOTHER|CHILD",
            "양부: 홍길동|CHILD_OF|CHILD|FATHER", "양녀: 홍길동|PARENT_OF|PARENT|DAUGHTER",
            "홍길동의 의붓아들|CHILD_OF|SON|PARENT", "자녀: 홍길동|PARENT_OF|PARENT|CHILD"
    })
    @DisplayName("명시된 가족 호칭을 원래 방향의 정확한 끝점에 남기고 반대편 호칭을 추론하지 않는다")
    void retainsExplicitEndpointRoles(String text, RelationshipKind kind, FamilyTreeRole subjectRole, FamilyTreeRole objectRole) {
        assertThat(parser.parse(current(text)).relationships()).singleElement().satisfies(claim -> {
            assertThat(claim.kind()).isEqualTo(kind);
            assertThat(claim.subjectRole()).isEqualTo(subjectRole);
            assertThat(claim.objectRole()).isEqualTo(objectRole);
        });
    }

    @Test
    @DisplayName("같은 관계의 서로 다른 끝점 호칭은 표시 시 상충 판단을 위해 모두 보존한다")
    void retainsConflictingExplicitRoleWords() {
        assertThat(parser.parse(current("아버지: 홍길동; 어머니: 홍길동")).relationships())
                .extracting(ParsedFamilyRelationship::objectRole).containsExactly(FamilyTreeRole.FATHER, FamilyTreeRole.MOTHER);
        assertThat(parser.parse(current("배우자: 홍길동")).relationships()).singleElement().satisfies(claim -> {
            assertThat(claim.subjectRole()).isNull();
            assertThat(claim.objectRole()).isNull();
        });
    }

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

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "첫째 아들|SON", "둘째 아들|SON", "셋째 딸|DAUGHTER", "넷째 아들|SON",
            "두 번째 딸|DAUGHTER", "세 번째 아들|SON", "제2 아들|SON", "2번째 딸|DAUGHTER",
            "외동아들|SON", "외동 딸|DAUGHTER", "막내아들|SON", "막내 딸|DAUGHTER",
            "맏아들|SON", "맏딸|DAUGHTER", "두 번째 자녀|CHILD", "넷째 자식|CHILD",
            "장남|SON", "차남|SON", "장녀|DAUGHTER", "차녀|DAUGHTER"
    })
    @DisplayName("서수·출생 순서 수식어는 자녀 방향과 명시된 호칭을 보존하며 추가 형제를 만들지 않는다")
    void parsesBirthOrderWithoutInventingAdditionalChildren(String role, FamilyTreeRole subjectRole) {
        var result = parser.parse("profile.출생_순서", current("현우의 " + role));

        assertThat(result.reviewReasons()).isEmpty();
        assertThat(result.relationships()).singleElement().satisfies(claim -> {
            assertThat(claim.objectMention()).isEqualTo("현우");
            assertThat(claim.kind()).isEqualTo(RelationshipKind.CHILD_OF);
            assertThat(claim.assertion()).isEqualTo(RelationshipAssertion.ASSERTED);
            assertThat(claim.subjectRole()).isEqualTo(subjectRole);
            assertThat(claim.objectRole()).isEqualTo(FamilyTreeRole.PARENT);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"언니", "동생", "형", "오빠", "누나", "여동생", "남동생"})
    @DisplayName("단순 인명의 소유격으로 명시된 형제 관계는 일상 호칭과 구분하여 읽는다")
    void acceptsSiblingRelationshipToNamedPerson(String role) {
        var result = parser.parse("profile.가족관계", current("은하의 " + role));

        assertThat(result.reviewReasons()).isEmpty();
        assertThat(result.relationships()).singleElement().satisfies(claim -> {
            assertThat(claim.objectMention()).isEqualTo("은하");
            assertThat(claim.kind()).isEqualTo(RelationshipKind.SIBLING);
            assertThat(claim.assertion()).isEqualTo(RelationshipAssertion.ASSERTED);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"6등급 탐험가", "6등급 탐험가이다", "6등급 탐험가입니다", "6등급 탐험가임"})
    @DisplayName("가족 관계 뒤의 지원하는 직업 명사구를 인명에 섞거나 관계를 버리지 않는다")
    void acceptsSupportedOccupationDescriptionAfterRelationship(String description) {
        var result = parser.parse("profile.특징", current("은하의 언니인 " + description));

        assertThat(result.reviewReasons()).isEmpty();
        assertThat(result.relationships()).singleElement().satisfies(claim -> {
            assertThat(claim.objectMention()).isEqualTo("은하");
            assertThat(claim.kind()).isEqualTo(RelationshipKind.SIBLING);
            assertThat(claim.assertion()).isEqualTo(RelationshipAssertion.ASSERTED);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"청암 클랜 수인 남성", "청암 클랜 수인 여성", "숲에 사는 사람", "이웃 마을 남자", "이웃 마을 여자", "옆집 인물"})
    @DisplayName("설명으로만 지칭한 형제 상대는 관계를 발견하되 이름처럼 자동 확정하지 않는다")
    void holdsSiblingWithDescriptiveCounterpart(String mention) {
        var result = parser.parse("profile.가족관계", current(mention + "의 동생"));

        assertThat(result.relationships()).singleElement().satisfies(claim -> {
            assertThat(claim.objectMention()).isEqualTo(mention);
            assertThat(claim.kind()).isEqualTo(RelationshipKind.SIBLING);
            assertThat(claim.assertion()).isEqualTo(RelationshipAssertion.UNCERTAIN);
            assertThat(claim.reviewReasons()).contains("AMBIGUOUS_FAMILY_ROLE");
        });
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "청암 클랜 수인 남성의 아들|CHILD_OF|UNSPECIFIED",
            "청암 클랜 수인 남성의 딸|CHILD_OF|UNSPECIFIED",
            "청암 클랜 수인 남성의 두 번째 자녀|CHILD_OF|UNSPECIFIED",
            "청암 클랜 수인 남성의 아버지|PARENT_OF|UNSPECIFIED",
            "청암 클랜 수인 남성의 어머니|PARENT_OF|UNSPECIFIED",
            "청암 클랜 수인 남성의 아내|SPOUSE|UNSPECIFIED",
            "청암 클랜 수인 남성의 친형제|SIBLING|BIOLOGICAL",
            "아버지: 청암 클랜 수인 남성|CHILD_OF|UNSPECIFIED",
            "어머니: 청암 클랜 수인 남성|CHILD_OF|UNSPECIFIED",
            "아들: 청암 클랜 수인 남성|PARENT_OF|UNSPECIFIED",
            "딸: 청암 클랜 수인 남성|PARENT_OF|UNSPECIFIED",
            "남편: 청암 클랜 수인 남성|SPOUSE|UNSPECIFIED",
            "양부: 청암 클랜 수인 남성|CHILD_OF|ADOPTIVE",
            "형제: 청암 클랜 수인 남성|SIBLING|UNSPECIFIED"
    })
    @DisplayName("설명형 상대는 가족 역할과 서술 방향에 관계없이 확인 대상으로 남긴다")
    void holdsDescriptiveCounterpartForEveryFamilyRole(String text, RelationshipKind kind, RelationshipSubtype subtype) {
        var result = parser.parse("profile.가족관계", current(text));

        assertThat(result.reviewReasons()).containsExactly("AMBIGUOUS_FAMILY_ROLE");
        assertThat(result.relationships()).singleElement().satisfies(claim -> {
            assertThat(claim.objectMention()).isEqualTo("청암 클랜 수인 남성");
            assertThat(claim.kind()).isEqualTo(kind);
            assertThat(claim.subtype()).isEqualTo(subtype);
            assertThat(claim.assertion()).isEqualTo(RelationshipAssertion.UNCERTAIN);
            assertThat(claim.reviewReasons()).containsExactly("AMBIGUOUS_FAMILY_ROLE");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"아버지", "어머니", "아들", "딸", "배우자", "친형제"})
    @DisplayName("항목명으로 가족 역할을 보완해도 설명형 상대는 확인 대상으로 남긴다")
    void holdsDescriptiveCounterpartFromRoleKey(String role) {
        assertThat(parser.parse("profile." + role, current("이웃 마을 여성")).relationships())
                .singleElement().satisfies(claim -> {
                    assertThat(claim.assertion()).isEqualTo(RelationshipAssertion.UNCERTAIN);
                    assertThat(claim.reviewReasons()).containsExactly("AMBIGUOUS_FAMILY_ROLE");
                });
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "청암 클랜 수인 남성의 아들이 아니다|NEGATED",
            "아버지: 청암 클랜 수인 남성이 아님|NEGATED",
            "청암 클랜 수인 남성의 아버지 같은 존재|FIGURATIVE",
            "청암 클랜 수인 남성의 아들로 추정된다|UNCERTAIN"
    })
    @DisplayName("설명형 상대의 부정·비유·추측을 일반적인 확인 사유로 덮어쓰지 않는다")
    void preservesNonAssertedDescriptiveClaims(String text, RelationshipAssertion assertion) {
        assertThat(parser.parse(current(text)).relationships()).singleElement().satisfies(claim -> {
            assertThat(claim.assertion()).isEqualTo(assertion);
            assertThat(claim.reviewReasons()).containsExactly(assertion.name());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"김남성", "한여성", "남자인", "사람온"})
    @DisplayName("설명 어휘가 이름 일부에 포함되어도 독립된 설명 표현이 아니면 명시 관계를 유지한다")
    void acceptsNamesContainingDescriptiveWords(String name) {
        var result = parser.parse(current(name + "의 아들"));

        assertThat(result.reviewReasons()).isEmpty();
        assertThat(result.relationships()).singleElement().satisfies(claim -> {
            assertThat(claim.objectMention()).isEqualTo(name);
            assertThat(claim.assertion()).isEqualTo(RelationshipAssertion.ASSERTED);
        });
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "현우의 넷째 아들이 아니다|NEGATED", "현우의 두 번째 딸로 추정된다|UNCERTAIN",
            "은하의 언니처럼 여긴다|FIGURATIVE"
    })
    @DisplayName("확장 문법에서도 부정·추측·비유의 의미를 보존한다")
    void preservesAssertionsAfterNewlySupportedGrammar(String value, RelationshipAssertion assertion) {
        var result = parser.parse("profile.가족관계", current(value));

        assertThat(result.relationships()).singleElement().satisfies(claim -> {
            assertThat(claim.assertion()).isEqualTo(assertion);
            assertThat(claim.reviewReasons()).contains(assertion.name());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "현우의 넷째 아들인 줄 알았으나 아니었다", "은하의 언니인 척하는 탐험가",
            "은하의 언니인 것으로 추정되는 탐험가", "은하의 언니인 탐험가가 아니다",
            "은하의 언니인 탐험가의 아들", "은하의 언니인 탐험가와 동행",
            "은하의 언니 같은 탐험가", "은하의 언니인 탐험가이며 현우의 딸",
            "현우의 넷째 아들의 딸", "현우의 둘째 딸의 세 번째 아들"
    })
    @DisplayName("관계 뒤의 미지원 서술·정정·중첩 관계를 잘라 앞부분만 자동 확정하지 않는다")
    void refusesUnrecognizedSuffixesInsteadOfTruncatingClaim(String value) {
        var result = parser.parse("profile.특징", current(value));

        assertThat(result.familyMentioned()).isTrue();
        assertThat(result.reviewReasons()).isNotEmpty();
        assertThat(result.relationships()).noneMatch(claim -> claim.assertion() == RelationshipAssertion.ASSERTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"현우의 넷째 아버지", "현우의 두 번째 어머니", "현우의 2번째 배우자", "현우의 막내 남편"})
    @DisplayName("자녀의 출생 순서 규칙을 부모·배우자에 무분별하게 적용하지 않는다")
    void rejectsBirthOrderBeforeUnsupportedFamilyRoles(String value) {
        var result = parser.parse("profile.가족관계", current(value));

        assertThat(result.familyMentioned()).isTrue();
        assertThat(result.relationships()).isEmpty();
        assertThat(result.reviewReasons()).contains("UNSUPPORTED_FAMILY_SYNTAX");
    }

    @ParameterizedTest
    @ValueSource(strings = {"아버지: 사망", "부모: 모름", "아들 둘", "부모는 사망했고 아버지의 얼굴을 모름",
            "청연 가문의 직계", "학교에서 만난 은하의 언니", "아버지: 고향에서 살고 있음"})
    @DisplayName("상대 없는 가족 상황과 혈통 표현을 이름이나 특정 부모 관계로 만들지 않는다")
    void detectsFamilyInformationWithoutInventingPeople(String value) {
        var result = parser.parse("profile.가족", current(value));

        assertThat(result.familyMentioned()).isTrue();
        assertThat(result.relationships()).isEmpty();
        assertThat(result.reviewReasons()).isNotEmpty();
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
        assertThat(fixture.path("cases")).hasSize(40);
        for (JsonNode testCase : fixture.path("cases")) {
            String key = testCase.path("factKey").isNull() ? null : testCase.path("factKey").asText();
            FamilyRelationshipParseResult result = parser.parse(key, testCase.path("currentValue"),
                    testCase.has("ownerName") ? testCase.path("ownerName").asText() : null);
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
