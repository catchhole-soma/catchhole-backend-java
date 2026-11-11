package org.monitoring.catchholebackend.domain.retrieval.mapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Collectors;
import org.monitoring.catchholebackend.domain.retrieval.repository.RetrievalIndexLagRepository.Snapshot;
import org.monitoring.catchholebackend.domain.retrieval.type.RetrievalIndexLagNotice;
import org.springframework.stereotype.Component;

/** 원고·제목·회원 정보·S3 key 없이 개수, 대기 시간, 작품 ID(최대 3개)만 담는다. */
@Component
public class RetrievalIndexLagAlertMapper {
    public String toMessage(RetrievalIndexLagNotice notice, Snapshot lag, long previousCount, LocalDateTime now,
            Duration threshold, String environment) {
        String env = "prod".equals(environment) ? "운영" : "local".equals(environment) ? "로컬" : "기타";
        if (notice == RetrievalIndexLagNotice.RESOLVED) {
            return "✅ [" + env + "] " + notice.getToKorean() + "\n"
                    + "기준 " + duration(threshold) + "을 넘겨 검색 색인이 없는 회차가 이제 없습니다.\n";
        }
        StringBuilder message = new StringBuilder();
        message.append("⚠️ [").append(env).append("] ").append(notice.getToKorean())
                .append(" · 작품 ").append(lag.works()).append("개 / 회차 ").append(lag.episodes()).append("개\n");
        message.append("원고가 올라온 뒤 ").append(duration(threshold)).append("이 넘도록 검색 색인이 없는 회차입니다.");
        if (lag.oldestContentUpdatedAt() != null) {
            message.append(" 가장 오래 기다린 회차: ")
                    .append(duration(Duration.between(lag.oldestContentUpdatedAt(), now)));
        }
        message.append('\n');
        if (notice == RetrievalIndexLagNotice.GREW) {
            message.append("직전 알림보다 늘었습니다: ").append(previousCount).append("개 → ")
                    .append(lag.episodes()).append("개\n");
        }
        if (!lag.topWorkIds().isEmpty()) {
            message.append("지연 회차가 많은 작품 ID: ")
                    .append(lag.topWorkIds().stream().map(UUID::toString).collect(Collectors.joining(", "))).append('\n');
        }
        message.append("AI 서버의 검색 색인 작업(catchhole-retrieval-backfill timer, manage-ai-runtime.py status) 상태를 확인하세요.\n");
        return message.toString();
    }

    static String duration(Duration value) {
        long minutes = Math.max(0, value.toMinutes());
        long days = minutes / (24 * 60), hours = minutes / 60 % 24, rest = minutes % 60;
        if (days > 0) return days + "일" + (hours > 0 ? " " + hours + "시간" : "");
        if (hours > 0) return hours + "시간" + (rest > 0 ? " " + rest + "분" : "");
        return rest + "분";
    }
}
