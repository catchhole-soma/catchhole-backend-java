package org.monitoring.catchholebackend.domain.settingtool.processor;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeSourcePurgeRequestRepository;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolQuery;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse.Evidence;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse.EvidenceStatus;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse.Quote;
import org.monitoring.catchholebackend.domain.settingtool.repository.SettingToolChunkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.exception.CommonErrorCode;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 현재 접근 가능한 동일 원문에서 재검증한 인용만 반환한다. 저장된 quote 자체는 신뢰하지 않는다. */
@Component
@RequiredArgsConstructor
public class SettingEvidenceReader {
    public static final int MAX_QUOTES = 5;
    public static final int MAX_QUOTE_CODE_POINTS = 1000;
    private final ObjectStorageService storage;
    private final EpisodeSourcePurgeRequestRepository purgeRequests;
    private final EntityManager entityManager;
    private final SettingToolChunkRepository chunks;

    public Evidence read(UUID workId, UUID candidateId, UUID sourceChunkId, Episode episode, AnalysisJob job,
                         String candidateSourceKey, JsonNode spans, SettingToolQuery request) {
        if (episode == null) return result(EvidenceStatus.NO_SOURCE, candidateId, null, null, null, null, null, List.of(), false);
        if (!workId.equals(episode.getWork().getId()) || job != null
                && (!workId.equals(job.getWork().getId()) || job.getEpisode() != null
                    && !episode.getId().equals(job.getEpisode().getId()))) {
            throw new AppException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        UUID verifiedChunkId = null;
        if (sourceChunkId != null) {
            var chunkEpisodeId = chunks.findEpisodeId(sourceChunkId);
            if (chunkEpisodeId.isPresent() && !chunkEpisodeId.get().equals(episode.getId())) {
                throw new AppException(CommonErrorCode.RESOURCE_NOT_FOUND);
            }
            if (chunkEpisodeId.isPresent()) verifiedChunkId = sourceChunkId;
        }
        var state = state(workId, episode.getId());
        Integer sourceNo = job != null && job.getSourceEpisodeNo() != null ? job.getSourceEpisodeNo() : episode.getEpisodeNo();
        String hash = job == null ? null : job.getSourceContentHash();
        String version = job == null ? null : job.getSourceContentS3Version();
        if (request.episodeFrom() != null && sourceNo < request.episodeFrom()
                || request.episodeTo() != null && sourceNo > request.episodeTo()) {
            return result(EvidenceStatus.OUT_OF_RANGE, candidateId, episode.getId(), sourceNo, hash, version, null, List.of(), false);
        }
        EvidenceStatus blocked = blocked(state, episode.getId());
        if (blocked != null) return result(blocked, candidateId, episode.getId(), sourceNo, hash, version, false, List.of(), false);
        boolean manifest = job != null && job.getSourceEpisodeNo() != null;
        if (manifest && (!Objects.equals(job.getSourceContentS3Key(), state.key())
                || !Objects.equals(hash, state.hash()) || !Objects.equals(version, state.version())
                || !Objects.equals(sourceNo, state.episodeNo()))
                || candidateSourceKey != null && !candidateSourceKey.equals(state.key())) {
            return result(EvidenceStatus.SOURCE_CHANGED, candidateId, episode.getId(), sourceNo, hash, version, false, List.of(), false);
        }
        if (spans == null || !spans.isArray() || spans.isEmpty()) {
            return result(EvidenceStatus.NO_SOURCE, candidateId, episode.getId(), sourceNo, hash, version, null, List.of(), false);
        }
        String text;
        try {
            text = storage.getText(state.key());
        } catch (RuntimeException exception) {
            var status = missingObject(exception) ? EvidenceStatus.SOURCE_MISSING : EvidenceStatus.STORAGE_UNAVAILABLE;
            return result(status, candidateId, episode.getId(), sourceNo, hash, version, null, List.of(), false);
        }
        // managed Episode를 재사용하면 1차 캐시가 삭제/교체를 가린다. scalar projection으로 다시 확인한다.
        var after = state(workId, episode.getId());
        blocked = blocked(after, episode.getId());
        if (blocked != null || !state.equals(after)) {
            return result(blocked != null ? blocked : EvidenceStatus.SOURCE_CHANGED,
                    candidateId, episode.getId(), sourceNo, hash, version, false, List.of(), false);
        }
        if (text == null) return result(EvidenceStatus.SOURCE_MISSING, candidateId, episode.getId(), sourceNo, hash, version, null, List.of(), false);
        if (state.hash() != null && !state.hash().equals(sha256(text))) {
            return result(EvidenceStatus.SOURCE_CHANGED, candidateId, episode.getId(), sourceNo, hash, version, false, List.of(), false);
        }
        var quotes = new ArrayList<Quote>();
        boolean truncated = false;
        boolean invalid = false;
        for (JsonNode span : spans) {
            if (quotes.size() == MAX_QUOTES) { truncated = true; break; }
            JsonNode quoteNode = span.get("quote");
            if (quoteNode == null || !quoteNode.isTextual() || quoteNode.asText().isBlank()) { invalid = true; continue; }
            String quote = quoteNode.asText();
            Integer start = offset(span, "startOffset", "start_offset");
            Integer end = offset(span, "endOffset", "end_offset");
            int textLength = text.codePointCount(0, text.length());
            if (start != null || end != null) {
                if (start == null || end == null || start < 0 || end < start || end > textLength
                        || !text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end)).equals(quote)) {
                    invalid = true; continue;
                }
            } else {
                int found = text.indexOf(quote);
                if (found < 0) { invalid = true; continue; }
                // 동일 인용이 여러 번 나오면 위치를 추측하지 않는다.
                if (text.indexOf(quote, found + 1) < 0) {
                    start = text.codePointCount(0, found);
                    end = start + quote.codePointCount(0, quote.length());
                }
            }
            if (quote.codePointCount(0, quote.length()) > MAX_QUOTE_CODE_POINTS) {
                quote = quote.substring(0, quote.offsetByCodePoints(0, MAX_QUOTE_CODE_POINTS));
                if (start != null) end = start + MAX_QUOTE_CODE_POINTS;
                truncated = true;
            }
            quotes.add(new Quote(quote, start, end));
        }
        EvidenceStatus status = invalid ? EvidenceStatus.INVALID_QUOTE
                : manifest && hash != null ? EvidenceStatus.AVAILABLE : EvidenceStatus.LEGACY_UNVERIFIED;
        return new Evidence(status, candidateId, episode.getId(), sourceNo, verifiedChunkId, hash, version,
                manifest && hash != null ? true : null, quotes, truncated);
    }

    private SourceState state(UUID workId, UUID episodeId) {
        return entityManager.createQuery("""
                select e.episodeNo, e.contentS3Key, e.contentHash, e.contentS3Version,
                       cast(e.status as string), cast(e.work.lifecycleStatus as string)
                from Episode e where e.id = :episodeId and e.work.id = :workId
                """, Object[].class).setParameter("episodeId", episodeId).setParameter("workId", workId)
                .getResultStream().map(row -> new SourceState((Integer) row[0], (String) row[1], (String) row[2],
                        (String) row[3], (String) row[4], (String) row[5])).findFirst().orElse(null);
    }

    private EvidenceStatus blocked(SourceState state, UUID episodeId) {
        if (state == null || "ARCHIVED".equals(state.status()) || !"ACTIVE".equals(state.workStatus())
                || purgeRequests.existsByEpisodeId(episodeId)) return EvidenceStatus.SOURCE_PURGED;
        return state.key() == null || state.key().isBlank() ? EvidenceStatus.SOURCE_MISSING : null;
    }

    private Evidence result(EvidenceStatus status, UUID candidateId, UUID episodeId, Integer no,
                            String hash, String version, Boolean matches, List<Quote> quotes, boolean truncated) {
        // 이용 불가 상태에서는 현재 접근 가능함을 확인하지 못한 chunk locator도 생략한다.
        return new Evidence(status, candidateId, episodeId, no, null, hash, version, matches, quotes, truncated);
    }

    private Integer offset(JsonNode span, String camel, String snake) {
        JsonNode node = span.has(camel) ? span.get(camel) : span.get(snake);
        if (node == null || node.isNull()) return null;
        return node.canConvertToInt() && node.isIntegralNumber() ? node.intValue() : -1;
    }
    private boolean missingObject(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof S3Exception s3 && s3.statusCode() == 404) return true;
            if (cause instanceof java.nio.file.NoSuchFileException) return true;
        }
        return false;
    }
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private record SourceState(Integer episodeNo, String key, String hash, String version, String status, String workStatus) {}
}
