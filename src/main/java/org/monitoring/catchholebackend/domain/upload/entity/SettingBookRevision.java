package org.monitoring.catchholebackend.domain.upload.entity;

import jakarta.persistence.*;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
import org.monitoring.catchholebackend.global.storage.StoredTextObject;
import org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind;

@Getter
@Entity
@Table(name = "setting_book_revisions", uniqueConstraints = @UniqueConstraint(name = "uk_setting_book_revisions_book_revision", columnNames = {"setting_book_id", "revision_no"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettingBookRevision extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(nullable = false, updatable = false)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "setting_book_id", nullable = false, updatable = false)
    private UploadFile settingBook;
    @Column(name = "revision_no", nullable = false, updatable = false)
    private int revisionNo;
    @Column(name = "content_s3_key", nullable = false, length = 512, updatable = false)
    private String contentS3Key;
    @Column(name = "content_s3_version", length = 255, updatable = false)
    private String contentS3Version;
    @Column(name = "content_hash", nullable = false, length = 64, updatable = false)
    private String contentHash;
    @Column(name = "char_count", nullable = false, updatable = false)
    private int charCount;
    @Enumerated(EnumType.STRING)
    @Column(name = "reference_time_kind", nullable = false, length = 20, updatable = false)
    private SettingBookReferenceTimeKind referenceTimeKind = SettingBookReferenceTimeKind.UNSPECIFIED;
    @Column(name = "reference_episode_no", updatable = false)
    private Integer referenceEpisodeNo;

    public static SettingBookRevision create(UploadFile book, int revisionNo, StoredTextObject text) {
        return create(book, revisionNo, text, SettingBookReferenceTimeKind.UNSPECIFIED, null);
    }

    public static SettingBookRevision create(UploadFile book, int revisionNo, StoredTextObject text,
            SettingBookReferenceTimeKind referenceTimeKind, Integer referenceEpisodeNo) {
        Objects.requireNonNull(referenceTimeKind).validate(referenceEpisodeNo);
        if (book.getFileRole() != org.monitoring.catchholebackend.domain.upload.type.UploadFileRole.SETTING_BOOK
                || revisionNo < 1 || text.key() == null || !text.contentHash().matches("[0-9a-f]{64}") || text.charCount() < 0) {
            throw new IllegalArgumentException("설정집 불변 원문 버전이 올바르지 않습니다.");
        }
        SettingBookRevision revision = new SettingBookRevision();
        revision.settingBook = Objects.requireNonNull(book);
        revision.revisionNo = revisionNo;
        revision.contentS3Key = text.key();
        revision.contentS3Version = text.versionId();
        revision.contentHash = text.contentHash();
        revision.charCount = text.charCount();
        revision.referenceTimeKind = referenceTimeKind;
        revision.referenceEpisodeNo = referenceEpisodeNo;
        return revision;
    }
}
