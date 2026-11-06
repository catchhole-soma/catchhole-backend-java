package org.monitoring.catchholebackend.domain.relationship.repository;

import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * GH391 관계도 1회 정리의 scalar 조회와 bulk 갱신. 분석·후보·프로필·Job 엔티티와 큰 jsonb 열을 올리지 않는다.
 * edge 끝점·key와 event의 edge·이름은 엔티티에서 {@code updatable=false}라 이 경로로만 바꾼다. event에는 edge_id
 * 색인이 없으므로 event 문은 모두 {@code work_id}로 범위를 좁힌다. 반드시 Work 잠금을 잡은 트랜잭션 안에서 호출한다.
 */
@Repository
@RequiredArgsConstructor
public class RelationshipGraphCleanupRepository {
    private static final int BATCH_SIZE = 50;

    private final JdbcTemplate jdbc;

    /**
     * @param cutoff 공개 세대의 마지막 APPLIED 회차. 없으면 null
     * @param ordered 그 회차 분석을 만든 Job이 ORDERED_PROVISIONAL이다
     * @param activeAnalyses PENDING·PREPARED·COMPLETED 분석 수(모든 세대)
     * @param frozenContexts 재개될 수 있는(문맥이 남은) INVALIDATED 분석 수
     * @param backfillActive 관계 백필 캠페인이 이 작품을 아직 완료하지 않았다
     */
    public record State(
            Integer cutoff, boolean ordered, long activeAnalyses, long frozenContexts, boolean backfillActive) {}

    public State state(UUID workId) {
        List<Object[]> backfill = jdbc.query(
                "select state, published_generation from relationship_backfill_works where work_id = ?",
                (rs, row) -> new Object[] {rs.getString(1), rs.getLong(2)},
                workId);
        long generation = backfill.isEmpty() ? 0 : (long) backfill.getFirst()[1];
        boolean backfillActive = !backfill.isEmpty() && !"COMPLETE".equals(backfill.getFirst()[0]);
        long active = count("""
                select count(*) from relationship_graph_analyses
                where work_id = ? and status in ('PENDING','PREPARED','COMPLETED')
                """, workId);
        long frozen = count("""
                select count(*) from relationship_graph_analyses
                where work_id = ? and status = 'INVALIDATED' and context is not null and evidence_purged = false
                """, workId);
        // The analysis that defines the published graph at its last episode also tells how its
        // prepare resolved names (ordered input anchors representative names).
        List<Object[]> latest = jdbc.query("""
                select a.episode_no, j.analysis_mode from relationship_graph_analyses a
                left join analysis_jobs j on j.id = a.id
                where a.work_id = ? and a.status = 'APPLIED' and a.generation = ?
                order by a.episode_no desc, a.created_at desc, a.id desc
                limit 1
                """, (rs, row) -> new Object[] {rs.getInt(1), rs.getString(2)}, workId, generation);
        Integer cutoff = latest.isEmpty() ? null : (Integer) latest.getFirst()[0];
        boolean ordered = !latest.isEmpty()
                && AnalysisMode.ORDERED_PROVISIONAL.name().equals(latest.getFirst()[1]);
        return new State(cutoff, ordered, active, frozen, backfillActive);
    }

    public record EventTransfer(UUID fromEdgeId, UUID toEdgeId, boolean swapNames, int expected) {}

    /** event 행이 따라온 선의 반대 순서로 생존 선에 붙으면 source/target 이름을 맞바꾼다. */
    public void swapEventNames(UUID workId, List<EventTransfer> transfers, LocalDateTime now) {
        List<EventTransfer> swapped = transfers.stream().filter(EventTransfer::swapNames).toList();
        batch("""
                update relationship_graph_events set source_name = target_name, target_name = source_name,
                    updated_at = ?
                where work_id = ? and edge_id = ?
                """, swapped.stream().map(t -> new Object[] {now, workId, t.fromEdgeId()}).toList(),
                swapped.stream().map(EventTransfer::expected).toList());
    }

    public void moveEvents(UUID workId, List<EventTransfer> transfers, LocalDateTime now) {
        batch("""
                update relationship_graph_events set edge_id = ?, updated_at = ?
                where work_id = ? and edge_id = ?
                """, transfers.stream().map(t -> new Object[] {t.toEdgeId(), now, workId, t.fromEdgeId()}).toList(),
                transfers.stream().map(EventTransfer::expected).toList());
    }

    public record EdgeEvents(UUID edgeId, int expected) {}

    public void deleteEvents(UUID workId, List<EdgeEvents> edges) {
        batch("delete from relationship_graph_events where work_id = ? and edge_id = ?",
                edges.stream().map(e -> new Object[] {workId, e.edgeId()}).toList(),
                edges.stream().map(EdgeEvents::expected).toList());
    }

    /** 합친 선과 자기 연결 선. 생존 선의 key 갱신보다 먼저 지워 UNIQUE(work_id, canonical_key) 충돌을 피한다. */
    public void deleteEdges(UUID workId, List<UUID> edgeIds) {
        batch("delete from relationship_graph_edges where work_id = ? and id = ?",
                edgeIds.stream().map(id -> new Object[] {workId, id}).toList(),
                edgeIds.stream().map(id -> 1).toList());
    }

    public record EdgeEndpoints(UUID edgeId, UUID sourceNodeId, UUID targetNodeId, String canonicalKey) {}

    public void moveEdges(UUID workId, List<EdgeEndpoints> moves, LocalDateTime now) {
        batch("""
                update relationship_graph_edges set source_node_id = ?, target_node_id = ?, canonical_key = ?,
                    updated_at = ?
                where work_id = ? and id = ?
                """, moves.stream().map(m -> new Object[] {
                        m.sourceNodeId(), m.targetNodeId(), m.canonicalKey(), now, workId, m.edgeId()}).toList(),
                moves.stream().map(m -> 1).toList());
    }

    private long count(String sql, UUID workId) {
        Long value = jdbc.queryForObject(sql, Long.class, workId);
        return value == null ? 0 : value;
    }

    /** 문마다 바뀐 행 수가 계획과 다르면 예외로 전체 트랜잭션을 되돌린다. */
    private void batch(String sql, List<Object[]> arguments, List<Integer> expected) {
        for (int from = 0; from < arguments.size(); from += BATCH_SIZE) {
            int to = Math.min(arguments.size(), from + BATCH_SIZE);
            int[] counts = jdbc.batchUpdate(sql, arguments.subList(from, to));
            for (int i = 0; i < counts.length; i++)
                if (counts[i] != Statement.SUCCESS_NO_INFO && counts[i] != expected.get(from + i))
                    throw new IllegalStateException(
                            "GH391 cleanup statement changed " + counts[i] + " rows, expected " + expected.get(from + i));
        }
    }
}
