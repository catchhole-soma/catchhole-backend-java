package org.monitoring.catchholebackend.domain.retrieval.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Python prepares these derived manifests; Spring owns source lifecycle and physical purge. */
@Entity
@Table(name = "retrieval_sources")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RetrievalSource {
    @Id @Column(name = "episode_id") private UUID episodeId;
    @Column(name = "work_id", nullable = false) private UUID workId;
    @Column(name = "source_key", nullable = false, length = 512) private String sourceKey;
    @Column(name = "source_version", length = 100) private String sourceVersion;
    @Column(name = "source_hash", nullable = false, length = 64) private String sourceHash;
    @Column(name = "chunk_policy", nullable = false, length = 100) private String chunkPolicy;
    @Column(name = "chunk_count", nullable = false) private int chunkCount;
    @Column(name = "prepared_at", nullable = false) private LocalDateTime preparedAt;
}
