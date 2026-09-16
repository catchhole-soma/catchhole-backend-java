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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
        var result = images.findAllByWorkId(workId,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return PageResponse.from(result, result.getContent().stream().map(mapper::toResponse).toList());
    }

    @Override
    @Transactional
    public PrivateWorldImageResponse upload(Long memberId, UUID workId, PrivateWorldImageUploadRequest request,
            MultipartFile image, MultipartFile thumbnail) {
        var work = works.getOwnedWorkForUpdate(workId, memberId);
        var vault = vaults.findByMemberId(memberId)
                .filter(value -> value.getId().equals(request.vaultId()))
                .orElseThrow(() -> new AppException(WorldImageErrorCode.PRIVATE_IMAGE_VAULT_REQUIRED));
        if (images.existsById(request.id())) throw new AppException(WorldImageErrorCode.PRIVATE_IMAGE_CONFLICT);
        if (images.countByWorkId(workId) >= 50) throw new AppException(WorldImageErrorCode.PRIVATE_IMAGE_LIMIT);
        PrivateImageCiphertext.validateBase64(request.encryptedMetadata());
        byte[] encryptedImage = PrivateImageCiphertext.read(image, PrivateImageCiphertext.IMAGE_LIMIT);
        byte[] encryptedThumbnail = PrivateImageCiphertext.read(thumbnail, PrivateImageCiphertext.THUMBNAIL_LIMIT);
        // 먼저 ID를 예약해 경쟁 요청의 저장소 덮어쓰기를 막는다. 실패 시 두 객체의 모든 버전을 정리한다.
        var saved = images.saveAndFlush(PrivateWorldImage.create(request.id(), work, vault,
                request.encryptedMetadata(), encryptedImage.length, encryptedThumbnail.length));
        String prefix = PrivateWorldImagePaths.prefix(workId, request.id());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) return;
                try {
                    if (!storage.purgePrefixes(List.of(prefix)).isComplete()) {
                        log.warn("개인 이미지 업로드 취소 파일 정리가 완료되지 않았습니다.");
                    }
                } catch (RuntimeException ignored) {
                    log.warn("개인 이미지 업로드 취소 파일 정리에 실패했습니다.");
                }
            }
        });
        storage.putBytes(PrivateWorldImagePaths.key(workId, request.id(), false), encryptedImage, "application/octet-stream");
        storage.putBytes(PrivateWorldImagePaths.key(workId, request.id(), true), encryptedThumbnail, "application/octet-stream");
        return mapper.toResponse(saved);
    }

    @Override
    public byte[] getCiphertext(Long memberId, UUID workId, UUID imageId, boolean thumbnail) {
        works.getOwnedWork(workId, memberId);
        requireImage(workId, imageId);
        return storage.getBytes(PrivateWorldImagePaths.key(workId, imageId, thumbnail));
    }

    @Override
    @Transactional
    public void delete(Long memberId, UUID workId, UUID imageId) {
        works.getOwnedWorkForUpdate(workId, memberId);
        var image = requireImage(workId, imageId);
        if (selections.existsByPrivateImageId(imageId) || characterSelections.existsByPrivateImageId(imageId)) throw new AppException(WorldImageErrorCode.PRIVATE_IMAGE_IN_USE);
        if (!storage.purgePrefixes(List.of(PrivateWorldImagePaths.prefix(workId, imageId))).isComplete()) {
            throw new AppException(CommonErrorCode.COMMON_INTERNAL_SERVER_ERROR, "이미지 삭제를 마치지 못했어요. 다시 시도해 주세요.");
        }
        images.delete(image);
    }

    private PrivateWorldImage requireImage(UUID workId, UUID imageId) {
        return images.findByIdAndWorkId(imageId, workId)
                .orElseThrow(() -> new AppException(WorldImageErrorCode.WORLD_IMAGE_NOT_FOUND));
    }
}
