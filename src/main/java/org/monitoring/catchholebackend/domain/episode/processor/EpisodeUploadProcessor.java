package org.monitoring.catchholebackend.domain.episode.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadConfirmationRequest;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadRequest;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeUploadResponse;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.exception.EpisodeErrorCode;
import org.monitoring.catchholebackend.domain.episode.mapper.EpisodeMapper;
import org.monitoring.catchholebackend.domain.episode.parser.DetectedEpisode;
import org.monitoring.catchholebackend.domain.episode.parser.DetectedEpisodeFile;
import org.monitoring.catchholebackend.domain.episode.parser.EpisodeFileParser;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeUploadType;
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
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.monitoring.catchholebackend.global.storage.StoredObject;
import org.monitoring.catchholebackend.global.storage.StoredTextObject;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Component
@RequiredArgsConstructor
public class EpisodeUploadProcessor {

    private final EpisodeRepository episodeRepository;
    private final UploadBatchRepository uploadBatchRepository;
    private final UploadFileRepository uploadFileRepository;
    private final EpisodeMapper episodeMapper;
    private final UploadMapper uploadMapper;
    private final EpisodeFileParser episodeFileParser;
    private final TextDocumentReader textDocumentReader;
    private final ObjectStorageService objectStorageService;

    /**
     * 회차 업로드 요청 하나를 batch 단위로 처리한다.
     * 업로드 타입에 맞게 파일을 파싱하고, 회차 번호 중복을 검증한 뒤 UploadBatch와 UploadFile 추적 정보를 만든다.
     * 파싱된 회차 본문은 S3에 저장하고 Episode에는 저장소 key/version/hash/글자 수 메타데이터만 남긴다.
     */
    public EpisodeUploadResponse processEpisodeUpload(
            Work work,
            EpisodeUploadRequest uploadRequest,
            List<MultipartFile> sourceEpisodeFiles,
            MultipartFile attachedSettingBookFile
    ) {
        // TODO: 현재 동기 업로드는 예외 시 트랜잭션 rollback으로 batch도 함께 사라질 수 있다. 모니터링 이력 기록이 필요하면 batch 선커밋/별도 트랜잭션/비동기 처리 중 어떤 방식이 맞을지 후속으로 검토한다.
        validateUploadRequest(uploadRequest);
        List<DetectedEpisodeFile> detectedEpisodeFiles = episodeFileParser.parseEpisodeFiles(
                uploadRequest.uploadType(),
                uploadRequest.singleEpisodeNo(),
                uploadRequest.singleEpisodeTitle(),
                sourceEpisodeFiles
        );
        List<FinalizedEpisodeFile> finalizedEpisodeFiles = finalizeEpisodeFiles(
                uploadRequest.uploadType(),
                detectedEpisodeFiles,
                uploadRequest.episodeConfirmations()
        );
        validateEpisodeNumberAvailability(work, finalizedEpisodeFiles);
        if (isAttached(attachedSettingBookFile)) {
            textDocumentReader.readText(attachedSettingBookFile);
            validateSettingBookFilenameAvailable(work, attachedSettingBookFile);
        }

        UploadBatch uploadBatch = uploadBatchRepository.save(
                UploadBatch.create(
                        work,
                        work.getMember(),
                        toBatchUploadType(uploadRequest.uploadType()),
                        UploadSourceType.FILE
                )
        );
        uploadBatch.startProcessing();

        uploadBatch.updateFileCount(countSourceFiles(sourceEpisodeFiles, attachedSettingBookFile));

        List<Episode> savedEpisodes = new ArrayList<>();
        for (FinalizedEpisodeFile finalizedEpisodeFile : finalizedEpisodeFiles) {
            saveEpisodeSourceFileAndEpisodes(uploadBatch, work, finalizedEpisodeFile, savedEpisodes);
        }
        updateLatestEpisodeNo(work, savedEpisodes);

        if (isAttached(attachedSettingBookFile)) {
            saveAttachedSettingBookFile(uploadBatch, attachedSettingBookFile);
        }

        uploadBatch.complete();
        List<UploadFile> savedUploadFiles =
                uploadFileRepository.findAllByBatchIdOrderByCreatedAtAsc(uploadBatch.getId());
        return new EpisodeUploadResponse(
                uploadBatch.getId(),
                uploadRequest.uploadType(),
                uploadBatch.getStatus(),
                savedEpisodes.size(),
                episodeMapper.toSummaryResponseList(savedEpisodes),
                uploadMapper.toFileResponseList(savedUploadFiles)
        );
    }

