package org.monitoring.catchholebackend.domain.work.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Getter
@Entity
@Table(name = "works")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Work extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "genre", length = 50)
    private String genre;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    //이후에 작품 보관 등 확장을 위한 필드
    private WorkStatus status;

    @Column(name = "latest_episode_no", nullable = false)
    private int latestEpisodeNo;

    @Builder
    private Work(
            UUID ownerUserId,
            String title,
            String genre,
            String description,
            WorkStatus status,
            Integer latestEpisodeNo
    ) {
        this.ownerUserId = ownerUserId;
        this.title = title;
        this.genre = genre;
        this.description = description;
        this.status = status == null ? WorkStatus.ACTIVE : status;
        this.latestEpisodeNo = latestEpisodeNo == null ? 0 : latestEpisodeNo;
    }

    public void updateInfo(String title, String genre, String description) {
        this.title = title;
        this.genre = genre;
        this.description = description;
    }

    public void updateLatestEpisodeNo(int latestEpisodeNo) {
        this.latestEpisodeNo = latestEpisodeNo;
    }

    public void activate() {
        this.status = WorkStatus.ACTIVE;
    }

    public void archive() {
        this.status = WorkStatus.ARCHIVED;
    }

    public boolean isOwnedBy(UUID userId) {
        return ownerUserId.equals(userId);
    }
}
