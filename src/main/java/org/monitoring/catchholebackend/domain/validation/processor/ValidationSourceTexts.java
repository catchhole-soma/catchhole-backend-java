package org.monitoring.catchholebackend.domain.validation.processor;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeSourcePurgeRequestRepository;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.stereotype.Component;

/**
 * report 고정 출처(baselineSeed의 target·sources) 원문 한 편을 읽는 유일한 경로다. 검수 Worker의 READ_SOURCE와
 * 작가 상세의 앞뒤 줄(오류 리포트 문맥 블록, 2026-11-03)이 같은 순서로 확인한다:
 * 현재 행이 고정 key·hash·version·회차 번호와 같은지 → 저장소 전문 GET 한 번 → 다시 같은지 → 크기 → 전문 SHA-256이 고정 hash와 같은지.
 * 원문 위치를 잘라 주는 일은 호출자가 한다. 상태 문자열은 READY·SOURCE_CHANGED·SOURCE_PURGED·SOURCE_UNREADABLE이다.
 */
@Component
@RequiredArgsConstructor
public class ValidationSourceTexts {
    /** 기존 검색·인용 reader와 동일한 읽기 지원 범위. */
    public static final int MAX_SOURCE_BYTES = 2_000_000;
    public static final String READY = "READY";

    private final EpisodeSourcePurgeRequestRepository purges;
    private final ObjectStorageService storage;
    private final EntityManager em;

    public record Read(String status, String text) {
        public boolean ready() {return READY.equals(status);}
    }

    /**
     * 고정 출처 하나의 전문. afterRead는 저장소 GET 직후(성공·실패 모두) 실행해 호출자가 report·lease를 다시 확인하게 한다.
     * 실패는 예외가 아니라 상태로 돌려준다.
     */
    public Read read(UUID workId, int targetEpisodeNo, JsonNode source, boolean target, Runnable afterRead) {
        String before = status(workId, targetEpisodeNo, source, target);
        if (!before.equals(READY)) return new Read(before, null);
        String text;
        try { text = storage.getText(source.path("contentS3Key").asText()); }
        catch (RuntimeException exception) { afterRead.run(); return new Read("SOURCE_UNREADABLE", null); }
        afterRead.run();
        String after = status(workId, targetEpisodeNo, source, target);
        if (!after.equals(READY)) return new Read(after, null);
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) return new Read("SOURCE_UNREADABLE", null);
        if (!storage.contentHash(text).equals(source.path("contentHash").asText())) return new Read("SOURCE_CHANGED", null);
        return new Read(READY, text);
    }

    /** 원문 I/O 없이 현재 행이 고정 출처와 같은지(scalar projection, JPA 1차 캐시와 분리). */
    public String status(UUID work, int targetNo, JsonNode source, boolean target) {
        String kind = source.path("sourceKind").asText();
        Object[] state;
        if (kind.equals("EPISODE")) {
            UUID id = UUID.fromString(source.path("episodeId").asText());
            state = em.createQuery("""
                    select e.contentS3Key, e.contentHash, e.contentS3Version, e.episodeNo, cast(e.status as string), e.contentRevision
                    from Episode e where e.id = :id and e.work.id = :work
                    """, Object[].class).setParameter("id", id).setParameter("work", work).getResultStream().findFirst().orElse(null);
            if (state == null || "ARCHIVED".equals(state[4]) || purges.blocksSource(id,source.path("contentS3Key").asText())) return "SOURCE_PURGED";
            if (source.path("contentRevision").isNumber() && !Objects.equals(state[5],source.path("contentRevision").asLong())) return "SOURCE_CHANGED";
            if (!Objects.equals(state[3], source.path("episodeNo").asInt())
                    || target && (int) state[3] != targetNo || !target && (int) state[3] >= targetNo) return "SOURCE_CHANGED";
        } else if (kind.equals("SETTING_BOOK") && !target) {
            state = em.createQuery("""
                    select r.contentS3Key, r.contentHash, r.contentS3Version, r.revisionNo,
                           cast(r.referenceTimeKind as string), r.referenceEpisodeNo, r.settingBook.id
                    from SettingBookRevision r where r.id = :id and r.settingBook.batch.work.id = :work
                    """, Object[].class).setParameter("id", UUID.fromString(source.path("revisionId").asText()))
                    .setParameter("work", work).getResultStream().findFirst().orElse(null);
            if (state == null) return "SOURCE_PURGED";
            if (!Objects.equals(state[3], source.path("revisionNo").asInt()) || !Objects.equals(state[4], source.path("referenceTimeKind").asText())
                    || !Objects.equals(state[5], source.path("referenceEpisodeNo").isNumber() ? source.path("referenceEpisodeNo").asInt() : null)
                    || !Objects.equals(state[6].toString(), source.path("settingBookId").asText())
                    || "EPISODE".equals(state[4]) && (int) state[5] >= targetNo) return "SOURCE_CHANGED";
        } else return "SOURCE_CHANGED";
        if (!Objects.equals(state[0], source.path("contentS3Key").asText()) || !Objects.equals(state[1], source.path("contentHash").asText())
                || !Objects.equals(state[2], nullable(source, "contentS3Version"))) return "SOURCE_CHANGED";
        return READY;
    }

    private static String nullable(JsonNode node, String field) {return node.path(field).isTextual() ? node.path(field).asText() : null;}
}
