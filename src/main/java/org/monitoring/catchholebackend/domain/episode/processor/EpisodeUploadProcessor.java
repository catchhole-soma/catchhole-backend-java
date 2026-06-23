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
        //TODO: 해당 코드의 경우 pending -> processing 단계로 넘어가는 코드가 하나도 존재하지 않음 로직 수정하기
        //TODO: 실패 처리 로직이 하나도 존재하지 않음 추가해야함 (실패로직의 경우 서로 상의하고 문서작업 pr 후 코드 리뷰 하기)
        //TODO: 현재 코드 구현 로직에서 변수네이밍이 이상함. 함수의 매개변수와 호출할때의 인자값의 네이밍이 달라서 코드 리뷰가 힘듦 (episode mapper 부분)
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

    /**
     * 업로드 요청 안의 회차 번호 중복과 같은 작품에 이미 존재하는 회차 번호를 함께 검증한다.
     */
    private void validateEpisodeNumbers(Work work, List<ParsedUploadFile> parsedUploadFiles) {
        Set<Integer> episodeNos = new HashSet<>();
        for (ParsedUploadFile parsedUploadFile : parsedUploadFiles) {
            for (ParsedEpisode parsedEpisode : parsedUploadFile.episodes()) {
                if (!episodeNos.add(parsedEpisode.episodeNo())
                        || episodeRepository.existsByWorkIdAndEpisodeNo(work.getId(), parsedEpisode.episodeNo())) {
                    //TODO: 에러메세지에 중복 회차가 몇회차인지 추가로 작성해놓기
                    throw new AppException(EpisodeErrorCode.EPISODE_DUPLICATED , parsedEpisode.episodeNo() + " 화가 중복입니다.");
                }
            }
        }
    }

    /**
     * 파싱된 원본 회차 파일 하나를 S3에 저장하고 UploadFile 추적 정보를 PARSED 상태로 갱신한다.
     * 파일 안에서 분리된 각 회차는 별도 S3 원문과 Episode로 저장한다.
     */
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

    /**
     * 설정집 파일을 업로드 batch에 포함된 보조 파일로 저장하고 UploadFile로 추적한다.
     * 설정집은 회차 범위가 없으므로 감지된 시작/끝 회차와 회차 개수는 비워둔다.
     */
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

    //TODO: 함수명의 이름이 S3에 저장하는 작업을 한다는걸 예상하기 힘듦 네이밍 수정 필요함
    /**
     * 파싱된 회차 본문을 S3에 먼저 저장한 뒤, 저장소 메타데이터를 포함한 Episode 엔티티를 조립한다.
     */
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
