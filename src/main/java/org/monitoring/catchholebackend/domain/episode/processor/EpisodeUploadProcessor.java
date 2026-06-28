package org.monitoring.catchholebackend.domain.episode.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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

    /**
     * 회차 업로드 요청 하나를 batch 단위로 처리한다.
     * 업로드 타입에 맞게 파일을 파싱하고, 회차 번호 중복을 검증한 뒤 UploadBatch와 UploadFile 추적 정보를 만든다.
     * 파싱된 회차 본문은 S3에 저장하고 Episode에는 저장소 key/version/hash/글자 수 메타데이터만 남긴다.
     */
    public EpisodeUploadResponse upload(
            Work work,
            EpisodeUploadRequest request,
            List<MultipartFile> episodeFiles,
            MultipartFile settingBookFile
    ) {
        // TODO: 현재 동기 업로드는 예외 시 트랜잭션 rollback으로 batch도 함께 사라질 수 있다. 모니터링 이력 기록이 필요하면 batch 선커밋/별도 트랜잭션/비동기 처리 중 어떤 방식이 맞을지 후속으로 검토한다.
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

    /**
     * 업로드 요청 안의 회차 번호 중복과 같은 작품에 이미 존재하는 회차 번호를 함께 검증한다.
     */
    private void validateEpisodeNumbers(Work work, List<ParsedEpisodeFile> parsedEpisodeFiles) {
        Set<Integer> uniqueEpisodeNosInRequest = new HashSet<>();
        Set<Integer> duplicatedEpisodeNosInRequest = new TreeSet<>();
        Set<Integer> duplicatedEpisodeNosInWork = new TreeSet<>();

        for (ParsedEpisodeFile parsedEpisodeFile : parsedEpisodeFiles) {
            for (ParsedEpisode parsedEpisode : parsedEpisodeFile.episodes()) {
                int episodeNo = parsedEpisode.episodeNo();
                if (!uniqueEpisodeNosInRequest.add(episodeNo)) {
                    duplicatedEpisodeNosInRequest.add(episodeNo);
                }
                if (episodeRepository.existsByWorkIdAndEpisodeNo(work.getId(), episodeNo)) {
                    duplicatedEpisodeNosInWork.add(episodeNo);
                }
            }
        }

        if (!duplicatedEpisodeNosInRequest.isEmpty() || !duplicatedEpisodeNosInWork.isEmpty()) {
            throw new AppException(
                    EpisodeErrorCode.EPISODE_UPLOAD_DUPLICATED,
                    buildDuplicateEpisodeMessage(duplicatedEpisodeNosInRequest, duplicatedEpisodeNosInWork)
            );
        }
    }

    private String buildDuplicateEpisodeMessage(
            Set<Integer> duplicatedEpisodeNosInRequest,
            Set<Integer> duplicatedEpisodeNosInWork
    ) {
        List<String> messages = new ArrayList<>();
        if (!duplicatedEpisodeNosInRequest.isEmpty()) {
            messages.add("업로드 파일 안에서 중복된 회차: " + formatEpisodeNos(duplicatedEpisodeNosInRequest) + ".");
        }
        if (!duplicatedEpisodeNosInWork.isEmpty()) {
            messages.add("이미 등록된 회차와 중복된 회차: " + formatEpisodeNos(duplicatedEpisodeNosInWork) + ".");
        }
        return String.join(" ", messages);
    }

    private String formatEpisodeNos(Set<Integer> episodeNos) {
        return String.join(", ", episodeNos.stream()
                .map(episodeNo -> episodeNo + "화")
                .toList());
    }

    /**
     * 파싱된 원본 회차 파일 하나를 S3에 저장하고 UploadFile 추적 정보를 PARSED 상태로 갱신한다.
     * 파일 안에서 분리된 각 회차는 별도 S3 원문과 Episode로 저장한다.
     */
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

    /**
     * 설정집 파일을 업로드 batch에 포함된 보조 파일로 저장하고 UploadFile로 추적한다.
     * 설정집은 회차 범위가 없으므로 감지된 시작/끝 회차와 회차 개수는 비워둔다.
     */
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

    /**
     * 파싱된 회차 본문을 S3에 먼저 저장한 뒤, 저장소 메타데이터를 포함한 Episode 엔티티를 조립한다.
     */
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
