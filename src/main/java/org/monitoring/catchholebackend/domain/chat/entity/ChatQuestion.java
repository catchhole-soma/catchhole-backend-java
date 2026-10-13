package org.monitoring.catchholebackend.domain.chat.entity;

import jakarta.persistence.*;

import lombok.*;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.Submit;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;

import java.util.UUID;

@Getter
@Entity
@Table(
        name = "chat_questions",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_chat_question_key",
                        columnNames = {"member_id", "work_id", "idempotency_key"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatQuestion {
    @Id private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "work_id", nullable = false)
    private Work work;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String inputHash;

    @Column(length = 4000)
    private String question;

    // 단일 질문 API의 기존 행은 대화에 속하지 않는다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ChatSession session;

    private Long sequenceNo;
    private java.time.Instant submittedAt;

    public void attachSession(ChatSession value, long sequence, java.time.Instant now) {
        session = value;
        sequenceNo = sequence;
        submittedAt = now;
    }

    private Integer episodeFrom;
    private Integer episodeTo;

    @Column(nullable = false)
    private int generation;

    @Column(nullable = false)
    private UUID currentAttemptId;

    public static ChatQuestion create(Work work, String key, String hash, Submit request) {
        var q = new ChatQuestion();
        q.id = UUID.randomUUID();
        q.work = work;
        q.member = work.getMember();
        q.idempotencyKey = key;
        q.inputHash = hash;
        q.question = request.question();
        q.episodeFrom = request.episodeFrom();
        q.episodeTo = request.episodeTo();
        return q;
    }

    public void next(UUID attempt) {
        generation++;
        currentAttemptId = attempt;
    }

    public void erase() {
        question = null;
    }
}
