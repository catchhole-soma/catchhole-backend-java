package org.monitoring.catchholebackend.domain.validation.repository;

import java.util.*;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationCorrection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ValidationCorrectionRepository extends JpaRepository<ValidationCorrection,UUID> {
    Optional<ValidationCorrection> findByWorkIdAndRequestKey(UUID workId,String requestKey);
    Optional<ValidationCorrection> findByIdAndWorkId(UUID id,UUID workId);
    List<ValidationCorrection> findAllByWorkIdOrderByCreatedAtAscIdAsc(UUID workId);
    void deleteAllByReportId(UUID reportId);
    void deleteAllByWorkId(UUID workId);
}
