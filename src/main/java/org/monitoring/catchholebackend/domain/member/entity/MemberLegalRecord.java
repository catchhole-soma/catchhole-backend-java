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
import org.monitoring.catchholebackend.domain.member.type.LegalDocumentType;
import org.monitoring.catchholebackend.domain.member.type.LegalRecordAction;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Getter
@Entity
@Table(
        name = "member_legal_records",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_member_legal_records_document_version",
                columnNames = {"member_id", "document_type", "document_version"}
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
            LegalDocumentType documentType,
            String documentVersion,
            LegalRecordAction actionType,
            LocalDateTime recordedAt
    ) {
        this.member = member;
        this.documentType = documentType;
        this.documentVersion = documentVersion;
        this.actionType = actionType;
        this.recordedAt = recordedAt;
    }

    public static MemberLegalRecord recordCurrent(
            Member member,
            LegalDocumentType documentType,
            LocalDateTime recordedAt
    ) {
        return new MemberLegalRecord(
                member,
                documentType,
                documentType.getCurrentVersion(),
                documentType.getRecordAction(),
                recordedAt
        );
    }
}
