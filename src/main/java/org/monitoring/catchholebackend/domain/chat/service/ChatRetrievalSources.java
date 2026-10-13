package org.monitoring.catchholebackend.domain.chat.service;

import static org.monitoring.catchholebackend.domain.chat.exception.ChatErrorCode.*;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.RetrievalReference;
import org.monitoring.catchholebackend.domain.chat.entity.ChatRun;
import org.monitoring.catchholebackend.domain.chat.mapper.ChatMapper;
import org.monitoring.catchholebackend.global.config.chat.ChatProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.stereotype.Component;

/** 호출자는 실행의 Work 잠금을 유지한다. 검색 텍스트도 실제 현재 원문과 다시 대조한다. */
@Component
@RequiredArgsConstructor
public class ChatRetrievalSources {
    private static final String POLICY = "cl100k-800-100-boundary-v1";
    private static final String MODEL = "text-embedding-3-small";
    private static final int MAX_SOURCE_CODE_POINTS = 250000;
    private final EntityManager entityManager;
    private final ObjectStorageService storage;
    private final ChatProperties properties;
    private final ChatMapper mapper;

    public String receipt(RetrievalReference source) {
        return "RETRIEVAL:" + sha256(mapper.encode(source));
    }

    public void validate(ChatRun run, List<RetrievalReference> sources) {
        if (sources == null || sources.size() > 12 || !sources.isEmpty() && run.operational())
            throw new AppException(CHAT_SCOPE_INVALID);
        if (sources.isEmpty()) return;
        Map<UUID, Chunk> observed = new HashMap<>();
        Map<UUID, String> originals = new HashMap<>();
        for (var ref : sources) {
            validateShape(ref);
            Chunk chunk = observed.computeIfAbsent(ref.chunkId(), id -> read(run, id));
            var question = run.getQuestion();
            if (!ref.episodeId().equals(chunk.episodeId())
                    || ref.sourceEpisodeNo() != chunk.episodeNo()
                    || !ref.sourceHash().equals(chunk.sourceHash())
                    || !Objects.equals(ref.sourceVersion(), chunk.sourceVersion())
                    || question.getEpisodeFrom() != null && chunk.episodeNo() < question.getEpisodeFrom()
                    || question.getEpisodeTo() != null && chunk.episodeNo() > question.getEpisodeTo()
                    || ref.startOffset() < chunk.startOffset()
                    || ref.endOffset() > chunk.endOffset()
                    || !sha256(chunk.text()).equals(chunk.chunkHash())
                    || !slice(chunk.text(), ref.startOffset() - chunk.startOffset(),
                            ref.endOffset() - chunk.startOffset()).equals(ref.quote()))
                throw new AppException(CHAT_SCOPE_INVALID);
            String original = originals.computeIfAbsent(chunk.episodeId(), id -> original(chunk));
            if (!slice(original, chunk.startOffset(), chunk.endOffset()).equals(chunk.text())
                    || !slice(original, ref.startOffset(), ref.endOffset()).equals(ref.quote()))
                throw new AppException(CHAT_SCOPE_INVALID);
        }
        // Scalar SQL을 다시 실행해 관리 엔티티 캐시 또는 읽기 도중 변경된 manifest를 재사용하지 않는다.
        for (var entry : observed.entrySet())
            if (!entry.getValue().equals(read(run, entry.getKey())))
                throw new AppException(CHAT_SCOPE_INVALID);
    }

    private void validateShape(RetrievalReference ref) {
        if (ref == null || ref.chunkId() == null || ref.episodeId() == null
                || ref.sourceHash() == null || !ref.sourceHash().matches("[a-f0-9]{64}")
                || ref.sourceEpisodeNo() < 1
                || ref.sourceVersion() != null && ref.sourceVersion().length() > 100
                || ref.quote() == null || ref.quote().isBlank()
                || ref.quote().codePointCount(0, ref.quote().length()) > 4000
                || ref.startOffset() < 0 || ref.endOffset() <= ref.startOffset())
            throw new AppException(CHAT_SCOPE_INVALID);
    }

    private String original(Chunk chunk) {
        final String text;
        try {
            text = storage.getText(chunk.sourceKey());
        } catch (RuntimeException exception) {
            throw new AppException(CHAT_SOURCE_UNAVAILABLE);
        }
        if (text == null || text.codePointCount(0, text.length()) > MAX_SOURCE_CODE_POINTS
                || !sha256(text).equals(chunk.sourceHash()))
            throw new AppException(CHAT_SCOPE_INVALID);
        return text;
    }

    private Chunk read(ChatRun run, UUID id) {
        // Keyword/인접 조회는 아직 임베딩이 없는 유효 청크도 허용한다.
        // 이미 벡터가 있으면 검색 서버와 같은 model/version을 요구한다.
        var rows = entityManager.createNativeQuery("""
                SELECT c.episode_id,e.episode_no,c.chunk_text,c.start_offset,c.end_offset,c.chunk_hash,
                       s.source_key,s.source_hash,s.source_version
                FROM retrieval_chunks c
                JOIN retrieval_sources s ON s.episode_id=c.episode_id
                JOIN episodes e ON e.id=s.episode_id AND e.work_id=s.work_id
                JOIN works w ON w.id=s.work_id
                JOIN members m ON m.id=w.member_id
                WHERE c.id=:id AND s.work_id=:work AND m.id=:member
                  AND w.lifecycle_status='ACTIVE' AND m.status='ACTIVE' AND e.status<>'ARCHIVED'
                  AND e.content_s3_key=s.source_key AND e.content_hash=s.source_hash
                  AND e.content_s3_version IS NOT DISTINCT FROM s.source_version
                  AND s.chunk_policy=:policy
                  AND NOT EXISTS (SELECT 1 FROM episode_source_purge_requests p WHERE p.episode_id=e.id)
                  AND (c.embedding IS NULL OR (c.embedding_model=:model AND c.embedding_version=:version))
                """, Object[].class)
                .setParameter("id", id).setParameter("work", run.getWork().getId())
                .setParameter("member", run.getWork().getMember().getId())
                .setParameter("policy", POLICY).setParameter("model", MODEL)
                .setParameter("version", properties.retrievalEmbeddingVersion()).getResultList();
        if (rows.size() != 1) throw new AppException(CHAT_SCOPE_INVALID);
        var r = (Object[]) rows.getFirst();
        return new Chunk((UUID) r[0], ((Number) r[1]).intValue(), (String) r[2],
                ((Number) r[3]).intValue(), ((Number) r[4]).intValue(), (String) r[5],
                (String) r[6], (String) r[7], (String) r[8]);
    }

    private String slice(String text, int start, int end) {
        if (start < 0 || end < start || end > text.codePointCount(0, text.length()))
            throw new AppException(CHAT_SCOPE_INVALID);
        return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Chunk(UUID episodeId, int episodeNo, String text, int startOffset, int endOffset,
                         String chunkHash, String sourceKey, String sourceHash, String sourceVersion) {}
}
