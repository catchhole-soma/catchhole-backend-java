package org.monitoring.catchholebackend.domain.upload.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.upload.dto.request.SettingBookUpdateRequest;
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

    private static final long MAX_SETTING_BOOK_SIZE = 10L * 1024 * 1024;
    private static final String TEXT_MIME_TYPE = "text/plain; charset=UTF-8";
    private static final String DOCX_MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

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
        String content = textDocumentReader.readText(file);
        String originalFilename = resolveOriginalFilename(file);
        String mimeType = resolveMimeType(originalFilename);

        UploadBatch batch = uploadBatchRepository.save(
                UploadBatch.create(work, work.getMember(), UploadType.SETTING_BOOK, UploadSourceType.FILE)
        );
        batch.startProcessing();
        batch.updateFileCount(1);
        StoredObject stored = objectStorageService.putUploadFile(
                batch.getId(), originalFilename, readBytes(file), mimeType);
        UploadFile uploadFile = uploadFileRepository.save(uploadMapper.toEntity(
                batch,
                UploadFileRole.SETTING_BOOK,
                originalFilename,
                mimeType,
                objectStorageService.toStorageUrl(stored.key()),
                file.getSize()
        ));
        StoredObject storedContent = objectStorageService.putSettingBookContent(
                work.getId(),
                uploadFile.getId(),
                uploadFile.getOriginalFilename(),
                content
        );
        uploadFile.linkEditableContent(objectStorageService.toStorageUrl(storedContent.key()));
        uploadFile.markParsed();
        batch.complete();
        return toSummary(uploadFile);
    }

    @Override
    public SettingBookResponse getSettingBook(Long memberId, UUID workId, UUID settingBookId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        UploadFile settingBook = getActiveSettingBook(settingBookId, work);
        return toResponse(settingBook, work, readCurrentContent(settingBook));
    }

    @Override
    @Transactional
    public SettingBookResponse updateSettingBook(
            Long memberId,
            UUID workId,
            UUID settingBookId,
            SettingBookUpdateRequest request
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        UploadFile settingBook = getActiveSettingBook(settingBookId, work);
        byte[] editedContent = request.content().getBytes(StandardCharsets.UTF_8);
        if (editedContent.length > MAX_SETTING_BOOK_SIZE) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_TOO_LARGE);
        }

        StoredObject stored = objectStorageService.putSettingBookContent(
                work.getId(),
                settingBook.getId(),
                settingBook.getOriginalFilename(),
                request.content()
        );
        settingBook.linkEditableContent(objectStorageService.toStorageUrl(stored.key()));
        return toResponse(settingBook, work, request.content());
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

    private String readCurrentContent(UploadFile settingBook) {
        if (StringUtils.hasText(settingBook.getContentStorageUrl())) {
            byte[] contentBytes =
                    objectStorageService.getBytesFromStorageUrl(settingBook.getContentStorageUrl());
            return new String(contentBytes, StandardCharsets.UTF_8);
        }
        byte[] originalBytes = objectStorageService.getBytesFromStorageUrl(settingBook.getStorageUrl());
        return textDocumentReader.readText(settingBook.getOriginalFilename(), originalBytes);
    }

    private SettingBookSummaryResponse toSummary(UploadFile uploadFile) {
        return new SettingBookSummaryResponse(
                uploadFile.getId(),
                uploadFile.getOriginalFilename(),
                resolveResponseMimeType(uploadFile),
                uploadFile.getFileSize(),
                uploadFile.getCreatedAt()
        );
    }

    private SettingBookResponse toResponse(UploadFile uploadFile, Work work, String content) {
        return new SettingBookResponse(
                uploadFile.getId(),
                work.getId(),
                uploadFile.getOriginalFilename(),
                resolveResponseMimeType(uploadFile),
                uploadFile.getFileSize(),
                content,
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
        return textDocumentReader.requireOriginalFilename(file);
    }

    private String resolveMimeType(String originalFilename) {
        return originalFilename.toLowerCase(Locale.ROOT).endsWith(".docx")
                ? DOCX_MIME_TYPE
                : TEXT_MIME_TYPE;
    }

    private String resolveResponseMimeType(UploadFile uploadFile) {
        return StringUtils.hasText(uploadFile.getMimeType())
                ? uploadFile.getMimeType()
                : resolveMimeType(uploadFile.getOriginalFilename());
    }
}
