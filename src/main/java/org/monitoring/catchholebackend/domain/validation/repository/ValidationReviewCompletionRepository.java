package org.monitoring.catchholebackend.domain.validation.repository;
import java.util.*;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReviewCompletion;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface ValidationReviewCompletionRepository extends JpaRepository<ValidationReviewCompletion,UUID> {
    Optional<ValidationReviewCompletion> findByReportId(UUID reportId);
    boolean existsByReportId(UUID reportId);
    Optional<ValidationReviewCompletion> findByWorkIdAndIdempotencyKey(UUID workId,String idempotencyKey);
    /** 현재 report의 receipt가 가리키는 release Job인지 확인한다. 퇴역 report의 receipt는 재시도 권한이 아니다. */
    @Query("select c from ValidationReviewCompletion c where c.extractionJob.id=:job and c.report.current=true")
    Optional<ValidationReviewCompletion> findCurrentByExtractionJobId(@Param("job") UUID extractionJobId);
}
