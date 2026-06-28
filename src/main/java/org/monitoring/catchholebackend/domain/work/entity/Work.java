package org.monitoring.catchholebackend.domain.work.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    //작품 장르 ex) 판타지 , 로맨스 , 기타 사용자 입력
    //TODO:enum 타입으로 전환할지 고민
    @Column(name = "genre", length = 50)
    private String genre;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    //최대 몇회차까지 올라갔는지
    @Column(name = "latest_episode_no", nullable = false)
    private int latestEpisodeNo;

    private Work(Member member, String title, String genre, String description) {
        this.member = member;
        this.title = title;
        this.genre = genre;
        this.description = description;
        this.latestEpisodeNo = 0;
    }

    public static Work create(Member member, String title, String genre, String description) {
        return new Work(member, title, genre, description);
    }

    public void updateInfo(String title, String genre, String description) {
        this.title = title;
        this.genre = genre;
        this.description = description;
    }

    public void updateLatestEpisodeNo(int latestEpisodeNo) {
        this.latestEpisodeNo = latestEpisodeNo;
    }

    public boolean isOwnedBy(Long memberId) {
        return member.getId().equals(memberId);
    }
}