    private void validateUploadRequest(EpisodeUploadRequest uploadRequest) {
        List<EpisodeUploadConfirmationRequest> episodeConfirmations = uploadRequest.episodeConfirmations();
        switch (uploadRequest.uploadType()) {
            case SINGLE_EPISODE -> {
                if (uploadRequest.singleEpisodeNo() == null) {
                    throw new AppException(UploadErrorCode.UPLOAD_EPISODE_NO_REQUIRED);
                }
                if (episodeConfirmations != null) {
                    throw new AppException(UploadErrorCode.UPLOAD_EPISODE_CONFIRMATION_NOT_ALLOWED);
                }
            }
            case MULTI_EPISODE_SINGLE_FILE, MULTI_EPISODE_MULTI_FILE -> {
                if (episodeConfirmations == null || episodeConfirmations.isEmpty()) {
                    throw new AppException(UploadErrorCode.UPLOAD_EPISODE_CONFIRMATION_REQUIRED);
                }
            }
        }
    }

    private List<FinalizedEpisodeFile> finalizeEpisodeFiles(
            EpisodeUploadType uploadType,
            List<DetectedEpisodeFile> detectedEpisodeFiles,
            List<EpisodeUploadConfirmationRequest> episodeConfirmations
    ) {
        if (uploadType == EpisodeUploadType.SINGLE_EPISODE) {
            return detectedEpisodeFiles.stream()
                    .map(detectedEpisodeFile -> new FinalizedEpisodeFile(
                            detectedEpisodeFile.sourceFile(),
                            detectedEpisodeFile.detectedEpisodes().stream()
                                    .map(this::toFinalizedEpisode)
                                    .toList()
                    ))
                    .toList();
        }
        return applyEpisodeConfirmations(detectedEpisodeFiles, episodeConfirmations);
    }

    private FinalizedEpisode toFinalizedEpisode(DetectedEpisode detectedEpisode) {
        return new FinalizedEpisode(
                detectedEpisode.episodeNo(),
                detectedEpisode.title(),
                detectedEpisode.content()
        );
    }

    private List<FinalizedEpisodeFile> applyEpisodeConfirmations(
            List<DetectedEpisodeFile> detectedEpisodeFiles,
            List<EpisodeUploadConfirmationRequest> episodeConfirmations
    ) {
        int detectedEpisodeCount = detectedEpisodeFiles.stream()
                .mapToInt(DetectedEpisodeFile::episodeCount)
                .sum();
        if (episodeConfirmations.size() != detectedEpisodeCount) {
            throw new AppException(UploadErrorCode.UPLOAD_EPISODE_CONFIRMATION_INVALID);
        }

        int detectionOrder = 0;
        int previousEpisodeNo = 0;
        List<FinalizedEpisodeFile> finalizedEpisodeFiles = new ArrayList<>();
        for (DetectedEpisodeFile detectedEpisodeFile : detectedEpisodeFiles) {
            List<FinalizedEpisode> finalizedEpisodes = new ArrayList<>();
            for (DetectedEpisode detectedEpisode : detectedEpisodeFile.detectedEpisodes()) {
                EpisodeUploadConfirmationRequest confirmation = episodeConfirmations.get(detectionOrder);
                if (confirmation.detectionOrder() != detectionOrder
                        || confirmation.episodeNo() <= previousEpisodeNo) {
                    throw new AppException(UploadErrorCode.UPLOAD_EPISODE_CONFIRMATION_INVALID);
                }
                finalizedEpisodes.add(new FinalizedEpisode(
                        confirmation.episodeNo(),
                        normalizeTitle(confirmation.title()),
                        detectedEpisode.content()
                ));
                previousEpisodeNo = confirmation.episodeNo();
                detectionOrder++;
            }
            finalizedEpisodeFiles.add(new FinalizedEpisodeFile(
                    detectedEpisodeFile.sourceFile(),
                    finalizedEpisodes
            ));
        }
        return finalizedEpisodeFiles;
    }

