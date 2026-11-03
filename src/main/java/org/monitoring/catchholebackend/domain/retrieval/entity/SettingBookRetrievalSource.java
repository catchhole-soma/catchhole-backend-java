package org.monitoring.catchholebackend.domain.retrieval.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * V114 설정집 RAG 색인 header의 생명주기 매핑. 검수 Worker가 접수 때 현재 판 revision 하나를 채우며(Python),
 * Spring은 설정집 수정·보관과 작품 파기 때 물리 삭제만 소유한다.
 */
@Entity
@Table(name = "retrieval_setting_book_sources")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettingBookRetrievalSource {
    @Id @Column(name = "revision_id") private UUID revisionId;
    @Column(name = "setting_book_id", nullable = false) private UUID settingBookId;
    @Column(name = "work_id", nullable = false) private UUID workId;
    @Column(name = "source_key", nullable = false, length = 512) private String sourceKey;
    @Column(name = "source_version") private String sourceVersion;
    @Column(name = "source_hash", nullable = false, length = 64) private String sourceHash;
    @Column(name = "chunk_policy", nullable = false, length = 100) private String chunkPolicy;
    @Column(name = "chunk_count", nullable = false) private int chunkCount;
    @Column(name = "prepared_at", nullable = false) private LocalDateTime preparedAt;
}
