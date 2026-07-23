package org.monitoring.catchholebackend.domain.work.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Schema(
        description = "작품 장르",
        type = "string",
        allowableValues = {
                "판타지", "로맨스", "추리", "코미디", "SF",
                "스포츠", "호러", "무협", "일상", "기타"
        }
)
public enum WorkGenre {

    FANTASY("판타지"),
    ROMANCE("로맨스"),
    MYSTERY("추리"),
    COMEDY("코미디"),
    SF("SF"),
    SPORTS("스포츠"),
    HORROR("호러"),
    MARTIAL_ARTS("무협"),
    SLICE_OF_LIFE("일상"),
    ETC("기타");

    @JsonValue
    private final String toKorean;

    @JsonCreator
    public static WorkGenre fromKorean(String toKorean) {
        return Arrays.stream(values())
                .filter(genre -> genre.toKorean.equals(toKorean))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 작품 장르입니다."));
    }
}