    private void validateSettingBookFilenameAvailable(
            Work work,
            MultipartFile attachedSettingBookFile
    ) {
        if (uploadFileRepository.existsByBatchWorkIdAndFileRoleAndOriginalFilenameAndArchivedAtIsNull(
                work.getId(),
                UploadFileRole.SETTING_BOOK,
                resolveOriginalFilename(attachedSettingBookFile)
        )) {
            throw new AppException(UploadErrorCode.UPLOAD_SETTING_BOOK_DUPLICATED);
        }
    }

    /**
     * 업로드 요청 안의 회차 번호 중복과 같은 작품에 이미 존재하는 회차 번호를 함께 검증한다.
     */
    private void validateEpisodeNumberAvailability(
            Work work,
            List<FinalizedEpisodeFile> finalizedEpisodeFiles
    ) {
        Set<Integer> seenEpisodeNos = new HashSet<>();
        Set<Integer> duplicateEpisodeNosInRequest = new TreeSet<>();
        Set<Integer> existingEpisodeNosInWork = new TreeSet<>();

        for (FinalizedEpisodeFile finalizedEpisodeFile : finalizedEpisodeFiles) {
            for (FinalizedEpisode finalizedEpisode : finalizedEpisodeFile.finalizedEpisodes()) {
                int episodeNo = finalizedEpisode.episodeNo();
                if (!seenEpisodeNos.add(episodeNo)) {
                    duplicateEpisodeNosInRequest.add(episodeNo);
                }
                if (episodeRepository.existsByWorkIdAndEpisodeNoAndStatusNot(
                        work.getId(), episodeNo, EpisodeStatus.ARCHIVED)) {
                    existingEpisodeNosInWork.add(episodeNo);
                }
            }
        }

        if (!duplicateEpisodeNosInRequest.isEmpty() || !existingEpisodeNosInWork.isEmpty()) {
            throw new AppException(
                    EpisodeErrorCode.EPISODE_UPLOAD_DUPLICATED,
                    buildDuplicateEpisodeNumberMessage(
                            duplicateEpisodeNosInRequest,
                            existingEpisodeNosInWork
                    )
            );
        }
    }

    private String buildDuplicateEpisodeNumberMessage(
            Set<Integer> duplicateEpisodeNosInRequest,
            Set<Integer> existingEpisodeNosInWork
    ) {
        List<String> messages = new ArrayList<>();
        if (!duplicateEpisodeNosInRequest.isEmpty()) {
            messages.add(
                    "업로드 파일 안에서 중복된 회차: "
                            + formatEpisodeNumbers(duplicateEpisodeNosInRequest)
                            + "."
            );
        }
        if (!existingEpisodeNosInWork.isEmpty()) {
            messages.add(
                    "이미 등록된 회차와 중복된 회차: "
                            + formatEpisodeNumbers(existingEpisodeNosInWork)
                            + "."
            );
        }
        return String.join(" ", messages);
    }

    private String formatEpisodeNumbers(Set<Integer> episodeNos) {
        return String.join(", ", episodeNos.stream()
                .map(episodeNo -> episodeNo + "화")
                .toList());
    }

    /**
     * 파싱된 원본 회차 파일 하나를 S3에 저장하고 UploadFile 추적 정보를 PARSED 상태로 갱신한다.
     * 파일 안에서 분리된 각 회차는 별도 S3 원문과 Episode로 저장한다.
     */
    private void saveEpisodeSourceFileAndEpisodes(
            UploadBatch uploadBatch,
            Work work,
            FinalizedEpisodeFile finalizedEpisodeFile,
            List<Episode> savedEpisodes
    ) {
        StoredObject storedSourceFile = objectStorageService.putUploadFile(
                uploadBatch.getId(),
                resolveOriginalFilename(finalizedEpisodeFile.sourceFile()),
                readBytes(finalizedEpisodeFile.sourceFile()),
                finalizedEpisodeFile.sourceFile().getContentType()
        );

        UploadFile savedSourceFile = uploadFileRepository.save(buildUploadFile(
                uploadBatch,
                UploadFileRole.EPISODE,
                finalizedEpisodeFile.sourceFile(),
                storedSourceFile.key()
        ));
        savedSourceFile.markEpisodesParsed(
                finalizedEpisodeFile.episodeStartNo(),
                finalizedEpisodeFile.episodeEndNo(),
                finalizedEpisodeFile.episodeCount()
        );

        for (FinalizedEpisode finalizedEpisode : finalizedEpisodeFile.finalizedEpisodes()) {
            savedEpisodes.add(episodeRepository.save(createEpisodeWithStoredContent(
                    work,
                    savedSourceFile,
                    finalizedEpisode
            )));
        }
    }

