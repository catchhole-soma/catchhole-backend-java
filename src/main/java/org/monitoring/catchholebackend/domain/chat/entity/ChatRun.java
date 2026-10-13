package org.monitoring.catchholebackend.domain.chat.entity;

import jakarta.persistence.*;

import lombok.*;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.Failure;
import org.monitoring.catchholebackend.domain.chat.type.ChatState;
import org.monitoring.catchholebackend.domain.work.entity.Work;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(
        name = "chat_runs",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_chat_run_generation",
                        columnNames = {"question_id", "generation"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRun {
    @Id private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "question_id")
    private ChatQuestion question;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "work_id", nullable = false)
    private Work work;

    @Column(nullable = false)
    private int generation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ChatState state;

    @Column(nullable = false)
    private Instant deadline;

    @Column(nullable = false)
    private Instant usageDeadline;

    private UUID runnerId;

    @Column(length = 100)
    private String actor;

    @Column(length = 100)
    private String policy;

    @Column(nullable = false)
    private long tokenBudget;

    @Column(nullable = false)
    private long allocatedTokens;

    @Column(nullable = false)
    private int callCount;

    @Column(nullable = false)
    private int toolCount;

    @Column(columnDefinition = "text")
    private String resultJson;

    @Column(columnDefinition = "text")
    private String receiptJson;

    @Column(length = 64)
    private String finishHash;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private Failure failure;

    public static ChatRun create(
            Work work,
            ChatQuestion question,
            Instant deadline,
            Instant usageDeadline,
            long budget,
            String actor,
            String policy) {
        var r = new ChatRun();
        r.id = UUID.randomUUID();
        r.work = work;
        r.question = question;
        r.deadline = deadline;
        r.usageDeadline = usageDeadline;
        r.tokenBudget = budget;
        r.actor = actor;
        r.policy = policy;
        r.state = ChatState.QUEUED;
        r.receiptJson = "[]";
        if (question != null) {
            question.next(r.id);
            r.generation = question.getGeneration();
        }
        return r;
    }

    public void useOperationId(UUID id) {
        if (question != null) throw new IllegalStateException();
        this.id = id;
    }

    public boolean operational() {
        return question == null;
    }

    public void start(UUID runner) {
        runnerId = runner;
        state = ChatState.RUNNING;
    }

    public void allocate(long tokens) {
        allocatedTokens += tokens;
        callCount++;
    }

    public void updateReceipts(String receipts) {
        receiptJson = receipts;
    }

    public void recordTool(String receipts) {
        toolCount++;
        receiptJson = receipts;
    }

    public void finish(String json, String hash, Failure failure, boolean pending) {
        this.resultJson = json;
        this.finishHash = hash;
        this.failure = failure;
        state =
                pending
                        ? ChatState.SETTLEMENT_PENDING
                        : (failure == null ? ChatState.SUCCEEDED : ChatState.FAILED);
    }

    public void settlementComplete() {
        if (state == ChatState.SETTLEMENT_PENDING)
            state = failure == null ? ChatState.SUCCEEDED : ChatState.FAILED;
    }

    public void timeout() {
        if (state == ChatState.QUEUED || state == ChatState.RUNNING) state = ChatState.TIMED_OUT;
    }

    public void eraseConversation(boolean pending) {
        resultJson = null;
        receiptJson = null;
        if (state == ChatState.QUEUED || state == ChatState.RUNNING) {
            failure = Failure.INTERRUPTED;
            state = pending ? ChatState.SETTLEMENT_PENDING : ChatState.FAILED;
        }
    }

    public void invalidate() {
        state = ChatState.INVALIDATED;
        resultJson = null;
        receiptJson = null;
        finishHash = null;
        if (question != null) question.erase();
    }
}
