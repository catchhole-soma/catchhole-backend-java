package org.monitoring.catchholebackend.domain.validation.repository;
import java.util.*;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationJudgmentHistory;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ValidationJudgmentHistoryRepository extends JpaRepository<ValidationJudgmentHistory,UUID> {
    List<ValidationJudgmentHistory> findAllByFindingIdInOrderByCreatedAtAscIdAsc(Collection<UUID> findingIds);
}
