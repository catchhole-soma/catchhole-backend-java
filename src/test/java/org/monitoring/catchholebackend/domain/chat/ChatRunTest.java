package org.monitoring.catchholebackend.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.monitoring.catchholebackend.domain.chat.entity.ChatRun;

import java.time.Instant;

@DisplayName("채팅 실행 기한의 데이터베이스 정밀도")
class ChatRunTest {
    @ParameterizedTest
    @CsvSource({
        "609359000, 609359000, 609359000",
        "609359499, 609359000, 609359000",
        "609359500, 609359000, 609359000",
        "609359940, 609359000, 609360000",
        "999999999, 999999000, 1000000000"
    })
    @DisplayName("생성 시 실행·정산 기한을 각각 마이크로초로 절삭하고 반올림하지 않는다")
    void creationTruncatesBothDeadlines(long nanos, long expectedNanos, long expectedUsageNanos) {
        var second = Instant.parse("2035-10-16T08:38:05Z");
        var rawDeadline = second.plusNanos(nanos);
        var run = ChatRun.create(
                null, null, rawDeadline, rawDeadline.plusSeconds(600).plusNanos(123),
                100, "test", "test");

        assertThat(run.getDeadline()).isEqualTo(second.plusNanos(expectedNanos));
        assertThat(run.getUsageDeadline())
                .isEqualTo(second.plusSeconds(600).plusNanos(expectedUsageNanos));
        assertThat(run.getDeadline()).isBeforeOrEqualTo(rawDeadline);
    }
}
