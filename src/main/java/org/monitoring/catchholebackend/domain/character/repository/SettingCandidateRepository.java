package org.monitoring.catchholebackend.domain.character.repository;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingCandidateRepository extends JpaRepository<SettingCandidate, UUID> {

    List<SettingCandidate> findAllByWorkIdOrderByCreatedAtDesc(UUID workId);

    List<SettingCandidate> findAllByWorkIdAndEntityNameAndReviewStatusOrderByCreatedAtDesc(
            UUID workId,
            String entityName,
            SettingCandidateReviewStatus reviewStatus
    );
}
