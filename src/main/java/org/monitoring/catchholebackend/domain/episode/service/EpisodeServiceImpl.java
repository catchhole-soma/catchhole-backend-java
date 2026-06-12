package org.monitoring.catchholebackend.domain.episode.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUpdateRequest;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadRequest;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeSummaryResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeUploadResponse;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.exception.EpisodeErrorCode;
import org.monitoring.catchholebackend.domain.episode.mapper.EpisodeMapper;
import org.monitoring.catchholebackend.domain.episode.parser.EpisodeUploadParser;
import org.monitoring.catchholebackend.domain.episode.parser.ParsedEpisode;
import org.monitoring.catchholebackend.domain.episode.parser.ParsedUploadFile;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.exception.UploadErrorCode;
import org.monitoring.catchholebackend.domain.upload.mapper.UploadMapper;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.exception.WorkErrorCode;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.ObjectStorage;
import org.monitoring.catchholebackend.global.storage.StoredObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EpisodeServiceImpl implements EpisodeService {

    private final EpisodeRepository episodeRepository;
    private final WorkRepository workRepository;
    private final UploadBatchRepository uploadBatchRepository;
    private final UploadFileRepository uploadFileRepository;
    private final EpisodeMapper episodeMapper;
    private final UploadMapper uploadMapper;
    private final EpisodeUploadParser episodeUploadParser;
    private final ObjectStorage objectStorage;

    @Override
    public List<EpisodeSummaryResponse> getEpisodes(Long memberId, UUID workId) {
        Work work = getOwnedWork(workId, memberId);
        List<Episode> episodes = episodeRepository.findAllByWorkIdOrderByEpisodeNoDesc(work.getId());
        return episodeMapper.toSummaryResponseList(episodes);
    }

    @Override
    public EpisodeResponse getEpisode(Long memberId, UUID workId, UUID episodeId) {
        Work work = getOwnedWork(workId, memberId);
        Episode episode = episodeRepository.findByIdAndWorkId(episodeId, work.getId())
                .orElseThrow(() -> new AppException(EpisodeErrorCode.EPISODE_NOT_FOUND));
        return episodeMapper.toResponse(episode, objectStorage.getText(episode.getContentS3Key()));
    }

    @Override
    @Transactional
    public EpisodeResponse updateEpisode(
            Long memberId,
            UUID workId,
            UUID episodeId,
            EpisodeUpdateRequest request
    ) {
        Work work = getOwnedWork(workId, memberId);
        Episode episode = getEpisodeInWork(episodeId, work);
        if (episode.getEpisodeNo() != request.episodeNo()
                && episodeRepository.existsByWorkIdAndEpisodeNo(work.getId(), request.episodeNo())) {
            throw new AppException(EpisodeErrorCode.EPISODE_DUPLICATED);
        }

        String oldContentS3Key = episode.getContentS3Key();
        String newContentS3Key = buildContentS3Key(work.getId(), request.episodeNo());
        StoredObject storedObject = objectStorage.putText(newContentS3Key, request.content());
        if (!oldContentS3Key.equals(newContentS3Key)) {
            objectStorage.delete(oldContentS3Key);
        }

        episode.updateContent(
                request.episodeNo(),
                request.title(),
                storedObject.key(),
                storedObject.versionId(),
                sha256(request.content()),
                request.content().length()
        );
        refreshLatestEpisodeNo(work);
        return episodeMapper.toResponse(episode, request.content());
    }

    @Override
    @Transactional
    public void deleteEpisode(Long memberId, UUID workId, UUID episodeId) {
        Work work = getOwnedWork(workId, memberId);
        Episode episode = getEpisodeInWork(episodeId, work);
        objectStorage.delete(episode.getContentS3Key());
        episodeRepository.delete(episode);
        episodeRepository.flush();
        refreshLatestEpisodeNo(work);
    }

    @Override
    @Transactional
    public EpisodeUploadResponse uploadEpisodes(
            Long memberId,
            UUID workId,
            EpisodeUploadRequest request,
            List<MultipartFile> episodeFiles,
            MultipartFile settingBookFile
    ) {
        List<ParsedUploadFile> parsedUploadFiles = episodeUploadParser.parse(request, episodeFiles);

        // URL 조작으로 다른 사용자의 작품에 회차를 업로드하지 못하도록 소유 작품을 먼저 확인한다.
        Work work = getOwnedWork(workId, memberId);
        validateEpisodeNumbers(work, parsedUploadFiles);

        // 업로드 요청 1회를 batch로 남겨 회차 파일과 설정집 파일을 같은 작업 묶음으로 추적한다.
        UploadBatch batch = uploadBatchRepository.save(
                UploadBatch.create(work, work.getMember(), request.uploadType(), UploadSourceType.FILE)
        );
        batch.startProcessing();
        batch.updateFileCount(countFiles(episodeFiles, settingBookFile));

        List<Episode> episodes = new ArrayList<>();
        for (ParsedUploadFile parsedUploadFile : parsedUploadFiles) {
            saveParsedUploadFile(batch, work, parsedUploadFile, episodes);
        }

        int latestEpisodeNo = episodes.stream()
                .mapToInt(Episode::getEpisodeNo)
                .max()
                .orElse(work.getLatestEpisodeNo());
        work.updateLatestEpisodeNo(Math.max(work.getLatestEpisodeNo(), latestEpisodeNo));

        if (isPresent(settingBookFile)) {
            // 설정집은 이번 범위에서 분석하지 않고, 함께 업로드된 파일 메타데이터까지만 저장한다.
            StoredObject storedSettingBookFile = objectStorage.putBytes(
                    buildUploadFileS3Key(batch.getId(), resolveOriginalFilename(settingBookFile)),
                    readBytes(settingBookFile),
                    settingBookFile.getContentType()
            );
            UploadFile uploadedSettingBookFile = uploadFileRepository.save(createUploadFile(
                    batch,
                    UploadFileRole.SETTING_BOOK,
                    settingBookFile,
                    storedSettingBookFile.key()
            ));
            uploadedSettingBookFile.markParsed(null, null, null);
        }

        batch.complete();
        List<UploadFile> uploadFiles = uploadFileRepository.findAllByBatchIdOrderByCreatedAtAsc(batch.getId());
        return new EpisodeUploadResponse(
                batch.getId(),
                batch.getUploadType(),
                batch.getStatus(),
                episodes.size(),
                uploadMapper.toFileResponseList(uploadFiles)
        );
    }

    private Work getOwnedWork(UUID workId, Long memberId) {
        return workRepository.findByIdAndMemberId(workId, memberId)
                .orElseThrow(() -> new AppException(WorkErrorCode.WORK_NOT_FOUND));
    }

    private Episode getEpisodeInWork(UUID episodeId, Work work) {
        return episodeRepository.findByIdAndWorkId(episodeId, work.getId())
                .orElseThrow(() -> new AppException(EpisodeErrorCode.EPISODE_NOT_FOUND));
    }

    private int countFiles(List<MultipartFile> episodeFiles, MultipartFile settingBookFile) {
        return episodeFiles.size() + (isPresent(settingBookFile) ? 1 : 0);
    }

    private void validateEpisodeNumbers(Work work, List<ParsedUploadFile> parsedUploadFiles) {
        Set<Integer> episodeNos = new HashSet<>();
        for (ParsedUploadFile parsedUploadFile : parsedUploadFiles) {
            for (ParsedEpisode parsedEpisode : parsedUploadFile.episodes()) {
                if (!episodeNos.add(parsedEpisode.episodeNo())
                        || episodeRepository.existsByWorkIdAndEpisodeNo(work.getId(), parsedEpisode.episodeNo())) {
                    throw new AppException(EpisodeErrorCode.EPISODE_DUPLICATED);
                }
            }
        }
    }

    private void saveParsedUploadFile(
            UploadBatch batch,
            Work work,
            ParsedUploadFile parsedUploadFile,
            List<Episode> episodes
    ) {
        StoredObject storedUploadFile = objectStorage.putBytes(
                buildUploadFileS3Key(batch.getId(), resolveOriginalFilename(parsedUploadFile.file())),
                readBytes(parsedUploadFile.file()),
                parsedUploadFile.file().getContentType()
        );

        // 업로드된 원본 파일 자체의 메타데이터를 저장한다. 원본 파일도 S3에 함께 저장한다.
        UploadFile uploadedEpisodeFile = uploadFileRepository.save(createUploadFile(
                batch,
                UploadFileRole.EPISODE,
                parsedUploadFile.file(),
                storedUploadFile.key()
        ));
        uploadedEpisodeFile.markParsed(
                parsedUploadFile.detectedEpisodeStartNo(),
                parsedUploadFile.detectedEpisodeEndNo(),
                parsedUploadFile.detectedEpisodeCount()
        );

        for (ParsedEpisode parsedEpisode : parsedUploadFile.episodes()) {
            episodes.add(episodeRepository.save(createEpisode(work, uploadedEpisodeFile, parsedEpisode)));
        }
    }

    private Episode createEpisode(Work work, UploadFile uploadedEpisodeFile, ParsedEpisode parsedEpisode) {
        StoredObject storedObject = objectStorage.putText(
                buildContentS3Key(work.getId(), parsedEpisode.episodeNo()),
                parsedEpisode.content()
        );
        Episode episode = Episode.create(
                work,
                uploadedEpisodeFile.getId(),
                parsedEpisode.episodeNo(),
                parsedEpisode.title(),
                storedObject.key(),
                storedObject.versionId(),
                sha256(parsedEpisode.content()),
                parsedEpisode.content().length()
        );
        return episode;
    }

    private UploadFile createUploadFile(
            UploadBatch batch,
            UploadFileRole fileRole,
            MultipartFile file,
            String storageKey
    ) {
        return UploadFile.create(
                batch,
                fileRole,
                resolveOriginalFilename(file),
                file.getContentType(),
                buildStorageUrl(storageKey),
                file.getSize()
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

    private String buildStorageUrl(String storageKey) {
        return "s3://" + storageKey;
    }

    private String buildUploadFileS3Key(UUID batchId, String originalFilename) {
        return "upload-batches/" + batchId + "/" + UUID.randomUUID() + "-" + originalFilename;
    }

    private String buildContentS3Key(UUID workId, int episodeNo) {
        return "works/" + workId + "/episodes/" + episodeNo + ".txt";
    }

    private boolean isPresent(MultipartFile file) {
        return file != null && !file.isEmpty();
    }

    private void refreshLatestEpisodeNo(Work work) {
        int latestEpisodeNo = episodeRepository.findFirstByWorkIdOrderByEpisodeNoDesc(work.getId())
                .map(Episode::getEpisodeNo)
                .orElse(0);
        work.updateLatestEpisodeNo(latestEpisodeNo);
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available.", exception);
        }
    }
}
