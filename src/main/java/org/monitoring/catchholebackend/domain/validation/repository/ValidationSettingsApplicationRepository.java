package org.monitoring.catchholebackend.domain.validation.repository;
import java.util.*;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationSettingsApplication;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ValidationSettingsApplicationRepository extends JpaRepository<ValidationSettingsApplication,UUID> {
    Optional<ValidationSettingsApplication> findByReportId(UUID reportId);
    Optional<ValidationSettingsApplication> findByWorkIdAndIdempotencyKey(UUID workId,String idempotencyKey);
}
