package org.monitoring.catchholebackend.domain.worldsetting.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.exception.WorldSettingErrorCode;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.global.exception.AppException;

@DisplayName("세계관 설정 Entity 단위 테스트")
class WorldSettingTest {

    @Test
    @DisplayName("대상명과 설정명의 앞뒤 공백만 제거하고 내부 공백을 보존한다")
    void createPreservesInternalSpaces() {
        WorldSetting setting = WorldSetting.create(
                work(),
                WorldSettingCategory.LOCATION,
                "  북부 설원  ",
                "  사회 구조  ",
                "  부족 단위로 생활  "
        );

        assertThat(setting.getSubjectName()).isEqualTo("북부 설원");
        assertThat(setting.getNormalizedSubjectName()).isEqualTo("북부 설원");
        assertThat(setting.getPropertyValue("사회 구조")).isEqualTo("부족 단위로 생활");
        assertThat(setting.getVersion()).isZero();
    }

    @Test
    @DisplayName("설정 하나를 추가할 때 기존 설정을 유지하고 버전을 증가시킨다")
    void addPropertyPreservesExistingProperty() {
        WorldSetting setting = worldSetting();

        setting.addProperty("특징", "전투에 특화된 종족");

        assertThat(setting.getPropertyCount()).isEqualTo(2);
        assertThat(setting.getPropertyValue("서식지")).isEqualTo("혹한 지역");
        assertThat(setting.getPropertyValue("특징")).isEqualTo("전투에 특화된 종족");
        assertThat(setting.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("대소문자와 Unicode 정규화가 같은 설정명은 중복으로 거절한다")
    void addPropertyRejectsNormalizedDuplicate() {
        WorldSetting setting = WorldSetting.create(
                work(),
                WorldSettingCategory.POWER_SYSTEM,
                "마법 체계",
                "Mana Rule",
                "적성 검사가 필요하다"
        );

        assertThatThrownBy(() -> setting.addProperty("  mana rule  ", "다른 값"))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(WorldSettingErrorCode.WORLD_SETTING_PROPERTY_DUPLICATED));
    }

    @Test
    @DisplayName("설정 하나를 수정해도 다른 JSON 속성을 덮어쓰지 않는다")
    void updatePropertyChangesOnlySelectedProperty() {
        WorldSetting setting = worldSetting();
        setting.addProperty("특징", "강인함");

        setting.updateProperty("서식지", "생활 지역", "극지방");

        assertThat(setting.getPropertyValue("서식지")).isNull();
        assertThat(setting.getPropertyValue("생활 지역")).isEqualTo("극지방");
        assertThat(setting.getPropertyValue("특징")).isEqualTo("강인함");
        assertThat(setting.getVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("동일한 설정값을 다시 반영하면 버전을 증가시키지 않는다")
    void applyPropertyIsIdempotent() {
        WorldSetting setting = worldSetting();

        boolean changed = setting.applyProperty("서식지", "혹한 지역");

        assertThat(changed).isFalse();
        assertThat(setting.getVersion()).isZero();
    }

    @Test
    @DisplayName("여러 설정을 한 묶음으로 반영하면 실제 변경 수와 무관하게 버전을 한 번만 증가시킨다")
    void applyPropertiesIncrementsVersionOnce() {
        WorldSetting setting = worldSetting();
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("서식지", "극지방");
        properties.put("특징", "강인한 신체");
        properties.put("사회 구조", "부족 단위로 생활");

        boolean changed = setting.applyProperties(properties);

        assertThat(changed).isTrue();
        assertThat(setting.getVersion()).isEqualTo(1);
        assertThat(setting.getPropertyValue("서식지")).isEqualTo("극지방");
        assertThat(setting.getPropertyValue("특징")).isEqualTo("강인한 신체");
        assertThat(setting.getPropertyValue("사회 구조")).isEqualTo("부족 단위로 생활");
    }

    @Test
    @DisplayName("루트 설정과 한 단계 범위 설정을 함께 저장하고 전체 경로로 조회한다")
    void storesRootAndScopedProperties() {
        WorldSetting setting = WorldSetting.create(
                work(),
                WorldSettingCategory.LOCATION,
                "미궁",
                List.of(
                        new WorldSetting.Property(null, "폐쇄 시점", "마왕력 103년"),
                        new WorldSetting.Property("1층", "출몰 규칙", "방향마다 몬스터가 다르다"),
                        new WorldSetting.Property("2층", "출몰 규칙", "중앙부에 언데드가 출몰한다")
                )
        );

        assertThat(setting.getPropertyValue("폐쇄 시점")).isEqualTo("마왕력 103년");
        assertThat(setting.getPropertyValue("1층", "출몰 규칙"))
                .isEqualTo("방향마다 몬스터가 다르다");
        assertThat(setting.getPropertyValue("2층", "출몰 규칙"))
                .isEqualTo("중앙부에 언데드가 출몰한다");
        assertThat(setting.getPropertyCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 설정명은 서로 다른 범위에 추가할 수 있다")
    void allowsSameSettingNameInDifferentScopes() {
        WorldSetting setting = WorldSetting.create(
                work(),
                WorldSettingCategory.LOCATION,
                "미궁",
                "1층",
                "출몰 규칙",
                "고블린이 출몰한다"
        );

        setting.addProperty("2층", "출몰 규칙", "언데드가 출몰한다");

        assertThat(setting.getPropertyValue("1층", "출몰 규칙")).isEqualTo("고블린이 출몰한다");
        assertThat(setting.getPropertyValue("2층", "출몰 규칙")).isEqualTo("언데드가 출몰한다");
    }

    @Test
    @DisplayName("범위와 설정명을 동시에 이동하면 이전 경로를 정리한다")
    void movesPropertyPath() {
        WorldSetting setting = WorldSetting.create(
                work(),
                WorldSettingCategory.LOCATION,
                "미궁",
                "1층",
                "출몰 규칙",
                "고블린이 출몰한다"
        );

        setting.updateProperty("1층", "출몰 규칙", "2층", "등장 규칙", "언데드가 출몰한다");

        assertThat(setting.getPropertyValue("1층", "출몰 규칙")).isNull();
        assertThat(setting.getPropertyValue("2층", "등장 규칙")).isEqualTo("언데드가 출몰한다");
        assertThat(setting.getPropertiesJson().has("1층")).isFalse();
        assertThat(setting.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("루트 설정과 같은 이름의 범위는 경로 충돌로 거절한다")
    void rejectsRootAndScopePathCollision() {
        WorldSetting setting = WorldSetting.create(
                work(),
                WorldSettingCategory.LOCATION,
                "미궁",
                "1층",
                "1층 설명"
        );

        assertThatThrownBy(() -> setting.addProperty("1층", "출몰 규칙", "고블린이 출몰한다"))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(WorldSettingErrorCode.WORLD_SETTING_PROPERTY_PATH_CONFLICT));
    }

    @Test
    @DisplayName("범위가 다른 여러 설정을 한 번에 반영하면 버전을 한 번만 증가시킨다")
    void appliesScopedPropertiesWithSingleVersionIncrement() {
        WorldSetting setting = worldSetting();

        boolean changed = setting.applyProperties(List.of(
                new WorldSetting.Property("1층", "출몰 규칙", "고블린이 출몰한다"),
                new WorldSetting.Property("2층", "출몰 규칙", "언데드가 출몰한다")
        ));

        assertThat(changed).isTrue();
        assertThat(setting.getVersion()).isEqualTo(1);
        assertThat(setting.getPropertyValue("1층", "출몰 규칙")).isEqualTo("고블린이 출몰한다");
        assertThat(setting.getPropertyValue("2층", "출몰 규칙")).isEqualTo("언데드가 출몰한다");
    }

    @Test
    @DisplayName("기존 루트 설정 이동과 새 범위 설정 반영을 한 번에 적용하고 버전을 한 번만 증가시킨다")
    void appliesRootMoveAndNewPropertyWithSingleVersionIncrement() {
        WorldSetting setting = WorldSetting.create(
                work(),
                WorldSettingCategory.RACE,
                "바바리안",
                "생명력",
                "선택 가능한 종족 중 가장 높다"
        );

        boolean changed = setting.applyRootPropertyMovesAndProperties(
                List.of(new WorldSetting.RootPropertyMove("생명력", "신체")),
                List.of(new WorldSetting.Property("신체", "근력 기댓값", "높다"))
        );

        assertThat(changed).isTrue();
        assertThat(setting.getPropertyValue("생명력")).isNull();
        assertThat(setting.getPropertyValue("신체", "생명력"))
                .isEqualTo("선택 가능한 종족 중 가장 높다");
        assertThat(setting.getPropertyValue("신체", "근력 기댓값")).isEqualTo("높다");
        assertThat(setting.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("루트 설정의 이동 목적지가 이미 존재하면 원본과 버전을 변경하지 않는다")
    void rejectsRootMoveDestinationConflictAtomically() {
        WorldSetting setting = WorldSetting.create(
                work(),
                WorldSettingCategory.RACE,
                "바바리안",
                List.of(
                        new WorldSetting.Property(null, "생명력", "기존 루트 값"),
                        new WorldSetting.Property("신체", "생명력", "기존 범위 값")
                )
        );

        assertThatThrownBy(() -> setting.applyRootPropertyMovesAndProperties(
                List.of(new WorldSetting.RootPropertyMove("생명력", "신체")),
                List.of(new WorldSetting.Property("신체", "근력 기댓값", "높다"))
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(WorldSettingErrorCode.WORLD_SETTING_PROPERTY_DUPLICATED));

        assertThat(setting.getPropertyValue("생명력")).isEqualTo("기존 루트 값");
        assertThat(setting.getPropertyValue("신체", "생명력")).isEqualTo("기존 범위 값");
        assertThat(setting.getPropertyValue("신체", "근력 기댓값")).isNull();
        assertThat(setting.getVersion()).isZero();
    }

    private WorldSetting worldSetting() {
        return WorldSetting.create(
                work(),
                WorldSettingCategory.RACE,
                "바바리안",
                "서식지",
                "혹한 지역"
        );
    }

    private Work work() {
        Member member = Member.register(
                "writer@example.com",
                "encoded-password",
                "01012345678",
                "작가"
        );
        return Work.create(member, "설원 전기", WorkGenre.FANTASY, "세계관 설정 테스트");
    }
}
