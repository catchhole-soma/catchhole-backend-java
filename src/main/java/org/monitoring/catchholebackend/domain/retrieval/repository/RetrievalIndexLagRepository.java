package org.monitoring.catchholebackend.domain.retrieval.repository;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 검색 색인 백필이 색인해야 하는데 기준 시각 전부터 색인되지 않은 회차를 센다(#422).
 * 대상·완료 조건은 AI `app/retrieval/store.py`의 `SOURCE_SQL`(AVAILABLE)과 `inspect`(READY)를 따른다.
 * 그쪽 규칙이 바뀌면 이 SQL도 함께 바꾼다.
 */
@Repository
@RequiredArgsConstructor
public class RetrievalIndexLagRepository {
    // AI IndexSpec과 Java ChatRetrievalSources가 쓰는 고정 조각 규칙·모델이다. 버전만 설정으로 바뀐다.
    static final String CHUNK_POLICY = "cl100k-800-100-boundary-v1";
    static final String EMBEDDING_MODEL = "text-embedding-3-small";

    private final EntityManager em;

    /** 색인 지연 회차 수, 그 회차가 속한 작품 수, 가장 오래된 원문 시각, 지연 회차가 많은 작품 ID(최대 topWorks개). */
    public record Snapshot(long episodes, long works, LocalDateTime oldestContentUpdatedAt, List<UUID> topWorkIds) {}

    @Transactional(readOnly = true)
    public Snapshot read(LocalDateTime cutoff, String embeddingVersion, int topWorks) {
        // retrieval_chunks는 조각 상태 열만 읽어 묶고 원문·벡터는 읽지 않는다.
        // 원문 hash 재계산(inspect의 CHUNK_MISMATCH)은 매 확인마다 전체 원문을 읽어야 해서 하지 않는다.
        Object[] row = (Object[]) em.createNativeQuery("""
                with chunk_state as (
                    select c.episode_id, count(*) as stored,
                           count(*) filter (where c.status = 'READY' and c.embedding is not null
                                              and c.embedding_model = :model
                                              and c.embedding_version = :version) as ready
                    from retrieval_chunks c
                    group by c.episode_id
                ), lagging as (
                    select e.work_id, e.content_updated_at
                    from episodes e
                    join works w on w.id = e.work_id
                    join members m on m.id = w.member_id
                    left join retrieval_sources s on s.episode_id = e.id and s.work_id = e.work_id
                         and s.source_key = e.content_s3_key and s.source_hash = e.content_hash
                         and s.source_version is not distinct from e.content_s3_version
                         and s.chunk_policy = :policy
                    left join chunk_state k on k.episode_id = s.episode_id
                    where w.lifecycle_status = 'ACTIVE' and m.status = 'ACTIVE' and e.status <> 'ARCHIVED'
                      and e.content_s3_key is not null and e.content_s3_key <> ''
                      and length(e.content_hash) = 64 and e.content_hash ~ '^[a-f0-9]+$'
                      and not exists (select 1 from episode_source_purge_requests p where p.episode_id = e.id)
                      and e.content_updated_at <= :cutoff
                      and (s.episode_id is null
                           or coalesce(k.stored, 0) <> s.chunk_count
                           or coalesce(k.ready, 0) <> s.chunk_count)
                ), per_work as (
                    select work_id, count(*) as episodes, min(content_updated_at) as oldest
                    from lagging
                    group by work_id
                )
                select coalesce(sum(episodes), 0), count(*), min(oldest),
                       (select string_agg(cast(top.work_id as text), ',' order by top.episodes desc, top.oldest, top.work_id)
                        from (select work_id, episodes, oldest from per_work
                              order by episodes desc, oldest, work_id limit :top) top)
                from per_work
                """)
                .setParameter("model", EMBEDDING_MODEL)
                .setParameter("version", embeddingVersion)
                .setParameter("policy", CHUNK_POLICY)
                .setParameter("cutoff", cutoff)
                .setParameter("top", topWorks)
                .setHint("jakarta.persistence.query.timeout", 5000)
                .getSingleResult();
        String top = (String) row[3];
        return new Snapshot(((Number) row[0]).longValue(), ((Number) row[1]).longValue(), time(row[2]),
                top == null || top.isEmpty() ? List.of() : Arrays.stream(top.split(",")).map(UUID::fromString).toList());
    }

    private static LocalDateTime time(Object value) {
        return value == null ? null : value instanceof LocalDateTime time ? time : ((Timestamp) value).toLocalDateTime();
    }
}
