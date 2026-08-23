package org.monitoring.catchholebackend.domain.work.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.work.type.WorkLifecycleStatus;
import org.monitoring.catchholebackend.domain.work.exception.WorkErrorCode;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
import org.monitoring.catchholebackend.global.exception.AppException;

@Getter
@Entity
@Table(name = "works")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Work extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "member_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_works_member")
    )
    private Member member;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "genre", nullable = false, length = 50)
    private WorkGenre genre;

    @Column(name = "description", length = 50)
    private String description;

    //최대 몇회차까지 올라갔는지
    @Column(name = "latest_episode_no", nullable = false)
    private int latestEpisodeNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false, length = 20)
    private WorkLifecycleStatus lifecycleStatus;

    private Work(Member member, String title, WorkGenre genre, String description) {
        this.member = member;
        this.title = title;
        this.genre = genre;
        this.description = normalizeDescription(description);
        this.latestEpisodeNo = 0;
        this.lifecycleStatus = WorkLifecycleStatus.ACTIVE;
    }

    public static Work create(Member member, String title, WorkGenre genre, String description) {
        return new Work(member, title, genre, description);
    }

    public void updateInfo(String title, WorkGenre genre, String description) {
        this.title = title;
        this.genre = genre;
        this.description = normalizeDescription(description);
    }

    public void updateLatestEpisodeNo(int latestEpisodeNo) {
        this.latestEpisodeNo = latestEpisodeNo;
    }

    public boolean isOwnedBy(Long memberId) {
        return member.getId().equals(memberId);
    }

    public void requireActive() {
        if (lifecycleStatus != WorkLifecycleStatus.ACTIVE) {
            throw new AppException(WorkErrorCode.WORK_PURGE_IN_PROGRESS);
        }
    }

    public void startPurging() {
        requireActive();
        this.lifecycleStatus = WorkLifecycleStatus.PURGING;
    }

    private static String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.trim();
    }
}
