package org.monitoring.catchholebackend.domain.validation.entity;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
/** 추가 전용 판단 이력. report 무효화·작품 파기에서는 finding보다 먼저 함께 지운다. */
@Entity @Table(name="validation_judgment_history") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ValidationJudgmentHistory extends BaseEntity {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="report_id",nullable=false,updatable=false) private ValidationReport report;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="finding_id",nullable=false,updatable=false) private ValidationFinding finding;
    @Column(name="check_id",updatable=false) private UUID checkId;
    @Column(name="check_revision",updatable=false) private Long checkRevision;
    /** 첫 작가 판단처럼 이전 판단 행이 없었으면 null이다. */
    @Enumerated(EnumType.STRING) @Column(name="from_judgment",length=20,updatable=false) private ValidationJudgmentValue fromJudgment;
    @Enumerated(EnumType.STRING) @Column(name="to_judgment",nullable=false,length=20,updatable=false) private ValidationJudgmentValue toJudgment;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=30,updatable=false) private ValidationJudgmentHistoryReason reason;
    public static ValidationJudgmentHistory author(ValidationJudgment j,ValidationJudgmentValue from) { return create(j,from,j.getJudgment(),ValidationJudgmentHistoryReason.AUTHOR); }
    /** 옛 revision의 판단 값은 그대로 두고 새 revision이 PENDING에서 시작한다는 사실만 남긴다. */
    public static ValidationJudgmentHistory supersede(ValidationJudgment j) { return create(j,j.getJudgment(),ValidationJudgmentValue.PENDING,ValidationJudgmentHistoryReason.REVISION_SUPERSEDED); }
    private static ValidationJudgmentHistory create(ValidationJudgment j,ValidationJudgmentValue from,ValidationJudgmentValue to,ValidationJudgmentHistoryReason reason) {
        var x=new ValidationJudgmentHistory();x.report=j.getReport();x.finding=j.getFinding();x.checkId=j.getCheckId();x.checkRevision=j.getCheckRevision();x.fromJudgment=from;x.toJudgment=to;x.reason=reason;return x;
    }
}
