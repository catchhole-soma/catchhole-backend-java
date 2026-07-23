package org.monitoring.catchholebackend.domain.upload.service;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.upload.dto.response.SettingBookResponse;
import org.monitoring.catchholebackend.domain.upload.dto.response.SettingBookSummaryResponse;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.exception.UploadErrorCode;
import org.monitoring.catchholebackend.domain.upload.mapper.UploadMapper;
import org.monitoring.catchholebackend.domain.upload.parser.TextDocumentReader;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.monitoring.catchholebackend.global.storage.StoredObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettingBookServiceImpl implements SettingBookService {

    private final WorkRepository workRepository;
    private final UploadBatchRepository uploadBatchRepository;
    private final UploadFileRepository uploadFileRepository;
    private final UploadMapper uploadMapper;
    private final TextDocumentReader textDocumentReader;
    private final ObjectStorageService objectStorageService;

    @Override
    public List<SettingBookSummaryResponse> getSettingBooks(Long memberId, UUID workId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        return uploadFileRepository
                .findAllByBatchWorkIdAndFileRoleAndArchivedAtIsNullOrderByCreatedAtDesc(
                        work.getId(), UploadFileRole.SETTING_BOOK)
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    @Transactional
    public SettingBookSummaryResponse uploadSettingBook(Long memberId, UUID workId, MultipartFile file) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        textDocumentReader.readText(file);
        String originalFilename = resolveOriginalFilename(file);
        if (uploadFileRepository.existsByBatchWorkIdAndFileRoleAndOriginalFilenameAndArchivedAtIsNull(
                work.getId(), UploadFileRole.SETTING_BOOK, originalFilename)) {
            throw new AppException(UploadErrorCode.UPLOAD_SETTING_BOOK_DUPLICATED);
        }

        UploadBatch batch = uploadBatchRepository.save(
                UploadBatch.create(work, work.getMember(), UploadType.SETTING_BOOK, UploadSourceType.FILE)
        );
        batch.startProcessing();
        batch.updateFileCount(1);
        StoredObject stored = objectStorageService.putUploadFile(
                batch.getId(), originalFilename, readBytes(file), file.getContentType());
        UploadFile uploadFile = uploadFileRepository.save(uploadMapper.toEntity(
                batch,
                UploadFileRole.SETTING_BOOK,
                originalFilename,
                file.getContentType(),
                objectStorageService.toStorageUrl(stored.key()),
                file.getSize()
        ));
        uploadFile.markParsed();
        batch.complete();
        return toSummary(uploadFile);
    }

    @Override
    public SettingBookResponse getSettingBook(Long memberId, UUID workId, UUID settingBookId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        UploadFile settingBook = getActiveSettingBook(settingBookId, work);
        byte[] bytes = objectStorageService.getBytesFromStorageUrl(settingBook.getStorageUrl());
        String content = textDocumentReader.readText(settingBook.getOriginalFilename(), bytes);
        return new SettingBookResponse(
                settingBook.getId(),
                work.getId(),
                settingBook.getOriginalFilename(),
                settingBook.getFileSize(),
                content,
                settingBook.getCreatedAt()
        );
    }

    @Override
    @Transactional
    public void deleteSettingBook(Long memberId, UUID workId, UUID settingBookId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        getActiveSettingBook(settingBookId, work).archive();
    }

    private UploadFile getActiveSettingBook(UUID settingBookId, Work work) {
        return uploadFileRepository.findByIdAndBatchWorkIdAndFileRoleAndArchivedAtIsNull(
                        settingBookId, work.getId(), UploadFileRole.SETTING_BOOK)
                .orElseThrow(() -> new AppException(UploadErrorCode.UPLOAD_SETTING_BOOK_NOT_FOUND));
    }

    private SettingBookSummaryResponse toSummary(UploadFile uploadFile) {
        return new SettingBookSummaryResponse(
                uploadFile.getId(),
                uploadFile.getOriginalFilename(),
                uploadFile.getFileSize(),
                uploadFile.getCreatedAt()
        );
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_READ_FAILED, exception);
        }
    }

    private String resolveOriginalFilename(MultipartFile file) {
        return StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "untitled.txt";
    }
}
