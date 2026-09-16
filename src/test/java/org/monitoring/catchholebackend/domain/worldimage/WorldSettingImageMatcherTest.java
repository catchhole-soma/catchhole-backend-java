package org.monitoring.catchholebackend.domain.worldimage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.monitoring.catchholebackend.domain.worldimage.entity.WorldImageCatalog;
import org.monitoring.catchholebackend.domain.worldimage.processor.WorldSettingImageMatcher;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;

@DisplayName("세계관 대표 이미지 이름 규칙")
class WorldSettingImageMatcherTest {
    private final WorldSettingImageMatcher matcher = new WorldSettingImageMatcher();
    private WorldImageCatalog image(String name, WorldSettingCategory category, String... aliases) {
        var image = mock(WorldImageCatalog.class);
        when(image.getName()).thenReturn(name); when(image.getCategory()).thenReturn(category);
        when(image.getAliases()).thenReturn(Set.of(aliases)); return image;
    }
    private WorldSetting subject(String name, WorldSettingCategory category) {
        var subject = mock(WorldSetting.class);
        when(subject.getSubjectName()).thenReturn(name); when(subject.getCategory()).thenReturn(category); return subject;
    }
    @Test @DisplayName("분류를 먼저 제한하고 전체 이름·별칭 일치를 우선한다")
    void exactBeforeSuffix() {
        var forest = image("숲", WorldSettingCategory.LOCATION);
        var goblinForest = image("고블린 숲", WorldSettingCategory.LOCATION, "도깨비 숲");
        var goblin = image("고블린", WorldSettingCategory.RACE);
        var images = List.of(forest, goblinForest, goblin);
        assertThat(matcher.match(subject("고블린숲", WorldSettingCategory.LOCATION), images)).isSameAs(goblinForest);
        assertThat(matcher.match(subject("도깨비 숲", WorldSettingCategory.LOCATION), images)).isSameAs(goblinForest);
        assertThat(matcher.match(subject("고블린", WorldSettingCategory.LOCATION), images)).isNull();
    }
    @Test @DisplayName("허용한 지형 접미사와 단어 경계만 연결하고 동률은 기본값으로 남긴다")
    void constrainedSuffixesAndAmbiguity() {
        var forest = image("숲", WorldSettingCategory.LOCATION);
        var cave = image("동굴", WorldSettingCategory.LOCATION);
        var castle = image("성", WorldSettingCategory.LOCATION);
        assertThat(matcher.match(subject("고블린숲", WorldSettingCategory.LOCATION), List.of(forest))).isSameAs(forest);
        assertThat(matcher.match(subject("얼음 동굴", WorldSettingCategory.LOCATION), List.of(cave))).isSameAs(cave);
        assertThat(matcher.match(subject("가능성", WorldSettingCategory.LOCATION), List.of(castle))).isNull();
        assertThat(matcher.match(subject("동굴을 지키는 숲", WorldSettingCategory.LOCATION), List.of(cave, forest))).isSameAs(forest);
        assertThat(matcher.match(subject("고블린 숲", WorldSettingCategory.LOCATION), List.of(forest, image("삼림", WorldSettingCategory.LOCATION, "숲")))).isNull();
    }
}
