package org.monitoring.catchholebackend.domain.character.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettingCandidateRepository extends JpaRepository<SettingCandidate, UUID> {

    List<SettingCandidate> findAllByWorkIdOrderByCreatedAtDesc(UUID workId);

    List<SettingCandidate> findAllByWorkIdAndReviewStatusOrderByCreatedAtDesc(
            UUID workId,
            SettingCandidateReviewStatus reviewStatus
    );

    List<SettingCandidate> findAllByWorkIdAndEntityNameOrderByCreatedAtDesc(
            UUID workId,
            String entityName
    );

    List<SettingCandidate> findAllByWorkIdAndEntityNameAndReviewStatusOrderByCreatedAtDesc(
            UUID workId,
            String entityName,
            SettingCandidateReviewStatus reviewStatus
    );

    @Query("""
            select candidate
            from SettingCandidate candidate
            where candidate.work.id = :workId
              and candidate.entityType = :entityType
              and candidate.reviewStatus = :reviewStatus
              and candidate.matchStatus = :matchStatus
              and trim(candidate.entityName) = :entityName
            order by candidate.createdAt desc
            """)
    List<SettingCandidate> findAllByNormalizedEntityNameAndMatchState(
            @Param("workId") UUID workId,
            @Param("entityName") String entityName,
            @Param("entityType") SettingEntityType entityType,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus,
            @Param("matchStatus") SettingCandidateMatchStatus matchStatus
    );

    Optional<SettingCandidate> findByIdAndWorkId(UUID id, UUID workId);
}
