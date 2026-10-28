package org.monitoring.catchholebackend.domain.validation.repository;

import java.util.*;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationBaselineSource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ValidationBaselineSourceRepository extends JpaRepository<ValidationBaselineSource, UUID> {
    List<ValidationBaselineSource> findAllByReportId(UUID reportId);
    void deleteAllByReportId(UUID reportId);
}
