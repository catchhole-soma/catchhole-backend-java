package org.monitoring.catchholebackend.domain.episode.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadRequest;
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
import org.monitoring.catchholebackend.domain.upload.exception.UploadErrorCode;
import org.monitoring.catchholebackend.domain.upload.mapper.UploadMapper;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
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
    private final EpisodeUploadParser episodeUploadParser;
    private final ObjectStorageService objectStorageService;

    public EpisodeUploadResponse upload(
            Work work,
            EpisodeUploadRequest request,
            List<MultipartFile> episodeFiles,
            MultipartFile settingBookFile
    ) {
        List<ParsedUploadFile> parsedUploadFiles = episodeUploadParser.parse(request, episodeFiles);
        validateEpisodeNumbers(work, parsedUploadFiles);

        UploadBatch batch = uploadBatchRepository.save(
                UploadBatch.create(work, work.getMember(), request.uploadType(), UploadSourceType.FILE)
        );
        batch.startProcessing();
        batch.updateFileCount(countFiles(episodeFiles, settingBookFile));

        List<Episode> episodes = new ArrayList<>();
        for (ParsedUploadFile parsedUploadFile : parsedUploadFiles) {
            saveParsedUploadFile(batch, work, parsedUploadFile, episodes);
        }
        updateLatestEpisodeNo(work, episodes);

        if (isPresent(settingBookFile)) {
            saveSettingBookFile(batch, settingBookFile);
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
        StoredObject storedUploadFile = objectStorageService.putUploadFile(
                batch.getId(),
                resolveOriginalFilename(parsedUploadFile.file()),
                readBytes(parsedUploadFile.file()),
                parsedUploadFile.file().getContentType()
        );

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

    private void saveSettingBookFile(UploadBatch batch, MultipartFile settingBookFile) {
        StoredObject storedSettingBookFile = objectStorageService.putUploadFile(
                batch.getId(),
                resolveOriginalFilename(settingBookFile),
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

    private Episode createEpisode(Work work, UploadFile uploadedEpisodeFile, ParsedEpisode parsedEpisode) {
        StoredTextObject storedContent = objectStorageService.putEpisodeContent(
                work.getId(),
                parsedEpisode.episodeNo(),
                parsedEpisode.content()
        );
        return episodeMapper.toEntity(work, uploadedEpisodeFile, parsedEpisode, storedContent);
    }

    private UploadFile createUploadFile(
            UploadBatch batch,
            UploadFileRole fileRole,
            MultipartFile file,
            String storageKey
    ) {
        return uploadMapper.toEntity(
                batch,
                fileRole,
                resolveOriginalFilename(file),
                file.getContentType(),
                objectStorageService.toStorageUrl(storageKey),
                file.getSize()
        );
    }

    private void updateLatestEpisodeNo(Work work, List<Episode> episodes) {
        int latestEpisodeNo = episodes.stream()
                .mapToInt(Episode::getEpisodeNo)
                .max()
                .orElse(work.getLatestEpisodeNo());
        work.updateLatestEpisodeNo(Math.max(work.getLatestEpisodeNo(), latestEpisodeNo));
    }

    private int countFiles(List<MultipartFile> episodeFiles, MultipartFile settingBookFile) {
        return episodeFiles.size() + (isPresent(settingBookFile) ? 1 : 0);
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

    private boolean isPresent(MultipartFile file) {
        return file != null && !file.isEmpty();
    }
}
