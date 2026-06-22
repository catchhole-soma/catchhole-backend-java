package org.monitoring.catchholebackend.domain.episode.entity;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Getter
@Entity
@Table(name = "episodes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Episode extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "work_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_episodes_work")
    )
    private Work work;

    //UploadFile_id
    //TODO: 네이밍 수정 필요 직관적이지 않음
    @Column(name = "source_file_id")
    private UUID sourceFileId;

    @Column(name = "episode_no", nullable = false)
    private int episodeNo;

    // ParsedEpisode record 의 title 에서 가져옴(분리 작업은 EpisodeUploadParser 에서 작업함)
    @Column(name = "title", length = 100)
    private String title;

    @Column(name = "content_s3_key", length = 512)
    private String contentS3Key;

    @Column(name = "content_s3_version", length = 100)
    private String contentS3Version;

    //S3 내용 변조 확인용
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    // 파싱된 회차 본문 길이. byte 크기가 아니라 Java String.length() 기준 문자 길이이다.
    @Column(name = "char_count", nullable = false)
    private int charCount;

    //episode 분석 상태 변수
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EpisodeStatus status;

    private Episode(
            Work work,
            UUID sourceFileId,
            int episodeNo,
            String title,
            String contentS3Key,
            String contentS3Version,
            String contentHash,
            int charCount
    ) {
        this.work = work;
        this.sourceFileId = sourceFileId;
        this.episodeNo = episodeNo;
        this.title = title;
        this.contentS3Key = contentS3Key;
        this.contentS3Version = contentS3Version;
        this.contentHash = contentHash;
        this.charCount = charCount;
        this.status = EpisodeStatus.UPLOADED;
    }

    public static Episode create(
            Work work,
            UUID sourceFileId,
            int episodeNo,
            String title,
            String contentS3Key,
            String contentS3Version,
            String contentHash,
            int charCount
    ) {
        return new Episode(work, sourceFileId, episodeNo, title, contentS3Key, contentS3Version, contentHash, charCount);
    }

    public void updateMetadata(int episodeNo, String title, int charCount) {
        this.episodeNo = episodeNo;
        this.title = title;
        this.charCount = charCount;
    }

    public void updateContent(
            int episodeNo,
            String title,
            String contentS3Key,
            String contentS3Version,
            String contentHash,
            int charCount
    ) {
        this.episodeNo = episodeNo;
        this.title = title;
        this.contentS3Key = contentS3Key;
        this.contentS3Version = contentS3Version;
        this.contentHash = contentHash;
        this.charCount = charCount;
        this.status = EpisodeStatus.UPLOADED;
    }

    public void updateContentStorage(String contentS3Key, String contentS3Version, String contentHash) {
        this.contentS3Key = contentS3Key;
        this.contentS3Version = contentS3Version;
        this.contentHash = contentHash;
    }

    public void markChunking() {
        this.status = EpisodeStatus.CHUNKING;
    }

    //TODO: 에피소드별로 status 관리 api가 현재 존재하지 않음
    public void markChunked() {
        this.status = EpisodeStatus.CHUNKED;
    }

    public void markPreprocessing() {
        this.status = EpisodeStatus.PREPROCESSING;
    }

    public void markPreprocessed() {
        this.status = EpisodeStatus.PREPROCESSED;
    }

    public void markAnalyzing() {
        this.status = EpisodeStatus.ANALYZING;
    }

    public void markAnalyzed() {
        this.status = EpisodeStatus.ANALYZED;
    }

    public void markFailed() {
        this.status = EpisodeStatus.FAILED;
    }

    public void archive() {
        this.status = EpisodeStatus.ARCHIVED;
    }

    public boolean isOwnedBy(Long memberId) {
        return work.isOwnedBy(memberId);
    }
}
