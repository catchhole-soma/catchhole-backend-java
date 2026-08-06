package org.monitoring.catchholebackend.domain.worldsetting.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
