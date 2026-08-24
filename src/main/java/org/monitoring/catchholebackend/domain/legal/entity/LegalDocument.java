package org.monitoring.catchholebackend.domain.legal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.legal.type.LegalDocumentStatus;
import org.monitoring.catchholebackend.domain.legal.type.LegalDocumentType;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Getter
@Entity
@Table(
        name = "legal_documents",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_legal_documents_type_locale_version",
                columnNames = {"document_type", "locale", "document_version"}
        ),
        indexes = @Index(
                name = "idx_legal_documents_public_lookup",
                columnList = "locale, status, document_type"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalDocument extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 40)
    private LegalDocumentType documentType;

    @Column(nullable = false, length = 10)
    private String locale;

    @Column(name = "document_version", nullable = false, length = 50)
    private String documentVersion;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "content_markdown", nullable = false, columnDefinition = "TEXT")
    private String contentMarkdown;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LegalDocumentStatus status;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "retired_at")
    private LocalDateTime retiredAt;

    private LegalDocument(
            LegalDocumentType documentType,
            String locale,
            String documentVersion,
            String title,
            String contentMarkdown,
            String contentHash,
            LegalDocumentStatus status,
            LocalDate effectiveDate,
            LocalDateTime publishedAt,
            LocalDateTime retiredAt
    ) {
        this.documentType = documentType;
        this.locale = locale;
        this.documentVersion = documentVersion;
        this.title = title;
        this.contentMarkdown = contentMarkdown;
        this.contentHash = contentHash;
        this.status = status;
        this.effectiveDate = effectiveDate;
        this.publishedAt = publishedAt;
        this.retiredAt = retiredAt;
    }

    public static LegalDocument published(
            LegalDocumentType documentType,
            String locale,
            String documentVersion,
            String title,
            String contentMarkdown,
            String contentHash,
            LocalDate effectiveDate,
            LocalDateTime publishedAt
    ) {
        return new LegalDocument(
                documentType,
                locale,
                documentVersion,
                title,
                contentMarkdown,
                contentHash,
                LegalDocumentStatus.PUBLISHED,
                effectiveDate,
                publishedAt,
                null
        );
    }

    public static LegalDocument draft(
            LegalDocumentType documentType,
            String locale,
            String documentVersion,
            String title,
            String contentMarkdown,
            String contentHash
    ) {
        return new LegalDocument(
                documentType,
                locale,
                documentVersion,
                title,
                contentMarkdown,
                contentHash,
                LegalDocumentStatus.DRAFT,
                null,
                null,
                null
        );
    }

    public void publish(LocalDate effectiveDate, LocalDateTime publishedAt) {
        if (status != LegalDocumentStatus.DRAFT) {
            throw new IllegalStateException("초안 문서만 게시할 수 있습니다.");
        }
        this.status = LegalDocumentStatus.PUBLISHED;
        this.effectiveDate = effectiveDate;
        this.publishedAt = publishedAt;
    }

    public void retire(LocalDateTime retiredAt) {
        if (status != LegalDocumentStatus.PUBLISHED) {
            throw new IllegalStateException("게시 중인 문서만 종료할 수 있습니다.");
        }
        this.status = LegalDocumentStatus.RETIRED;
        this.retiredAt = retiredAt;
    }
}
