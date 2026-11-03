package org.monitoring.catchholebackend.domain.analysis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

/** 종료 동의와 새 Job들의 metricsRequestId를 연결한다. 원문·후보 값은 복사하지 않는다. */
@Getter
@Entity
@Table(name = "analysis_review_continuations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisReviewContinuation extends BaseEntity {
    @Id private UUID id;
    @Column(nullable = false, updatable = false) private UUID workId;
    @Column(nullable = false, updatable = false) private Long memberId;
    @Column(nullable = false, updatable = false, length = 64) private String reviewRevision;
    @Column(nullable = false, updatable = false) private int firstEpisodeNo;
    @Column(nullable = false, updatable = false) private long characterCount;
    @Column(nullable = false, updatable = false) private long worldCount;
    @Column(nullable = false, updatable = false) private long relationshipCount;

    public static AnalysisReviewContinuation record(UUID requestId, UUID workId, Long memberId,
            String revision, int firstEpisodeNo, long characterCount, long worldCount, long relationshipCount) {
        var value = new AnalysisReviewContinuation();
        value.id = requestId;
        value.workId = workId;
        value.memberId = memberId;
        value.reviewRevision = revision;
        value.firstEpisodeNo = firstEpisodeNo;
        value.characterCount = characterCount;
        value.worldCount = worldCount;
        value.relationshipCount = relationshipCount;
        return value;
    }
}
