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
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadStatus;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Getter
@Entity
@Table(name = "upload_batches")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
/*
 * upload_batches 테이블
 * 회차 업로드 요청 1건을 batch 단위로 추적한다.
 * batch에 속한 원본 파일은 upload_files로 저장하고,
 * 분석 작업은 batchId를 기준으로 작업하며 이후 해당 ID 를 가지고있는 upload_files -> episodes를 따라
 * 분석 대상 회차를 찾는다.
 */
public class UploadBatch extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "work_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_upload_batches_work")
    )
    private Work work;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "member_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_upload_batches_member")
    )
    private Member member;

    //업로드 타입 유무에 관련해서 서비스 로직이 다름(자세한 내용은 python ai worker 쪽 확인)
    @Enumerated(EnumType.STRING)
    @Column(name = "upload_type", nullable = false, length = 40)
    private UploadType uploadType;

    //파일 형식 필드 (현재 txt 파일만 지원 가능 txt 의 경우가 FILE)
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private UploadSourceType sourceType;

    //TODO: 실패 로직 생각해서 구현 수정하기
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UploadStatus status;


    //업로드 한 파일의 개수 (설정집 포함)
    //TODO : 설정집 있는지 여부를 boolean 형식으로 둘지 말지 논의 필요
    @Column(name = "file_count", nullable = false)
    private int fileCount;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    private UploadBatch(Work work, Member member, UploadType uploadType, UploadSourceType sourceType) {
        this.work = work;
        this.member = member;
        this.uploadType = uploadType;
        this.sourceType = sourceType;
        this.status = UploadStatus.PENDING;
        this.fileCount = 0;
    }

    public static UploadBatch create(
            Work work,
            Member member,
            UploadType uploadType,
            UploadSourceType sourceType
    ) {
        return new UploadBatch(work, member, uploadType, sourceType);
    }

    public void updateFileCount(int fileCount) {
        this.fileCount = fileCount;
    }

    public void startProcessing() {
        this.status = UploadStatus.PROCESSING;
    }

    public void complete() {
        this.status = UploadStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    public void fail() {
        this.status = UploadStatus.FAILED;
        this.completedAt = LocalDateTime.now();
    }
}
