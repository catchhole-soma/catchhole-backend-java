package org.monitoring.catchholebackend.domain.retrieval.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.retrieval.repository.RetrievalIndexLagRepository.Snapshot;
import org.monitoring.catchholebackend.domain.retrieval.type.RetrievalIndexLagNotice;

@DisplayName("검색 색인 지연 알림 문구")
class RetrievalIndexLagAlertMapperTest {
    final RetrievalIndexLagAlertMapper mapper = new RetrievalIndexLagAlertMapper();

    @Test
    @DisplayName("대기 시간은 일·시간·분 중 큰 두 단위로 줄여 쓴다")
    void formatsWaitingTime() {
        assertThat(RetrievalIndexLagAlertMapper.duration(Duration.ofMinutes(45))).isEqualTo("45분");
        assertThat(RetrievalIndexLagAlertMapper.duration(Duration.ofMinutes(60))).isEqualTo("1시간");
        assertThat(RetrievalIndexLagAlertMapper.duration(Duration.ofMinutes(95))).isEqualTo("1시간 35분");
        assertThat(RetrievalIndexLagAlertMapper.duration(Duration.ofHours(123).plusMinutes(5))).isEqualTo("5일 3시간");
        assertThat(RetrievalIndexLagAlertMapper.duration(Duration.ofMinutes(-3))).isEqualTo("0분");
    }

    @Test
    @DisplayName("작품 ID가 없고 환경이 운영이 아니면 그 줄을 빼고 환경을 구분해 표시한다")
    void omitsWorkIdsWhenUnavailable() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 11, 12, 0);
        String message = mapper.toMessage(RetrievalIndexLagNotice.STARTED, new Snapshot(1, 1, now.minusMinutes(90), List.of()),
                0, now, Duration.ofMinutes(60), "local");

        assertThat(message).isEqualTo("""
                ⚠️ [로컬] 검색 색인 지연 · 작품 1개 / 회차 1개
                원고가 올라온 뒤 1시간이 넘도록 검색 색인이 없는 회차입니다. 가장 오래 기다린 회차: 1시간 30분
                AI 서버의 검색 색인 작업(catchhole-retrieval-backfill timer, manage-ai-runtime.py status) 상태를 확인하세요.
                """);
    }
}
