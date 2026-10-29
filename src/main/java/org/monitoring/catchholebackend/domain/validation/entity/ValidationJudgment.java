package org.monitoring.catchholebackend.domain.validation.entity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
/** finding 행 하나의 현재 작가 판단. 행이 없으면 PENDING이며 첫 판단 저장 때 만든다. 저장 자체는 추출을 시작하지 않는다. */
@Entity @Table(name="validation_judgments") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ValidationJudgment extends BaseEntity {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="report_id",nullable=false,updatable=false) private ValidationReport report;
    /** revision이 바뀐 옛 finding에 남은 판단은 현재 판단이 아니라 이력으로만 보여준다. */
    @OneToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="finding_id",nullable=false,unique=true,updatable=false) private ValidationFinding finding;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20,updatable=false) private ValidationBranch branch;
    @Column(name="check_id",updatable=false) private UUID checkId;
    @Column(name="check_revision",updatable=false) private Long checkRevision;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private ValidationJudgmentValue judgment;
    /** 판단 CAS. 첫 저장이 1이고 실제 값 변경마다 증가한다. 행이 없는 항목의 응답 version은 0이다. */
    @Column(nullable=false) private long version;
    /** PENDING으로 되돌리면 null이다. */
    @Column(name="decided_at") private LocalDateTime decidedAt;
    public static ValidationJudgment decide(ValidationFinding f,ValidationJudgmentValue value) {
        var x=new ValidationJudgment();x.report=f.getReport();x.finding=f;x.branch=f.getBranch();x.checkId=f.getCheckId();x.checkRevision=f.getCheckRevision();x.apply(value);return x;
    }
    public void change(ValidationJudgmentValue value) { apply(value); }
    private void apply(ValidationJudgmentValue value) {
        if(!value.allowedIn(branch))throw new IllegalArgumentException("분기에 맞지 않는 작가 판단입니다.");
        judgment=value;decidedAt=value==ValidationJudgmentValue.PENDING?null:LocalDateTime.now();version++;
    }
}
