package org.monitoring.catchholebackend.domain.episode.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadRequest;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeSummaryResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeUploadResponse;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.exception.EpisodeErrorCode;
import org.monitoring.catchholebackend.domain.episode.mapper.EpisodeMapper;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFileRole;
import org.monitoring.catchholebackend.domain.upload.entity.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.entity.UploadType;
import org.monitoring.catchholebackend.domain.upload.exception.UploadErrorCode;
import org.monitoring.catchholebackend.domain.upload.mapper.UploadMapper;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.exception.WorkErrorCode;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
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
        return episodeMapper.toResponse(episode);
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
        validateSingleEpisodeUpload(request, episodeFiles);

        // URL 조작으로 다른 사용자의 작품에 회차를 업로드하지 못하도록 소유 작품을 먼저 확인한다.
        Work work = getOwnedWork(workId, memberId);
        int episodeNo = request.episodeNo();
        if (episodeRepository.existsByWorkIdAndEpisodeNo(work.getId(), episodeNo)) {
            throw new AppException(EpisodeErrorCode.EPISODE_DUPLICATED);
        }

        // 업로드 요청 1회를 batch로 남겨 회차 파일과 설정집 파일을 같은 작업 묶음으로 추적한다.
        UploadBatch batch = uploadBatchRepository.save(
                UploadBatch.create(work, work.getMember(), request.uploadType(), UploadSourceType.FILE)
        );
        batch.startProcessing();
        batch.updateFileCount(countFiles(episodeFiles, settingBookFile));

        MultipartFile episodeFile = episodeFiles.get(0);
        // S3 연동 전 임시 흐름이다. 원문은 해시와 글자 수 계산에만 사용하고, 아직 영속 저장하지 않는다.
        String originalText = readText(episodeFile);
        String title = resolveTitle(request.title(), episodeFile.getOriginalFilename());
        String contentHash = sha256(originalText);

        // 업로드된 원본 파일 자체의 메타데이터를 저장한다. 실제 파일 저장 위치는 S3 연동 시 교체한다.
        UploadFile uploadedEpisodeFile = uploadFileRepository.save(createUploadFile(
                batch,
                UploadFileRole.EPISODE,
                episodeFile
        ));
        uploadedEpisodeFile.markParsed(episodeNo, episodeNo, 1);

        // 회차 도메인 데이터는 UploadFile과 분리해서 저장한다. sourceFileId로 원본 파일 추적만 연결한다.
        Episode episode = Episode.create(
                work,
                uploadedEpisodeFile.getId(),
                episodeNo,
                title,
                buildContentS3Key(work.getId(), episodeNo),
                null,
                contentHash,
                originalText.length()
        );
        episode.markParsed();
        episodeRepository.save(episode);
        work.updateLatestEpisodeNo(Math.max(work.getLatestEpisodeNo(), episodeNo));

        if (isPresent(settingBookFile)) {
            // 설정집은 이번 범위에서 분석하지 않고, 함께 업로드된 파일 메타데이터까지만 저장한다.
            UploadFile uploadedSettingBookFile = uploadFileRepository.save(createUploadFile(
                    batch,
                    UploadFileRole.SETTING_BOOK,
                    settingBookFile
            ));
            uploadedSettingBookFile.markParsed(null, null, null);
        }

        batch.complete();
        List<UploadFile> uploadFiles = uploadFileRepository.findAllByBatchIdOrderByCreatedAtAsc(batch.getId());
        return new EpisodeUploadResponse(
                batch.getId(),
                batch.getUploadType(),
                batch.getStatus(),
                1,
                uploadMapper.toFileResponseList(uploadFiles)
        );
    }

    private Work getOwnedWork(UUID workId, Long memberId) {
        return workRepository.findByIdAndMemberId(workId, memberId)
                .orElseThrow(() -> new AppException(WorkErrorCode.WORK_NOT_FOUND));
    }

    private void validateSingleEpisodeUpload(EpisodeUploadRequest request, List<MultipartFile> episodeFiles) {
        if (request.uploadType() != UploadType.SINGLE_EPISODE) {
            throw new AppException(UploadErrorCode.UPLOAD_TYPE_NOT_SUPPORTED);
        }
        if (request.episodeNo() == null) {
            throw new AppException(UploadErrorCode.UPLOAD_EPISODE_NO_REQUIRED);
        }
        if (episodeFiles == null || episodeFiles.size() != 1) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_REQUIRED);
        }
        if (episodeFiles.get(0).isEmpty()) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_EMPTY);
        }
    }

    private int countFiles(List<MultipartFile> episodeFiles, MultipartFile settingBookFile) {
        return episodeFiles.size() + (isPresent(settingBookFile) ? 1 : 0);
    }

    private UploadFile createUploadFile(UploadBatch batch, UploadFileRole fileRole, MultipartFile file) {
        return UploadFile.create(
                batch,
                fileRole,
                resolveOriginalFilename(file),
                file.getContentType(),
                buildStorageUrl(batch.getId(), resolveOriginalFilename(file)),
                file.getSize()
        );
    }

    private String readText(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_READ_FAILED, exception);
        }
    }

    private String resolveTitle(String requestedTitle, String originalFilename) {
        if (StringUtils.hasText(requestedTitle)) {
            return requestedTitle;
        }
        String filename = StringUtils.hasText(originalFilename) ? originalFilename : "untitled";
        int extensionIndex = filename.lastIndexOf('.');
        return extensionIndex > 0 ? filename.substring(0, extensionIndex) : filename;
    }

    private String resolveOriginalFilename(MultipartFile file) {
        return StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "untitled.txt";
    }

    private String buildStorageUrl(UUID batchId, String originalFilename) {
        return "pending://upload-batches/" + batchId + "/" + originalFilename;
    }

    private String buildContentS3Key(UUID workId, int episodeNo) {
        return "works/" + workId + "/episodes/" + episodeNo + ".txt";
    }

    private boolean isPresent(MultipartFile file) {
        return file != null && !file.isEmpty();
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
