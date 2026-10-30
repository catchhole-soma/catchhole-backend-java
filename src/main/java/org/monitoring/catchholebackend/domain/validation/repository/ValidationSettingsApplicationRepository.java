package org.monitoring.catchholebackend.domain.validation.repository;
import java.util.*;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationSettingsApplication;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ValidationSettingsApplicationRepository extends JpaRepository<ValidationSettingsApplication,UUID> {
    Optional<ValidationSettingsApplication> findByReportId(UUID reportId);
    Optional<ValidationSettingsApplication> findByWorkIdAndIdempotencyKey(UUID workId,String idempotencyKey);
    /** 회차 전체 확정이 APPLIED로 기록한 report인지. 같은 트랜잭션에서 도메인 쓰기보다 먼저 기록한 행도 읽는다. */
    boolean existsByReportIdAndStatus(UUID reportId,org.monitoring.catchholebackend.domain.validation.type.ValidationApplicationStatus status);
}
