package org.monitoring.catchholebackend.domain.feedback.entity;

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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Getter
@Entity
@Table(name = "feedbacks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Feedback extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "member_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_feedbacks_member")
    )
    private Member member;

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(name = "page_path", length = 255)
    private String pagePath;

    @Column(name = "reward_request_id")
    private UUID rewardRequestId;

    @Builder(access = AccessLevel.PRIVATE)
    private Feedback(
            Member member,
            String content,
            String pagePath,
            UUID rewardRequestId
    ) {
        this.member = member;
        this.content = content;
        this.pagePath = pagePath;
        this.rewardRequestId = rewardRequestId;
    }

    public static Feedback create(
            Member member,
            String content,
            String pagePath,
            UUID rewardRequestId
    ) {
        return Feedback.builder()
                .member(member)
                .content(content)
                .pagePath(pagePath)
                .rewardRequestId(rewardRequestId)
                .build();
    }
}
