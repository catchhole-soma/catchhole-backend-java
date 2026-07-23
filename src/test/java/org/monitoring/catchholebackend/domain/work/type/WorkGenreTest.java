package org.monitoring.catchholebackend.domain.work.type;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("작품 장르 enum 테스트")
class WorkGenreTest {

    @Test
    @DisplayName("지원하는 열 가지 장르의 한글 표시값을 순서대로 제공한다")
    void genresProvideSupportedDisplayNames() {
        assertThat(WorkGenre.values())
                .extracting(WorkGenre::getToKorean)
                .containsExactly(
                        "판타지", "로맨스", "추리", "코미디", "SF",
                        "스포츠", "호러", "무협", "일상", "기타"
                );
    }

    @Test
    @DisplayName("한글 API 값과 enum을 양방향으로 변환한다")
    void genresConvertKoreanApiValuesBidirectionally() {
        for (WorkGenre genre : WorkGenre.values()) {
            assertThat(WorkGenre.fromKorean(genre.getToKorean())).isSameAs(genre);
        }
    }

    @Test
    @DisplayName("지원하지 않는 장르 값은 변환하지 않는다")
    void unsupportedGenreCannotBeConverted() {
        assertThatThrownBy(() -> WorkGenre.fromKorean("역사"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("지원하지 않는 작품 장르입니다.");
    }
}