    /**
     * 설정집 파일을 업로드 batch에 포함된 보조 파일로 저장하고 UploadFile로 추적한다.
     * 설정집은 회차 범위가 없으므로 감지된 시작/끝 회차와 회차 개수는 비워둔다.
     */
    private void saveAttachedSettingBookFile(
            UploadBatch uploadBatch,
            MultipartFile attachedSettingBookFile
    ) {
        StoredObject storedSettingBookFile = objectStorageService.putUploadFile(
                uploadBatch.getId(),
                resolveOriginalFilename(attachedSettingBookFile),
                readBytes(attachedSettingBookFile),
                attachedSettingBookFile.getContentType()
        );
        UploadFile savedSettingBookFile = uploadFileRepository.save(buildUploadFile(
                uploadBatch,
                UploadFileRole.SETTING_BOOK,
                attachedSettingBookFile,
                storedSettingBookFile.key()
        ));
        savedSettingBookFile.markParsed();
    }

    /**
     * 파싱된 회차 본문을 S3에 먼저 저장한 뒤, 저장소 메타데이터를 포함한 Episode 엔티티를 조립한다.
     */
    private Episode createEpisodeWithStoredContent(
            Work work,
            UploadFile savedSourceFile,
            FinalizedEpisode finalizedEpisode
    ) {
        StoredTextObject storedEpisodeContent = objectStorageService.putEpisodeContent(
                work.getId(),
                finalizedEpisode.episodeNo(),
                finalizedEpisode.content()
        );
        return episodeMapper.toEntity(work, savedSourceFile, finalizedEpisode, storedEpisodeContent);
    }

    private UploadFile buildUploadFile(
            UploadBatch uploadBatch,
            UploadFileRole fileRole,
            MultipartFile sourceFile,
            String storageKey
    ) {
        return uploadMapper.toEntity(
                uploadBatch,
                fileRole,
                resolveOriginalFilename(sourceFile),
                sourceFile.getContentType(),
                objectStorageService.toStorageUrl(storageKey),
                sourceFile.getSize()
        );
    }

    /**
     * 이번 업로드로 생성된 회차 번호 중 가장 큰 값을 작품의 최신 회차 번호에 반영한다.
     * 기존 최신 회차 번호보다 작은 회차만 추가된 경우에는 값을 낮추지 않는다.
     */
    private void updateLatestEpisodeNo(Work work, List<Episode> episodes) {
        int latestEpisodeNo = episodes.stream()
                .mapToInt(Episode::getEpisodeNo)
                .max()
                .orElse(work.getLatestEpisodeNo());
        work.updateLatestEpisodeNo(Math.max(work.getLatestEpisodeNo(), latestEpisodeNo));
    }

    private UploadType toBatchUploadType(EpisodeUploadType episodeUploadType) {
        return switch (episodeUploadType) {
            case SINGLE_EPISODE -> UploadType.SINGLE_EPISODE;
            case MULTI_EPISODE_SINGLE_FILE -> UploadType.MULTI_EPISODE_SINGLE_FILE;
            case MULTI_EPISODE_MULTI_FILE -> UploadType.MULTI_EPISODE_MULTI_FILE;
        };
    }

    private int countSourceFiles(
            List<MultipartFile> sourceEpisodeFiles,
            MultipartFile attachedSettingBookFile
    ) {
        return sourceEpisodeFiles.size() + (isAttached(attachedSettingBookFile) ? 1 : 0);
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

    private String normalizeTitle(String title) {
        return StringUtils.hasText(title) ? title.trim() : null;
    }

    private boolean isAttached(MultipartFile file) {
        return file != null;
    }
}
