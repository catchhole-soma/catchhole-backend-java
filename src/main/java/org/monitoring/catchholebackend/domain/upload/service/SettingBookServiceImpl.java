package org.monitoring.catchholebackend.domain.upload.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.upload.dto.request.SettingBookUpdateRequest;
import org.monitoring.catchholebackend.domain.upload.dto.response.SettingBookResponse;
import org.monitoring.catchholebackend.domain.upload.dto.response.SettingBookSummaryResponse;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.exception.UploadErrorCode;
import org.monitoring.catchholebackend.domain.upload.mapper.UploadMapper;
import org.monitoring.catchholebackend.domain.upload.parser.DocumentFormat;
import org.monitoring.catchholebackend.domain.upload.parser.TextDocumentReader;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind;
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

    private final WorkRepository workRepository;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final UploadBatchRepository uploadBatchRepository;
    private final UploadFileRepository uploadFileRepository;
    private final UploadMapper uploadMapper;
    private final TextDocumentReader textDocumentReader;
    private final ObjectStorageService objectStorageService;
    private final org.monitoring.catchholebackend.domain.upload.repository.SettingBookRevisionRepository revisions;
    private final org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository jobs;
    private final org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisJobMapper jobMapper;
    private final org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService runState;
    private final org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobService jobService;
    private final org.monitoring.catchholebackend.domain.analysis.service.AnalysisGuideService analysisGuide;
    private final org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService tokens;
    private final org.monitoring.catchholebackend.domain.upload.processor.SettingBookStorageCompensator storageCompensator;


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
        return uploadSettingBook(memberId, workId, file, SettingBookReferenceTimeKind.UNSPECIFIED, null);
    }

    @Override
    @Transactional
    public SettingBookSummaryResponse uploadSettingBook(Long memberId, UUID workId, MultipartFile file,
            SettingBookReferenceTimeKind referenceTimeKind, Integer referenceEpisodeNo) {
        referenceTimeKind = referenceTimeKind == null ? SettingBookReferenceTimeKind.UNSPECIFIED : referenceTimeKind;
        referenceTimeKind.validate(referenceEpisodeNo);
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        String content = textDocumentReader.readText(file);
        String originalFilename = resolveOriginalFilename(file);
        String mimeType = resolveMimeType(originalFilename);
        tokens.ensureAnalysisCanStart(memberId);
        analysisGuide.markAnalysisStarted(memberId);
        List<String> newObjectKeys = new java.util.ArrayList<>();
        storageCompensator.registerRollbackCompensation(newObjectKeys);

        UploadBatch batch = uploadBatchRepository.save(
                UploadBatch.create(work, work.getMember(), UploadType.SETTING_BOOK, UploadSourceType.FILE)
        );
        batch.startProcessing();
        batch.updateFileCount(1);
        StoredObject stored = objectStorageService.putUploadFile(
                batch.getId(), originalFilename, readBytes(file), mimeType);
        newObjectKeys.add(stored.key());
        UploadFile uploadFile = uploadFileRepository.save(uploadMapper.toEntity(
                batch,
                UploadFileRole.SETTING_BOOK,
                originalFilename,
                mimeType,
                objectStorageService.toStorageUrl(stored.key()),
                file.getSize()
        ));
        saveRevision(work, uploadFile, content, referenceTimeKind, referenceEpisodeNo, newObjectKeys);
        uploadFile.markParsed();
        batch.complete();
        enqueue(work, uploadFile);
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
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        UploadFile settingBook = getActiveSettingBook(settingBookId, work);
        var currentRevision = settingBook.getCurrentSettingBookRevision();
        if (!java.util.Objects.equals(request.expectedRevisionNo(), currentRevision == null ? 0 : currentRevision.getRevisionNo())) {
            throw new AppException(UploadErrorCode.UPLOAD_SETTING_BOOK_REVISION_CONFLICT);
        }
        if (request.referenceTimeKind() == null && request.referenceEpisodeNo() != null) {
            throw new AppException(UploadErrorCode.UPLOAD_SETTING_BOOK_REFERENCE_INVALID);
        }
        SettingBookReferenceTimeKind referenceTimeKind = request.referenceTimeKind() != null ? request.referenceTimeKind()
                : currentRevision == null ? SettingBookReferenceTimeKind.UNSPECIFIED : currentRevision.getReferenceTimeKind();
        Integer referenceEpisodeNo = request.referenceTimeKind() != null ? request.referenceEpisodeNo()
                : currentRevision == null ? null : currentRevision.getReferenceEpisodeNo();
        referenceTimeKind.validate(referenceEpisodeNo);
        byte[] editedContent = request.content().getBytes(StandardCharsets.UTF_8);
        if (editedContent.length > MAX_SETTING_BOOK_SIZE) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_TOO_LARGE);
        }

        if (settingBook.getCurrentSettingBookRevision() == null
                || !currentRevision.getContentHash().equals(objectStorageService.contentHash(request.content()))
                || currentRevision.getReferenceTimeKind() != referenceTimeKind
                || !java.util.Objects.equals(currentRevision.getReferenceEpisodeNo(), referenceEpisodeNo)) {
            runState.invalidateRunsForSettingBookChangeForUpdate(work.getId(), settingBook.getId(), "설정집 원문이 변경되어 재분석이 필요합니다.");
            eventPublisher.publishEvent(new org.monitoring.catchholebackend.domain.validation.event.ValidationStagingEvents.SettingBookSourceChanged(work.getId(), settingBook.getId()));
            List<String> newObjectKeys = new java.util.ArrayList<>();
            storageCompensator.registerRollbackCompensation(newObjectKeys);
            saveRevision(work, settingBook, request.content(), referenceTimeKind, referenceEpisodeNo, newObjectKeys);
        }
        return toResponse(settingBook, work, request.content());
    }

    @Override
    @Transactional
    public void deleteSettingBook(Long memberId, UUID workId, UUID settingBookId) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        UploadFile book = getActiveSettingBook(settingBookId, work);
        runState.invalidateRunsForSettingBookChangeForUpdate(work.getId(), book.getId(), "설정집이 보관되어 기존 분석을 사용할 수 없습니다.");
        eventPublisher.publishEvent(new org.monitoring.catchholebackend.domain.validation.event.ValidationStagingEvents.SettingBookSourceChanged(work.getId(), book.getId()));
        book.archive();
    }

    @Override
    @Transactional
    public org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisJobResponse analyzeSettingBook(
            Long memberId, UUID workId, UUID settingBookId) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        UploadFile book = getActiveSettingBook(settingBookId, work);
        org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob latest = latest(book);
        if (latest != null && latest.hasCurrentSourceVersion()
                && (latest.getStatus() == org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING
                    || latest.getStatus() == org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING)) {
            return jobResponse(latest, book);
        }
        tokens.ensureAnalysisCanStart(memberId);
        if (book.getCurrentSettingBookRevision() == null) {
            List<String> newObjectKeys = new java.util.ArrayList<>();
            storageCompensator.registerRollbackCompensation(newObjectKeys);
            saveRevision(work, book, readCurrentContent(book), SettingBookReferenceTimeKind.UNSPECIFIED, null, newObjectKeys);
        }
        if (latest != null && latest.hasCurrentSourceVersion()) {
            if (latest.getStatus() == org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING
                    || latest.getStatus() == org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING) return jobResponse(latest, book);
            if ((latest.getStatus() == org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.FAILED
                    || latest.getStatus() == org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.SUCCEEDED
                        && latest.getJournalStatus() == org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus.INCOMPLETE)
                    && latest.getJournalStatus() != org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus.INVALIDATED) {
                return jobService.retryFailedAnalysisJob(memberId, workId, latest.getId()).getFirst();
            }
        }
        runState.invalidateRunsForSettingBookChangeForUpdate(work.getId(), book.getId(), "설정집 재분석으로 이전 대기 비교를 취소했습니다.");
        analysisGuide.markAnalysisStarted(memberId);
        return jobResponse(enqueue(work, book), book);
    }

    private void saveRevision(Work work, UploadFile book, String content, SettingBookReferenceTimeKind referenceTimeKind,
            Integer referenceEpisodeNo, List<String> newObjectKeys) {
        int number = book.getCurrentSettingBookRevision() == null ? 1 : book.getCurrentSettingBookRevision().getRevisionNo() + 1;
        org.monitoring.catchholebackend.global.storage.StoredTextObject stored = objectStorageService.putSettingBookRevision(
                work.getId(), book.getId(), book.getOriginalFilename(), content);
        newObjectKeys.add(stored.key());
        book.linkCurrentRevision(revisions.save(org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision.create(
                book, number, stored, referenceTimeKind, referenceEpisodeNo)));
    }

    private org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob enqueue(Work work, UploadFile book) {
        org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob job = org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.createSettingBook(
                work, book, book.getCurrentSettingBookRevision());
        runState.initializeRun(List.of(job));
        return job;
    }

    private org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob latest(UploadFile book) {
        return jobs.findFirstBySourceSettingBookIdAndJobTypeOrderByCreatedAtDescIdDesc(book.getId(),
                org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION).orElse(null);
    }

    private org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisJobResponse jobResponse(
            org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob job, UploadFile book) {
        return jobMapper.toResponse(job, List.of(book), List.of());
    }

    @Override
    public org.monitoring.catchholebackend.domain.upload.dto.response.SettingBookRevisionResponse getSettingBookRevision(
            Long memberId, UUID workId, UUID settingBookId, UUID revisionId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        var revision = revisions.findByIdAndSettingBookId(revisionId, settingBookId)
                .filter(r -> r.getSettingBook().getFileRole() == UploadFileRole.SETTING_BOOK
                        && r.getSettingBook().getBatch().getWork().getId().equals(work.getId()))
                .orElseThrow(() -> new AppException(UploadErrorCode.UPLOAD_SETTING_BOOK_NOT_FOUND));
        var book = revision.getSettingBook();
        String content = new String(objectStorageService.getBytesFromStorageUrl(objectStorageService.toStorageUrl(revision.getContentS3Key())), StandardCharsets.UTF_8);
        var source = new org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisSourceResponse(
                org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.SETTING_BOOK, null, null,
                book.getId(), book.getOriginalFilename(), revision.getId(), revision.getRevisionNo(), revision.getContentHash(),
                revision.getReferenceTimeKind(), revision.getReferenceEpisodeNo());
        return new org.monitoring.catchholebackend.domain.upload.dto.response.SettingBookRevisionResponse(book.getId(), book.getOriginalFilename(),
                revision.getId(), revision.getRevisionNo(), revision.getContentHash(), revision.getCharCount(), content, source,
                revision.getReferenceTimeKind(), revision.getReferenceEpisodeNo());
    }

    private boolean changed(UploadFile book, org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob job) {
        return job != null && (book.getCurrentSettingBookRevision() == null
                || !book.getCurrentSettingBookRevision().getId().equals(job.getSourceSettingBookRevision().getId()));
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
        var revision = uploadFile.getCurrentSettingBookRevision();
        var latest = latest(uploadFile);
        return new SettingBookSummaryResponse(
                uploadFile.getId(),
                uploadFile.getOriginalFilename(),
                resolveResponseMimeType(uploadFile),
                uploadFile.getFileSize(),
                uploadFile.getCreatedAt(), uploadFile.getBatch().getId(),
                revision == null ? null : revision.getId(), revision == null ? null : revision.getRevisionNo(),
                revision == null ? null : revision.getContentHash(), revision == null ? null : revision.getCharCount(),
                latest == null ? null : jobResponse(latest, uploadFile), latest == null ? null : latest.getStatus(), changed(uploadFile, latest),
                revision == null ? SettingBookReferenceTimeKind.UNSPECIFIED : revision.getReferenceTimeKind(),
                revision == null ? null : revision.getReferenceEpisodeNo()
        );
    }

    private SettingBookResponse toResponse(UploadFile uploadFile, Work work, String content) {
        var revision = uploadFile.getCurrentSettingBookRevision();
        var latest = latest(uploadFile);
        return new SettingBookResponse(
                uploadFile.getId(),
                work.getId(),
                uploadFile.getOriginalFilename(),
                resolveResponseMimeType(uploadFile),
                uploadFile.getFileSize(),
                content,
                uploadFile.getCreatedAt(), uploadFile.getBatch().getId(),
                revision == null ? null : revision.getId(), revision == null ? null : revision.getRevisionNo(),
                revision == null ? null : revision.getContentHash(), revision == null ? null : revision.getCharCount(),
                latest == null ? null : jobResponse(latest, uploadFile), latest == null ? null : latest.getStatus(), changed(uploadFile, latest),
                revision == null ? SettingBookReferenceTimeKind.UNSPECIFIED : revision.getReferenceTimeKind(),
                revision == null ? null : revision.getReferenceEpisodeNo()
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
        return DocumentFormat.fromFilename(originalFilename).mimeType();
    }

    private String resolveResponseMimeType(UploadFile uploadFile) {
        return StringUtils.hasText(uploadFile.getMimeType())
                ? uploadFile.getMimeType()
                : resolveMimeType(uploadFile.getOriginalFilename());
    }
}
