package org.monitoring.catchholebackend.domain.validation.repository;
import java.util.*;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationJudgment;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ValidationJudgmentRepository extends JpaRepository<ValidationJudgment,UUID> {
    Optional<ValidationJudgment> findByFindingId(UUID findingId);
    List<ValidationJudgment> findAllByFindingIdIn(Collection<UUID> findingIds);
    List<ValidationJudgment> findAllByCheckIdAndCheckRevision(UUID checkId,Long checkRevision);
}
