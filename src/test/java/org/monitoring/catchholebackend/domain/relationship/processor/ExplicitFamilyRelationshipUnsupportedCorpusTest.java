package org.monitoring.catchholebackend.domain.relationship.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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

/** 문법 경계만 재현한 합성 사례다. 실제 작품의 인물·설정·원문을 fixture로 저장하지 않는다. */
@DisplayName("미지원 가족 설정 유형의 합성 회귀 사례")
class ExplicitFamilyRelationshipUnsupportedCorpusTest {

    private final ExplicitFamilyRelationshipParser parser = new ExplicitFamilyRelationshipParser();

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "profile.father|양아버지 담온|CHILD_OF|ADOPTIVE|CHILD|FATHER",
            "profile.children|아들 담온|PARENT_OF|UNSPECIFIED|PARENT|SON",
            "profile.children|외동아들 담온|PARENT_OF|UNSPECIFIED|PARENT|SON",
            "profile.가족_관계|담온의 서녀|CHILD_OF|UNSPECIFIED|DAUGHTER|PARENT"
    })
    @DisplayName("가족 항목의 역할과 인명은 방향과 명시 호칭을 보존하고 생략된 성별을 추론하지 않는다")
    void readsRoleAndNameInFamilySlot(String key, String text, RelationshipKind kind,
            RelationshipSubtype subtype, FamilyTreeRole subjectRole, FamilyTreeRole objectRole) {
        var result = parser.parse(key, current(text), "여솔");

        assertThat(result.relationships()).singleElement().satisfies(claim -> {
            assertThat(claim.objectMention()).isEqualTo("담온");
            assertThat(claim.kind()).isEqualTo(kind);
            assertThat(claim.subtype()).isEqualTo(subtype);
            assertThat(claim.subjectRole()).isEqualTo(subjectRole);
            assertThat(claim.objectRole()).isEqualTo(objectRole);
            assertThat(claim.assertion()).isEqualTo(RelationshipAssertion.ASSERTED);
            assertThat(claim.temporalScope()).isEqualTo(RelationshipTemporalScope.CURRENT);
        });
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "담온과 형제 관계|ASSERTED",
            "하나뿐인 언니 담온|UNCERTAIN",
            "동생 담온|UNCERTAIN",
            "오빠 담온|UNCERTAIN",
            "담온의 쌍둥이 동생|ASSERTED",
            "담온의 쌍둥이 형|ASSERTED",
            "담온의 오라버니|ASSERTED"
    })
    @DisplayName("형제 문법을 넓혀도 일상 호칭의 검토와 명시하지 않은 세부 유형을 유지한다")
    void readsSiblingGrammarWithoutInventingParents(String text, RelationshipAssertion assertion) {
        var result = parser.parse("profile.siblings", current(text), "여솔");

        assertThat(result.relationships()).singleElement().satisfies(claim -> {
            assertThat(claim.objectMention()).isEqualTo("담온");
            assertThat(claim.kind()).isEqualTo(RelationshipKind.SIBLING);
            assertThat(claim.subtype()).isEqualTo(RelationshipSubtype.UNSPECIFIED);
            assertThat(claim.assertion()).isEqualTo(assertion);
            assertThat(claim.subjectRole()).isNull();
            assertThat(claim.objectRole()).isNull();
            assertThat(claim.generationGap()).isNull();
        });
    }

    @Test
    @DisplayName("현재 소유자의 정확한 이름으로 끝나는 자녀 명사구는 다른 형제를 만들지 않는다")
    void readsExactOwnerSuffix() {
        var result = parser.parse("profile.정식_호칭", current("담온의 두 번째 딸 여솔"), "여솔");

        assertThat(result.relationships()).singleElement().satisfies(claim -> {
            assertThat(claim.objectMention()).isEqualTo("담온");
            assertThat(claim.kind()).isEqualTo(RelationshipKind.CHILD_OF);
            assertThat(claim.subjectRole()).isEqualTo(FamilyTreeRole.DAUGHTER);
            assertThat(claim.objectRole()).isEqualTo(FamilyTreeRole.PARENT);
            assertThat(claim.assertion()).isEqualTo(RelationshipAssertion.ASSERTED);
        });
    }

    @Test
    @DisplayName("출생 순서가 붙은 가족 항목명에서 실제로 이름이 있는 자녀 한 명만 읽는다")
    void readsModifiedRoleKey() {
        var result = parser.parse("profile.막내아들", current("담온"), "여솔");

        assertThat(result.relationships()).singleElement().satisfies(claim -> {
            assertThat(claim.objectMention()).isEqualTo("담온");
            assertThat(claim.kind()).isEqualTo(RelationshipKind.PARENT_OF);
            assertThat(claim.subjectRole()).isEqualTo(FamilyTreeRole.PARENT);
            assertThat(claim.objectRole()).isEqualTo(FamilyTreeRole.SON);
            assertThat(claim.assertion()).isEqualTo(RelationshipAssertion.ASSERTED);
        });
    }

    @Test
    @DisplayName("쉼표로 나열한 완결된 가족 항목마다 인명과 방향을 따로 보존한다")
    void readsCompleteLabeledEnumeration() {
        var result = parser.parse("profile.가족_관계",
                current("아버지는 담온, 어머니는 여솔, 여동생은 채림"), "모래빛");

        assertThat(result.relationships()).hasSize(3);
        assertThat(result.relationships()).filteredOn(claim -> claim.kind() == RelationshipKind.CHILD_OF)
                .extracting(ParsedFamilyRelationship::objectMention).containsExactlyInAnyOrder("담온", "여솔");
        assertThat(result.relationships()).filteredOn(claim -> claim.objectMention().equals("담온"))
                .singleElement().satisfies(claim -> assertThat(claim.objectRole()).isEqualTo(FamilyTreeRole.FATHER));
        assertThat(result.relationships()).filteredOn(claim -> claim.objectMention().equals("여솔"))
                .singleElement().satisfies(claim -> assertThat(claim.objectRole()).isEqualTo(FamilyTreeRole.MOTHER));
        assertThat(result.relationships()).filteredOn(claim -> claim.objectMention().equals("채림"))
                .singleElement().satisfies(claim -> {
                    assertThat(claim.kind()).isEqualTo(RelationshipKind.SIBLING);
                    assertThat(claim.assertion()).isEqualTo(RelationshipAssertion.UNCERTAIN);
                });
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "담온의 아내이자 여솔과 채림의 어머니|MOTHER",
            "여솔과 채림의 아버지이자 담온의 남편|FATHER"
    })
    @DisplayName("배우자와 복수 자녀를 함께 적어도 인명을 합치거나 관계 방향을 뒤집지 않는다")
    void readsCompleteConjoinedFamilyClaims(String text, FamilyTreeRole parentRole) {
        var result = parser.parse("profile.가족_관계", current(text), "모래빛");

        assertThat(result.relationships()).hasSize(3).allSatisfy(claim ->
                assertThat(claim.assertion()).isEqualTo(RelationshipAssertion.ASSERTED));
        assertThat(result.relationships()).filteredOn(claim -> claim.kind() == RelationshipKind.SPOUSE)
                .singleElement().satisfies(claim -> assertThat(claim.objectMention()).isEqualTo("담온"));
        assertThat(result.relationships()).filteredOn(claim -> claim.kind() == RelationshipKind.PARENT_OF)
                .extracting(ParsedFamilyRelationship::objectMention).containsExactlyInAnyOrder("여솔", "채림");
        assertThat(result.relationships()).filteredOn(claim -> claim.kind() == RelationshipKind.PARENT_OF)
                .allSatisfy(claim -> {
                    assertThat(claim.subjectRole()).isEqualTo(parentRole);
                    assertThat(claim.objectRole()).isEqualTo(FamilyTreeRole.CHILD);
                });
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "profile.children|자녀 셋이 먼 도시에서 생활함",
            "profile.가족_관계|부모를 일찍 여의고 자람",
            "profile.birth_order|외동아들",
            "profile.siblings|아직 이름이 알려지지 않은 동생이 있음",
            "profile.family_lineage|은무리 가문의 자제",
            "profile.affiliation|은무리 가문",
            "profile.social_status|은무리 가문의 피후견인",
            "profile.long_term_goal|가족을 얻는 것이 평생의 바람",
            "profile.alias|바람의 딸 은빛 종달새",
            "profile.personality|담온을 가족처럼 의지하며 지냄",
            "profile.위장_가족관계|담온의 사촌인 척함",
            "profile.title|담온 형 바라기",
            "profile.가족_관계|담온의 숙부"
    })
    @DisplayName("상대 없는 설정·가문·비유·위장·지원 밖 친족에서 임의의 확정 관계를 만들지 않는다")
    void doesNotInventRelationsFromFamilyWords(String key, String text) {
        var result = parser.parse(key, current(text), "모래빛");

        assertThat(result.relationships()).noneMatch(claim -> claim.assertion() == RelationshipAssertion.ASSERTED);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "담온의 외아들이며 여솔의 사촌",
            "담온의 아들이자 여솔의 외종형",
            "담온과 여솔의 아들이며 채림의 형이자 윤슬의 약혼자"
    })
    @DisplayName("지원 밖 관계가 섞이면 이해한 부분만 잘라 자동 확정하지 않는다")
    void retainsUnsupportedContextAcrossConjunctions(String text) {
        var result = parser.parse("profile.가족_관계", current(text), "모래빛");

        assertThat(result.relationships()).noneMatch(claim -> claim.assertion() == RelationshipAssertion.ASSERTED);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"담온의 아들 여솔", "담온의 딸 채림"})
    @DisplayName("소유자의 축약명이나 다른 이름을 같은 인물이라고 추측하지 않는다")
    void rejectsShortenedOrDifferentOwnerSuffix(String text) {
        var result = parser.parse("profile.정식_호칭", current(text), "여솔 아라온");

        assertThat(result.relationships()).isEmpty();
    }

    @Test
    @DisplayName("표시값과 구조값이 서로 다르면 새 문법을 읽어도 단정으로 승격하지 않는다")
    void preservesCurrentValueConflictAfterGrammarExpansion() {
        var value = JsonNodeFactory.instance.objectNode().put("factValue", "아들 담온");
        value.putObject("valueJson").put("value", "아들 여솔");

        var result = parser.parse("profile.children", value, "모래빛");

        assertThat(result.reviewReasons()).contains("CURRENT_VALUE_CONFLICT");
        assertThat(result.relationships()).isNotEmpty().allSatisfy(claim ->
                assertThat(claim.assertion()).isEqualTo(RelationshipAssertion.CONFLICTING));
    }

    private static JsonNode current(String text) {
        var value = JsonNodeFactory.instance.objectNode().put("factValue", text);
        value.putObject("valueJson").put("value", text);
        return value;
    }
}
