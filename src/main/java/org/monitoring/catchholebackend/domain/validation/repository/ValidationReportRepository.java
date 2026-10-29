package org.monitoring.catchholebackend.domain.validation.repository;

import java.util.*;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ValidationReportRepository extends JpaRepository<ValidationReport, UUID> {
    Optional<ValidationReport> findByIdAndWorkId(UUID id, UUID workId);
    default Optional<ValidationReport> findByEpisodeId(UUID episodeId) { return findByEpisodeIdAndCurrentTrue(episodeId); }
    Optional<ValidationReport> findByEpisodeIdAndCurrentTrue(UUID episodeId);
    List<ValidationReport> findAllByWorkId(UUID workId);

    @org.springframework.data.jpa.repository.Query("select new org.monitoring.catchholebackend.domain.validation.repository.ValidationReportState(r.episode.id, r.id, r.status) from ValidationReport r where r.current = true and r.episode.id in :episodeIds")
    List<ValidationReportState> findStatesByEpisodeIdIn(@org.springframework.data.repository.query.Param("episodeIds") Collection<UUID> episodeIds);
}
