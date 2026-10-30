package org.monitoring.catchholebackend.domain.validation.repository;

import java.util.*;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ValidationReportRepository extends JpaRepository<ValidationReport, UUID> {
    Optional<ValidationReport> findByIdAndWorkId(UUID id, UUID workId);
    default Optional<ValidationReport> findByEpisodeId(UUID episodeId) { return findByEpisodeIdAndCurrentTrue(episodeId); }
    Optional<ValidationReport> findByEpisodeIdAndCurrentTrue(UUID episodeId);
    List<ValidationReport> findAllByWorkId(UUID workId);

    /** GH312 S4: 검수 단계 계산에 필요한 실행·검토 종료·release Job·회차 전체 확정 상태를 scalar로 함께 읽는다. 행마다 엔티티를 읽지 않는다. */
    @org.springframework.data.jpa.repository.Query("""
            select new org.monitoring.catchholebackend.domain.validation.repository.ValidationReportState(
                r.episode.id, r.id, r.status, r.baselineStatus, r.current, j.status, c.id, aj.status, aj.journalStatus, a.status)
            from ValidationReport r
            left join ValidationJob j on j.report = r
            left join ValidationReviewCompletion c on c.report = r
            left join c.extractionJob aj
            left join ValidationSettingsApplication a on a.report = r
            where r.current = true and r.episode.id in :episodeIds
            """)
    List<ValidationReportState> findStatesByEpisodeIdIn(@org.springframework.data.repository.query.Param("episodeIds") Collection<UUID> episodeIds);

    /**
     * #314 B1: 작품의 현재 report 중 주어진 mode의 검수 단계를 scalar로 읽는다. 다음 회차 등록 차단 판정만 사용하며 seed/entries·finding을 읽지 않는다.
     * 회차 번호 오름차순이라 가장 앞선 미완료 report가 먼저 온다.
     */
    @org.springframework.data.jpa.repository.Query("""
            select new org.monitoring.catchholebackend.domain.validation.repository.ValidationAdmissionState(
                r.id, e.id, e.episodeNo, r.status, r.baselineStatus, r.current, j.status, c.id, aj.status, aj.journalStatus, a.status)
            from ValidationReport r
            join r.episode e
            left join ValidationJob j on j.report = r
            left join ValidationReviewCompletion c on c.report = r
            left join c.extractionJob aj
            left join ValidationSettingsApplication a on a.report = r
            where r.work.id = :workId and r.current = true and r.mode = :mode
            order by e.episodeNo asc, r.id asc
            """)
    List<ValidationAdmissionState> findAdmissionStatesByWorkId(@org.springframework.data.repository.query.Param("workId") UUID workId,
            @org.springframework.data.repository.query.Param("mode") org.monitoring.catchholebackend.domain.validation.type.ValidationReportMode mode);
}
