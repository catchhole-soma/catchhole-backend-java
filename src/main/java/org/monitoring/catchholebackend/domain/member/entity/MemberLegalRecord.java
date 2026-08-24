package org.monitoring.catchholebackend.domain.member.entity;

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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.legal.entity.LegalDocument;
import org.monitoring.catchholebackend.domain.legal.type.LegalDocumentType;
import org.monitoring.catchholebackend.domain.member.type.LegalRecordAction;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Getter
@Entity
@Table(
        name = "member_legal_records",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_member_legal_records_document",
                columnNames = {"member_id", "legal_document_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberLegalRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "member_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_member_legal_records_member")
    )
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "legal_document_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_member_legal_records_document")
    )
    private LegalDocument legalDocument;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 40)
    private LegalDocumentType documentType;

    @Column(name = "document_version", nullable = false, length = 50)
    private String documentVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 30)
    private LegalRecordAction actionType;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    private MemberLegalRecord(
            Member member,
            LegalDocument legalDocument,
            LocalDateTime recordedAt
    ) {
        this.member = member;
        this.legalDocument = legalDocument;
        this.documentType = legalDocument.getDocumentType();
        this.documentVersion = legalDocument.getDocumentVersion();
        this.actionType = LegalRecordAction.forDocumentType(legalDocument.getDocumentType());
        this.recordedAt = recordedAt;
    }

    public static MemberLegalRecord record(
            Member member,
            LegalDocument legalDocument,
            LocalDateTime recordedAt
    ) {
        return new MemberLegalRecord(member, legalDocument, recordedAt);
    }
}
