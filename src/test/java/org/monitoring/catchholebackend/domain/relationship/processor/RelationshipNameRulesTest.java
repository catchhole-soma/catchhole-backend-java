package org.monitoring.catchholebackend.domain.relationship.processor;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 기대값은 AI {@code character_name_resolver}·{@code ordered_character_subjects}를 같은 입력으로 실행한 결과다. */
@DisplayName("관계 끝점 이름 규칙은 캐릭터 추출의 정규화·제외 단어·포함 규칙과 같다")
class RelationshipNameRulesTest {
    private final UUID bjorn = UUID.randomUUID(), ragnar = UUID.randomUUID(), ara = UUID.randomUUID();

    @Test
    @DisplayName("앞뒤 공백·따옴표·괄호를 떼고 공백 뭉치를 한 칸으로, 대문자를 소문자로 바꾼다")
    void normalizesLikeExtraction() {
        assertThat(RelationshipNameRules.normalize("  “비요른   얀델”  ")).isEqualTo("비요른 얀델");
        assertThat(RelationshipNameRules.normalize("Bjorn  YANDEL")).isEqualTo("bjorn yandel");
        assertThat(RelationshipNameRules.normalize("비요른\t얀델")).isEqualTo("비요른 얀델");
        assertThat(RelationshipNameRules.normalize(" 아라 ")).isEqualTo("아라");
        assertThat(RelationshipNameRules.normalize("'\"아라\"'")).isEqualTo("아라");
        assertThat(RelationshipNameRules.normalize("`아라`")).isEqualTo("아라");
        assertThat(RelationshipNameRules.normalize(null)).isEmpty();
        // 원본처럼 괄호를 뗀 뒤 다시 trim하지 않는다.
        assertThat(RelationshipNameRules.normalize("《 비요른 얀델 》")).isEqualTo(" 비요른 얀델 ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"비요른", "나은", "그로", "린"})
    @DisplayName("구체 이름과 지칭어에 우연히 조사가 겹친 이름은 비교에 쓴다")
    void concreteNamesAreUsable(String name) {
        assertThat(RelationshipNameRules.usable(RelationshipNameRules.normalize(name))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "미상", "unknown", "Unknown", "그녀", "그 남자", "나", "주인공은", "그녀에게는",
            "주인공에게는", "그녀로", "주인공의", "내 캐릭터는"})
    @DisplayName("placeholder·대명사와 조사가 붙은 지칭어는 비교하지 않는다")
    void pronounsAndPlaceholdersAreNotUsable(String name) {
        assertThat(RelationshipNameRules.usable(RelationshipNameRules.normalize(name))).isFalse();
    }

    @Test
    @DisplayName("2글자 이상에서 한쪽이 다른 쪽을 포함하면 겹치고, 같은 이름은 길이와 무관하게 겹친다")
    void overlapIsSymmetricContainmentOfTwoOrMoreCharacters() {
        assertThat(RelationshipNameRules.overlaps("비요른", "비요른 얀델")).isTrue();
        assertThat(RelationshipNameRules.overlaps("비요른 얀델 공작", "비요른 얀델")).isTrue();
        assertThat(RelationshipNameRules.overlaps("얀", "비요른 얀델")).isFalse();
        assertThat(RelationshipNameRules.overlaps("아라", "다라")).isFalse();
        assertThat(RelationshipNameRules.overlaps("보라", "아라")).isFalse();
        assertThat(RelationshipNameRules.overlaps("아라 린", "아라 린")).isTrue();
        assertThat(RelationshipNameRules.overlaps("린", "린")).isTrue();
    }

    @Test
    @DisplayName("관측 이름이 확정 이름 한 프로필에만 포함 관계로 겹치면 그 프로필로 해소한다")
    void containedNameResolvesToTheOnlyOverlappingProfile() {
        var resolved = RelationshipNameRules.resolve(
                Map.of("비요른 얀델", List.of(bjorn), "얀델", List.of(bjorn)), List.of("비요른", "보라"), List.of());

        assertThat(resolved).containsEntry("비요른", List.of(bjorn)).doesNotContainKey("보라");
        // 확정 이름 자체는 그대로 남아 기존 정확 일치 흐름이 쓴다.
        assertThat(resolved).containsEntry("비요른 얀델", List.of(bjorn)).containsEntry("얀델", List.of(bjorn));
    }

    @Test
    @DisplayName("겹치는 프로필이 둘 이상이면 모두 돌려주어 보류한다")
    void containedNameOverlappingTwoProfilesHolds() {
        var resolved = RelationshipNameRules.resolve(
                Map.of("비요른 얀델", List.of(bjorn), "비요른 라프손", List.of(ragnar)), List.of("비요른"), List.of());

        assertThat(resolved.get("비요른")).containsExactlyInAnyOrder(bjorn, ragnar);
    }

    @Test
    @DisplayName("정확 일치가 있으면 포함 비교를 하지 않는다")
    void exactNameWinsOverContainment() {
        var resolved = RelationshipNameRules.resolve(
                Map.of("아라", List.of(ara), "아라 린", List.of(ragnar)), List.of("아라"), List.of());

        assertThat(resolved).containsEntry("아라", List.of(ara));
    }

    @Test
    @DisplayName("정규화 결과가 같은 이름은 정확 일치로 모은다")
    void exactMatchComparesNormalizedNames() {
        var resolved = RelationshipNameRules.resolve(
                Map.of("Bjorn", List.of(bjorn), "BJORN", List.of(ragnar)), List.of("bjorn"), List.of());

        assertThat(resolved.get("bjorn")).containsExactlyInAnyOrder(bjorn, ragnar);
        assertThat(resolved.get("Bjorn")).containsExactlyInAnyOrder(bjorn, ragnar);
    }

    @Test
    @DisplayName("대명사·1글자 관측과 대명사 확정 이름은 포함 비교에 쓰지 않는다")
    void pronounsAndOneCharacterNamesAreNotContained() {
        var resolved = RelationshipNameRules.resolve(
                Map.of("그녀의 기사", List.of(bjorn), "린 아라", List.of(ara), "그녀", List.of(ragnar)),
                List.of("그녀", "린", "그녀에게는", "기사"), List.of());

        assertThat(resolved).doesNotContainKeys("린", "그녀에게는");
        // 확정 이름과 정확히 같은 지칭어는 기존처럼 정확 일치로 남는다.
        assertThat(resolved).containsEntry("그녀", List.of(ragnar));
        assertThat(resolved).containsEntry("기사", List.of(bjorn));
    }

    private final UUID hans = UUID.randomUUID(), hansHodge = UUID.randomUUID();

    /** 운영 사본의 모양: "한스 호지" 발견 별칭은 프로필 "한스"에, 같은 이름의 프로필은 따로 있다. */
    private Map<String, List<UUID>> hansConfirmed() {
        return Map.of("한스", List.of(hans), "한스 호지", List.of(hans),
                "얀델", List.of(bjorn), "비요른 얀델", List.of(bjorn));
    }

    private List<RelationshipNameRules.ProfileName> currentProfiles() {
        return List.of(new RelationshipNameRules.ProfileName(hans, "한스"),
                new RelationshipNameRules.ProfileName(hansHodge, "한스 호지"),
                new RelationshipNameRules.ProfileName(bjorn, "비요른 얀델"));
    }

    @Test
    @DisplayName("확정 이름과 정확히 같은 현재 프로필 이름이 다른 프로필에 있으면 보류한다")
    void currentProfileWithSameExactNameHolds() {
        var resolved = RelationshipNameRules.resolve(hansConfirmed(), List.of("한스 호지"), currentProfiles());

        assertThat(resolved.get("한스 호지")).containsExactlyInAnyOrder(hans, hansHodge);
    }

    @Test
    @DisplayName("포함 비교에서 현재 프로필 이름이 다른 프로필로 겹치면 보류한다")
    void currentProfileOverlappingContainedNameHolds() {
        var resolved = RelationshipNameRules.resolve(hansConfirmed(), List.of("호지"), currentProfiles());

        assertThat(resolved.get("호지")).containsExactlyInAnyOrder(hans, hansHodge);
    }

    @Test
    @DisplayName("정확 일치가 확정 이름과 현재 이름 모두 한 프로필이면 포함으로 겹치는 다른 프로필이 있어도 해소한다")
    void exactNameWinsOverCurrentProfileContainment() {
        var resolved = RelationshipNameRules.resolve(hansConfirmed(), List.of("한스", "비요른"), currentProfiles());

        assertThat(resolved).containsEntry("한스", List.of(hans)).containsEntry("비요른", List.of(bjorn));
    }

    @Test
    @DisplayName("현재 프로필 이름만 겹치면 해소하지 않는다")
    void currentProfileNameAloneDoesNotResolve() {
        var resolved = RelationshipNameRules.resolve(Map.of(), List.of("비요른"),
                List.of(new RelationshipNameRules.ProfileName(bjorn, "비요른 얀델")));
        // 확정 이름이 "얀델"뿐이면 "비요른"과 겹치는 것은 현재 이름뿐이다.
        var aliasOnly = RelationshipNameRules.resolve(Map.of("얀델", List.of(bjorn)), List.of("비요른"),
                List.of(new RelationshipNameRules.ProfileName(bjorn, "비요른 얀델")));

        assertThat(resolved).doesNotContainKey("비요른");
        assertThat(aliasOnly).doesNotContainKey("비요른");
    }

    @Test
    @DisplayName("GH391 엄격 해소는 정확 일치에서 멈추지 않고 포함으로 겹치는 다른 프로필까지 모아 보류한다")
    void strictResolutionUnitesExactAndContainedProfiles() {
        // resolve는 "한스"를 정확 일치로 끝내지만, 엄격 해소는 "한스 호지" 프로필에 포함되는 것도 본다.
        assertThat(RelationshipNameRules.resolve(hansConfirmed(), List.of("한스"), currentProfiles()))
                .containsEntry("한스", List.of(hans));
        assertThat(RelationshipNameRules.resolveStrictly("한스", hansConfirmed(), currentProfiles()))
                .containsExactlyInAnyOrder(hans, hansHodge);
        assertThat(RelationshipNameRules.resolveStrictly("한스 호지", hansConfirmed(), currentProfiles()))
                .containsExactlyInAnyOrder(hans, hansHodge);

        UUID erwen = UUID.randomUUID(), daria = UUID.randomUUID();
        var tersia = RelationshipNameRules.resolveStrictly("테르시아", Map.of("테르시아", List.of(erwen)),
                List.of(new RelationshipNameRules.ProfileName(erwen, "에르웬 포르나치 디 테르시아"),
                        new RelationshipNameRules.ProfileName(daria, "다리아 위트엠버 디 테르시아")));
        assertThat(tersia).containsExactlyInAnyOrder(erwen, daria);
    }

    @Test
    @DisplayName("GH391 엄격 해소는 확정 이름이 뒷받침하는 프로필 하나만 해소하고 현재 이름만 겹치면 해소하지 않는다")
    void strictResolutionNeedsConfirmedAnchor() {
        var profiles = List.of(new RelationshipNameRules.ProfileName(bjorn, "비요른 얀델"),
                new RelationshipNameRules.ProfileName(ragnar, "브라운 로트밀러"));

        assertThat(RelationshipNameRules.resolveStrictly("로트밀러", Map.of("브라운 로트밀러", List.of(ragnar)), profiles))
                .containsExactly(ragnar);
        assertThat(RelationshipNameRules.resolveStrictly("한스C", hansConfirmed(), currentProfiles()))
                .containsExactly(hans);
        assertThat(RelationshipNameRules.resolveStrictly("비요른", Map.of(), profiles)).isEmpty();
        assertThat(RelationshipNameRules.resolveStrictly("그녀", Map.of("그녀의 기사", List.of(bjorn)), profiles))
                .isEmpty();
        assertThat(RelationshipNameRules.resolveStrictly("보라", hansConfirmed(), currentProfiles())).isEmpty();
    }
}
