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
import org.monitoring.catchholebackend.domain.episode.parser.EpisodeFileParser;
import org.monitoring.catchholebackend.domain.episode.parser.ParsedEpisode;
import org.monitoring.catchholebackend.domain.episode.parser.ParsedEpisodeFile;
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
    private final EpisodeFileParser episodeFileParser;
    private final ObjectStorageService objectStorageService;

    public EpisodeUploadResponse upload(
            Work work,
            EpisodeUploadRequest request,
            List<MultipartFile> episodeFiles,
            MultipartFile settingBookFile
    ) {
        //TODO: 해당 코드의 경우 pending -> processing 단계로 넘어가는 코드가 하나도 존재하지 않음 로직 수정하기
        //TODO: 실패 처리 로직이 하나도 존재하지 않음 추가해야함 (실패로직의 경우 서로 상의하고 문서작업 pr 후 코드 리뷰 하기)
        List<ParsedEpisodeFile> parsedEpisodeFiles = episodeFileParser.parse(request, episodeFiles);
        validateEpisodeNumbers(work, parsedEpisodeFiles);

        UploadBatch batch = uploadBatchRepository.save(
                UploadBatch.create(work, work.getMember(), request.uploadType(), UploadSourceType.FILE)
        );
        batch.startProcessing();

        batch.updateFileCount(countFiles(episodeFiles, settingBookFile));

        List<Episode> savedEpisodes = new ArrayList<>();
        for (ParsedEpisodeFile parsedEpisodeFile : parsedEpisodeFiles) {
            saveEpisodeUploadFileAndEpisodes(batch, work, parsedEpisodeFile, savedEpisodes);
        }
        updateLatestEpisodeNo(work, savedEpisodes);

        if (isPresent(settingBookFile)) {
            saveSettingBookUploadFile(batch, settingBookFile);
        }

        batch.complete();
        List<UploadFile> uploadFiles = uploadFileRepository.findAllByBatchIdOrderByCreatedAtAsc(batch.getId());
        return new EpisodeUploadResponse(
                batch.getId(),
                batch.getUploadType(),
                batch.getStatus(),
                savedEpisodes.size(),
                uploadMapper.toFileResponseList(uploadFiles)
        );
    }

    private void validateEpisodeNumbers(Work work, List<ParsedEpisodeFile> parsedEpisodeFiles) {
        Set<Integer> uniqueEpisodeNosInRequest = new HashSet<>();
        for (ParsedEpisodeFile parsedEpisodeFile : parsedEpisodeFiles) {
            for (ParsedEpisode parsedEpisode : parsedEpisodeFile.episodes()) {
                if (!uniqueEpisodeNosInRequest.add(parsedEpisode.episodeNo())
                        || episodeRepository.existsByWorkIdAndEpisodeNo(work.getId(), parsedEpisode.episodeNo())) {
                    //TODO: 에러메세지에 중복 회차가 몇회차인지 추가로 작성해놓기
                    throw new AppException(EpisodeErrorCode.EPISODE_DUPLICATED , parsedEpisode.episodeNo() + " 화가 중복입니다.");
                }
            }
        }
    }

    private void saveEpisodeUploadFileAndEpisodes(
            UploadBatch batch,
            Work work,
            ParsedEpisodeFile parsedEpisodeFile,
            List<Episode> savedEpisodes
    ) {
        StoredObject storedEpisodeFile = objectStorageService.putUploadFile(
                batch.getId(),
                resolveOriginalFilename(parsedEpisodeFile.episodeFile()),
                readBytes(parsedEpisodeFile.episodeFile()),
                parsedEpisodeFile.episodeFile().getContentType()
        );

        UploadFile savedEpisodeFile = uploadFileRepository.save(createUploadFile(
                batch,
                UploadFileRole.EPISODE,
                parsedEpisodeFile.episodeFile(),
                storedEpisodeFile.key()
        ));
        savedEpisodeFile.markParsed(
                parsedEpisodeFile.detectedEpisodeStartNo(),
                parsedEpisodeFile.detectedEpisodeEndNo(),
                parsedEpisodeFile.detectedEpisodeCount()
        );

        for (ParsedEpisode parsedEpisode : parsedEpisodeFile.episodes()) {
            savedEpisodes.add(episodeRepository.save(storeEpisodeContentAndCreateEpisode(
                    work,
                    savedEpisodeFile,
                    parsedEpisode
            )));
        }
    }

    private void saveSettingBookUploadFile(UploadBatch batch, MultipartFile settingBookFile) {
        StoredObject storedSettingBookFile = objectStorageService.putUploadFile(
                batch.getId(),
                resolveOriginalFilename(settingBookFile),
                readBytes(settingBookFile),
                settingBookFile.getContentType()
        );
        UploadFile savedSettingBookFile = uploadFileRepository.save(createUploadFile(
                batch,
                UploadFileRole.SETTING_BOOK,
                settingBookFile,
                storedSettingBookFile.key()
        ));
        savedSettingBookFile.markParsed(null, null, null);
    }

    private Episode storeEpisodeContentAndCreateEpisode(
            Work work,
            UploadFile savedEpisodeFile,
            ParsedEpisode parsedEpisode
    ) {
        StoredTextObject storedEpisodeContent = objectStorageService.putEpisodeContent(
                work.getId(),
                parsedEpisode.episodeNo(),
                parsedEpisode.content()
        );
        return episodeMapper.toEntity(work, savedEpisodeFile, parsedEpisode, storedEpisodeContent);
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
