package org.monitoring.catchholebackend.domain.chat.entity;

import jakarta.persistence.*;

import lombok.*;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

import java.time.*;
import java.util.UUID;

@Getter
@Entity
@Table(
        name = "chat_sessions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"work_id", "creation_key"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatSession extends BaseEntity {
    @Id private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Work work;

    @Column(nullable = false)
    private UUID creationKey;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant closedAt;

    @Column(nullable = false)
    private long nextSequence;

    public static ChatSession create(Work work, UUID key, Instant now, Duration ttl) {
        var session = new ChatSession();
        session.id = UUID.randomUUID();
        session.work = work;
        session.creationKey = key;
        session.expiresAt = now.plus(ttl);
        session.nextSequence = 1;
        return session;
    }

    public boolean isOpen(Instant now) {
        return closedAt == null && now.isBefore(expiresAt);
    }

    public long appendQuestion(Instant now, Duration ttl) {
        touch(now, ttl);
        return nextSequence++;
    }

    public void touch(Instant now, Duration ttl) {
        expiresAt = now.plus(ttl);
    }

    public void close(Instant now) {
        if (closedAt == null) closedAt = now;
    }
}
