package org.monitoring.catchholebackend.domain.work.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.work.dto.request.WorkCoverUpdateRequest;
import org.monitoring.catchholebackend.domain.work.dto.response.WorkResponse;
import org.monitoring.catchholebackend.domain.work.entity.WorkCoverImage;
import org.monitoring.catchholebackend.domain.work.exception.WorkErrorCode;
import org.monitoring.catchholebackend.domain.work.repository.WorkCoverImageRepository;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkCoverAction;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.ObjectStorage;
import org.monitoring.catchholebackend.global.storage.WorkCoverImageContent;
import org.monitoring.catchholebackend.global.storage.WorkCoverImagePaths;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import static org.monitoring.catchholebackend.domain.work.type.WorkCoverImageStatus.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class WorkCoverServiceImpl implements WorkCoverService {
    private final jakarta.persistence.EntityManager entityManager;
    private final WorkRepository works;
    private final WorkCoverImageRepository images;
    private final WorkService workService;
    private final ObjectStorage storage;
    private final TransactionTemplate transactions;

    @Override
    public WorkResponse updateWorkCover(Long memberId, UUID workId, WorkCoverUpdateRequest request, MultipartFile file) {
        transactions.executeWithoutResult(status -> {
            var work = works.getOwnedWork(workId, memberId);
            work.requireActive();
            work.requireCoverVersion(request.expectedCoverVersion());
        });
        boolean replace = request.action() == WorkCoverAction.REPLACE;
        if (!replace && file != null) throw new AppException(WorkErrorCode.WORK_COVER_INVALID);
        byte[] bytes = replace ? WorkCoverImageContent.read(file) : null;
        UUID imageId = replace ? transactions.execute(status -> {
            entityManager.clear();
            var work = works.getOwnedWorkForUpdate(workId, memberId);
            work.requireCoverVersion(request.expectedCoverVersion());
            // 반복 실패·동시 요청이 저장소 예약을 무제한 쌓지 못하게 한다.
            if (images.countByWorkId(workId) >= 10) throw new AppException(WorkErrorCode.WORK_COVER_CONFLICT);
            return images.saveAndFlush(WorkCoverImage.reserve(work)).getId();
        }) : null;

        SaveResult result;
        try {
            if (replace) storage.putBytes(WorkCoverImagePaths.key(workId, imageId), bytes, "image/png");
            result = transactions.execute(status -> {
                // OSIV가 앞선 짧은 트랜잭션의 작품을 보관해도 현재 버전·삭제 상태를 다시 읽는다.
                entityManager.clear();
                var work = works.getOwnedWorkForUpdate(workId, memberId);
                work.requireCoverVersion(request.expectedCoverVersion());
                UUID oldId = work.getCoverImageId();
                if (replace) images.findByIdForUpdate(imageId)
                        .orElseThrow(() -> new AppException(WorkErrorCode.WORK_COVER_CONFLICT)).completeUpload();
                work.changeCover(imageId);
                if (oldId != null) images.findByIdForUpdate(oldId).ifPresent(WorkCoverImage::requestDeletion);
                // 작품 정보·표지 참조·이전 파일 정리 예약이 함께 커밋되거나 함께 취소된다.
                var response = workService.updateWork(memberId, workId, request.work());
                images.flush();
                return new SaveResult(response, oldId);
            });
        } catch (RuntimeException failure) {
            if (imageId != null) abandonUpload(workId, imageId);
            throw failure;
        }
        if (result.oldImageId() != null) cleanupQuietly(workId, result.oldImageId());
        return result.response();
    }

    @Override
    public byte[] getWorkCover(Long memberId, UUID workId, UUID imageId) {
        requireCurrentCover(memberId, workId, imageId);
        byte[] bytes = storage.getBytes(WorkCoverImagePaths.key(workId, imageId));
        requireCurrentCover(memberId, workId, imageId);
        return bytes;
    }

    private void requireCurrentCover(Long memberId, UUID workId, UUID imageId) {
        transactions.executeWithoutResult(status -> {
            entityManager.clear();
            var work = works.getOwnedWork(workId, memberId);
            work.requireActive();
            if (!imageId.equals(work.getCoverImageId()) || !images.existsByIdAndWorkIdAndStatus(imageId, workId, READY)) {
                throw new AppException(WorkErrorCode.WORK_COVER_NOT_FOUND);
            }
        });
    }

    private void abandonUpload(UUID workId, UUID imageId) {
        try {
            transactions.executeWithoutResult(status -> {
                entityManager.clear();
                var work = works.findByIdForUpdate(workId);
                if (work.isPresent() && imageId.equals(work.get().getCoverImageId())) return;
                images.findByIdForUpdate(imageId).ifPresent(WorkCoverImage::requestDeletion);
            });
            cleanupQuietly(workId, imageId);
        } catch (RuntimeException ignored) { log.warn("표지 업로드 정리를 다음 실행에서 재시도합니다."); }
    }

    @Override
    public void retryPendingCleanup() {
        LocalDateTime staleBefore = LocalDateTime.now().minusHours(1);
        var ids = transactions.execute(status -> images.findCleanupIds(staleBefore, PageRequest.of(0, 20)));
        for (UUID id : ids) {
            try {
                UUID workId = transactions.execute(status -> images.findByIdForUpdate(id)
                        .filter(image -> image.getStatus() == DELETING
                                || (image.getStatus() == UPLOADING && image.getUpdatedAt().isBefore(staleBefore)))
                        .map(image -> {
                            image.startCleanup();
                            return image.getWork().getId();
                        }).orElse(null));
                if (workId != null) cleanupQuietly(workId, id);
            } catch (RuntimeException ignored) { log.warn("표지 파일 정리를 다음 실행에서 재시도합니다."); }
        }
    }

    private void cleanupQuietly(UUID workId, UUID imageId) {
        try {
            boolean deleting = Boolean.TRUE.equals(transactions.execute(status -> images.findByIdForUpdate(imageId)
                    .map(image -> {
                        if (image.getStatus() != DELETING) return false;
                        image.startCleanup();
                        return true;
                    // 만료 예약을 이미 회수한 뒤 늦게 끝난 업로드도 고유 prefix를 다시 비운다.
                    // 현재 표지는 FK 때문에 이미지 행 없이 존재할 수 없다.
                    }).orElse(true)));
            if (!deleting) return;
            if (!storage.purgePrefixes(List.of(WorkCoverImagePaths.prefix(workId, imageId))).isComplete()) return;
            transactions.executeWithoutResult(status -> images.findByIdForUpdate(imageId)
                    .filter(image -> image.getStatus() == DELETING).ifPresent(images::delete));
        } catch (RuntimeException ignored) { log.warn("표지 파일 정리를 다음 실행에서 재시도합니다."); }
    }

    private record SaveResult(WorkResponse response, UUID oldImageId) {}
}
