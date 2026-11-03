package org.monitoring.catchholebackend.domain.retrieval.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** V114 설정집 RAG 조각의 생명주기 매핑. 벡터 컬럼은 Python/pgvector가 소유한다(retrieval_chunks와 같음). */
@Entity
@Table(name = "retrieval_setting_book_chunks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettingBookRetrievalChunk {
    @Id private UUID id;
    @Column(name = "revision_id", nullable = false) private UUID revisionId;
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
}
