package org.monitoring.catchholebackend.domain.retrieval.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Lifecycle mapping only. Python/pgvector owns the vector column (as with episode_chunks). */
@Entity
@Table(name = "retrieval_chunks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RetrievalChunk {
    @Id private UUID id;
    @Column(name = "episode_id", nullable = false) private UUID episodeId;
    @Column(name = "chunk_index", nullable = false) private int chunkIndex;
    @Column(name = "chunk_text", nullable = false, columnDefinition = "text") private String chunkText;
    @Column(name = "start_offset", nullable = false) private int startOffset;
    @Column(name = "end_offset", nullable = false) private int endOffset;
    @Column(name = "chunk_hash", nullable = false, length = 64) private String chunkHash;
    @Column(name = "token_count", nullable = false) private int tokenCount;
    @Column(name = "embedding_model", length = 100) private String embeddingModel;
    @Column(name = "embedding_version", length = 50) private String embeddingVersion;
    @Column(name = "embedded_at") private LocalDateTime embeddedAt;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "failure_code", length = 40) private String failureCode;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "last_operation_id") private UUID lastOperationId;
}
