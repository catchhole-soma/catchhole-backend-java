package org.monitoring.catchholebackend.domain.analysis.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {

    Optional<AnalysisJob> findByIdAndWorkId(UUID id, UUID workId);

    List<AnalysisJob> findAllByWorkIdOrderByCreatedAtDesc(UUID workId);
}
