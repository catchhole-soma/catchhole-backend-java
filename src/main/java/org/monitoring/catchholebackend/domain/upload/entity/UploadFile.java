package org.monitoring.catchholebackend.domain.upload.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileParseStatus;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;



@Getter
@Entity
@Table(name = "upload_files")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
/*
 * upload_files 테이블
 *
 * 하나의 UploadBatch에 포함된 개별 원본 파일을 추적한다.
 * 회차 원고 파일과 설정집 파일을 fileRole로 구분하고,
 * 원본 파일명, MIME 타입, S3 저장 위치, 파일 크기, 파싱 상태를 기록한다.
 *
 * 회차 원고 파일의 경우 사용자 확정값이 적용된 시작 회차, 끝 회차,
 * 회차 개수를 저장한다.
 *
 * 업로드로 생성된 Episode는 episodes.source_file_id를 통해
 * 어떤 UploadFile에서 파생되었는지 연결된다.
 * 이후 분석 작업은 batchId -> upload_files -> episodes 흐름으로
 * 분석 대상 회차를 찾는다.
 */
public class UploadFile extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "batch_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_upload_files_batch")
    )
    private UploadBatch batch;

    // 원고의 종류(설정집 or 회차)
    @Enumerated(EnumType.STRING)
    @Column(name = "file_role", nullable = false, length = 30)
    private UploadFileRole fileRole;

    // 사용자가 업로드한 원본 파일명. 설정집 목록의 표시명으로 사용한다.
    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    //파일 형식(txt 인지 docx 인지)
    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "storage_url", length = 512)
    private String storageUrl;

    // 설정집에서 추출한 현재 편집용 텍스트의 저장 위치. 업로드 원본 storageUrl과 분리한다.
    @Column(name = "content_storage_url", length = 512)
    private String contentStorageUrl;

    // 원본 업로드 파일의 크기(byte 단위)
    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "detected_episode_start_no")
    private Integer episodeStartNo;

    @Column(name = "detected_episode_end_no")
    private Integer episodeEndNo;

    // 해당 파일에서 최종 생성한 회차 개수
    @Column(name = "detected_episode_count")
    private Integer episodeCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false, length = 20)
    private UploadFileParseStatus parseStatus;

    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    private UploadFile(
            UploadBatch batch,
            UploadFileRole fileRole,
            String originalFilename,
            String mimeType,
            String storageUrl,
            long fileSize
    ) {
        this.batch = batch;
        this.fileRole = fileRole;
        this.originalFilename = originalFilename;
        this.mimeType = mimeType;
        this.storageUrl = storageUrl;
        this.fileSize = fileSize;
        this.parseStatus = UploadFileParseStatus.PENDING;
    }

    public static UploadFile create(
            UploadBatch batch,
            UploadFileRole fileRole,
            String originalFilename,
            String mimeType,
            String storageUrl,
            long fileSize
    ) {
        return new UploadFile(batch, fileRole, originalFilename, mimeType, storageUrl, fileSize);
    }

    public void linkEditableContent(String contentStorageUrl) {
        this.contentStorageUrl = contentStorageUrl;
    }

    public void markEpisodesParsed(
            int episodeStartNo,
            int episodeEndNo,
            int episodeCount
    ) {
        this.episodeStartNo = episodeStartNo;
        this.episodeEndNo = episodeEndNo;
        this.episodeCount = episodeCount;
        this.parseStatus = UploadFileParseStatus.PARSED;
    }

    public void markParsed() {
        this.parseStatus = UploadFileParseStatus.PARSED;
    }

    public void markFailed() {
        this.parseStatus = UploadFileParseStatus.FAILED;
    }

    /** 업로드 원본을 파기한 뒤 존재하지 않는 저장소 위치를 다시 노출하지 않도록 참조를 비운다. */
    public void purgeStoredSource() {
        this.storageUrl = null;
    }

    public void archive() {
        this.archivedAt = LocalDateTime.now();
    }
}
