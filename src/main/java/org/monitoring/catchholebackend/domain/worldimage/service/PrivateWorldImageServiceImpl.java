package org.monitoring.catchholebackend.domain.worldimage.service;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.worldimage.dto.request.PrivateImageVaultCreateRequest;
import org.monitoring.catchholebackend.domain.worldimage.dto.request.PrivateWorldImageUploadRequest;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.PrivateImageVaultResponse;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.PrivateWorldImageResponse;
import org.monitoring.catchholebackend.domain.worldimage.entity.PrivateImageVault;
import org.monitoring.catchholebackend.domain.worldimage.entity.PrivateWorldImage;
import org.monitoring.catchholebackend.domain.worldimage.exception.WorldImageErrorCode;
import org.monitoring.catchholebackend.domain.worldimage.mapper.PrivateWorldImageMapper;
import org.monitoring.catchholebackend.domain.worldimage.processor.PrivateImageCiphertext;
import org.monitoring.catchholebackend.domain.worldimage.repository.PrivateImageVaultRepository;
import org.monitoring.catchholebackend.domain.worldimage.repository.PrivateWorldImageRepository;
import org.monitoring.catchholebackend.domain.worldimage.repository.WorldSettingImageRepository;
import org.monitoring.catchholebackend.domain.worldimage.repository.CharacterImageRepository;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.exception.CommonErrorCode;
import org.monitoring.catchholebackend.global.storage.ObjectStorage;
import org.monitoring.catchholebackend.global.storage.PrivateWorldImagePaths;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.Objects;
import static org.monitoring.catchholebackend.domain.worldimage.type.PrivateWorldImageStatus.*;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PrivateWorldImageServiceImpl implements PrivateWorldImageService {
    private final PrivateImageVaultRepository vaults;
    private final PrivateWorldImageRepository images;
    private final WorldSettingImageRepository selections;
    private final CharacterImageRepository characterSelections;
    private final MemberRepository members;
    private final WorkRepository works;
    private final ObjectStorage storage;
    private final PrivateWorldImageMapper mapper;
    private final TransactionTemplate transactionTemplate;

    @Override
    public PrivateImageVaultResponse getVault(Long memberId) {
        return vaults.findByMemberId(memberId).map(mapper::toResponse).orElse(null);
    }

    @Override
    @Transactional
    public PrivateImageVaultResponse createVault(Long memberId, PrivateImageVaultCreateRequest request) {
        var member = members.findByIdForUpdate(memberId).orElseThrow(() -> new AppException(CommonErrorCode.AUTH_UNAUTHORIZED));
        var existing = vaults.findByMemberId(memberId);
        if (existing.isPresent()) {
            if (existing.get().getId().equals(request.id()) && existing.get().getKeyCheck().equals(request.keyCheck())) {
                return mapper.toResponse(existing.get());
            }
            throw new AppException(WorldImageErrorCode.PRIVATE_IMAGE_VAULT_CONFLICT);
        }
        if (vaults.existsById(request.id())) throw new AppException(WorldImageErrorCode.PRIVATE_IMAGE_VAULT_CONFLICT);
        PrivateImageCiphertext.validateBase64(request.keyCheck());
        return mapper.toResponse(vaults.saveAndFlush(PrivateImageVault.create(request.id(), member, request.keyCheck())));
    }

    @Override
    public PageResponse<PrivateWorldImageResponse> list(Long memberId, UUID workId, int page, int size) {
        works.getOwnedWork(workId, memberId);
        var result = images.findAllByWorkIdAndStatus(workId, READY,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return PageResponse.from(result, result.getContent().stream().map(mapper::toResponse).toList());
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PrivateWorldImageResponse upload(Long memberId, UUID workId, PrivateWorldImageUploadRequest request,
            MultipartFile image, MultipartFile thumbnail) {
        PrivateImageCiphertext.validateBase64(request.encryptedMetadata());
        byte[] encryptedImage = PrivateImageCiphertext.read(image, PrivateImageCiphertext.IMAGE_LIMIT);
        byte[] encryptedThumbnail = PrivateImageCiphertext.read(thumbnail, PrivateImageCiphertext.THUMBNAIL_LIMIT);
        UUID attemptId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> {
            var work = works.getOwnedWorkForUpdate(workId, memberId);
            var vault = vaults.findByMemberId(memberId)
                    .filter(value -> value.getId().equals(request.vaultId()))
                    .orElseThrow(() -> new AppException(WorldImageErrorCode.PRIVATE_IMAGE_VAULT_REQUIRED));
            if (images.existsById(request.id())) throw new AppException(WorldImageErrorCode.PRIVATE_IMAGE_CONFLICT);
            if (images.countByWorkId(workId) >= 50) throw new AppException(WorldImageErrorCode.PRIVATE_IMAGE_LIMIT);
            var reserved = PrivateWorldImage.create(request.id(), work, vault, request.encryptedMetadata(),
                    encryptedImage.length, encryptedThumbnail.length);
            reserved.reserveUpload(attemptId);
            images.saveAndFlush(reserved);
        });
        var target = new CleanupTarget(workId, request.id(), attemptId);
        try {
            storage.putBytes(PrivateWorldImagePaths.key(workId, request.id(), attemptId, false), encryptedImage, "application/octet-stream");
            storage.putBytes(PrivateWorldImagePaths.key(workId, request.id(), attemptId, true), encryptedThumbnail, "application/octet-stream");
            return transactionTemplate.execute(status -> {
                works.getOwnedWorkForUpdate(workId, memberId);
                var saved = images.findByIdForUpdate(request.id()).filter(value -> attemptId.equals(value.getStorageAttemptId()))
                        .orElseThrow(() -> new AppException(WorldImageErrorCode.PRIVATE_IMAGE_CONFLICT));
                saved.completeUpload();
                images.flush();
                return mapper.toResponse(saved);
            });
        } catch (RuntimeException failure) {
            // 예약은 별도 커밋되어 재시작 후에도 정리할 수 있고, 다른 시도의 파일은 건드리지 않는다.
            try {
                transactionTemplate.executeWithoutResult(status -> images.findByIdForUpdate(request.id())
                        .filter(value -> attemptId.equals(value.getStorageAttemptId()))
                        .ifPresent(PrivateWorldImage::requestDeletion));
                cleanup(target);
            } catch (RuntimeException ignored) {
                log.warn("개인 이미지 업로드 정리를 다음 실행에서 재시도합니다.");
            }
            throw failure;
        }
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public byte[] getCiphertext(Long memberId, UUID workId, UUID imageId, boolean thumbnail) {
        String key = transactionTemplate.execute(status -> {
            works.getOwnedWork(workId, memberId);
            var image = images.findByIdAndWorkIdAndStatus(imageId, workId, READY)
                    .orElseThrow(() -> new AppException(WorldImageErrorCode.WORLD_IMAGE_NOT_FOUND));
            return PrivateWorldImagePaths.key(workId, imageId, image.getStorageAttemptId(), thumbnail);
        });
        return storage.getBytes(key);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void delete(Long memberId, UUID workId, UUID imageId) {
        CleanupTarget target = transactionTemplate.execute(status -> {
            works.getOwnedWorkForUpdate(workId, memberId);
            var image = requireImage(workId, imageId);
            if (image.getStatus() == UPLOADING) throw new AppException(WorldImageErrorCode.PRIVATE_IMAGE_CONFLICT);
            if (selections.existsByPrivateImageId(imageId) || characterSelections.existsByPrivateImageId(imageId)) {
                throw new AppException(WorldImageErrorCode.PRIVATE_IMAGE_IN_USE);
            }
            image.requestDeletion();
            return new CleanupTarget(workId, imageId, image.getStorageAttemptId());
        });
        if (!cleanup(target)) throw new AppException(CommonErrorCode.COMMON_INTERNAL_SERVER_ERROR,
                "이미지 삭제를 마치지 못했어요. 잠시 후 다시 시도해 주세요.");
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void retryPendingCleanup() {
        LocalDateTime staleBefore = LocalDateTime.now().minusHours(1);
        var ids = transactionTemplate.execute(status -> images.findCleanupIds(staleBefore, PageRequest.of(0, 20)));
        for (UUID id : ids) {
            try {
                CleanupTarget target = transactionTemplate.execute(status -> images.findByIdForUpdate(id)
                        .filter(image -> image.getStatus() == DELETING
                                || (image.getStatus() == UPLOADING && image.getUpdatedAt().isBefore(staleBefore)))
                        .map(image -> {
                            // 실패·중단된 시도도 다음 배치의 오래된 미처리 항목을 막지 않는다.
                            image.startCleanup(LocalDateTime.now());
                            return new CleanupTarget(image.getWork().getId(), id, image.getStorageAttemptId());
                        }).orElse(null));
                if (target != null && !cleanup(target)) log.warn("개인 이미지 파일 정리를 다음 실행에서 재시도합니다.");
            } catch (RuntimeException ignored) {
                log.warn("개인 이미지 파일 정리를 다음 실행에서 재시도합니다.");
            }
        }
    }

    private boolean cleanup(CleanupTarget target) {
        if (!storage.purgePrefixes(List.of(PrivateWorldImagePaths.prefix(target.workId(), target.imageId(), target.attemptId()))).isComplete()) return false;
        transactionTemplate.executeWithoutResult(status -> images.findByIdForUpdate(target.imageId())
                .filter(image -> image.getStatus() == DELETING && Objects.equals(image.getStorageAttemptId(), target.attemptId()))
                .ifPresent(images::delete));
        return true;
    }

    private record CleanupTarget(UUID workId, UUID imageId, UUID attemptId) {}

    private PrivateWorldImage requireImage(UUID workId, UUID imageId) {
        return images.findByIdAndWorkId(imageId, workId)
                .orElseThrow(() -> new AppException(WorldImageErrorCode.WORLD_IMAGE_NOT_FOUND));
    }
}
