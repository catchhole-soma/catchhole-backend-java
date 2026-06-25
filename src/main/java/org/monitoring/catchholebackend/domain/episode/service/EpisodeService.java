package org.monitoring.catchholebackend.domain.episode.service;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUpdateRequest;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadRequest;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeSummaryResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeUploadResponse;
import org.springframework.web.multipart.MultipartFile;

public interface EpisodeService {

    /**
     * 작품 소유권을 확인한 뒤 작품에 속한 회차 목록을 최신 회차순으로 조회한다.
     */
    List<EpisodeSummaryResponse> getEpisodes(Long memberId, UUID workId);

    /**
     * 작품 소유권과 회차 소속을 확인한 뒤 S3 원문을 포함한 회차 상세 정보를 조회한다.
     */
    EpisodeResponse getEpisode(Long memberId, UUID workId, UUID episodeId);

    /**
     * 작품 소유권과 회차 소속을 확인하고, 변경하려는 회차 번호가 작품 안에서 중복되지 않는지 검증한다.
     * S3에 저장된 회차 원문을 교체한 뒤 DB에는 제목, 회차 번호, S3 key/version/hash/글자 수 메타데이터를 갱신한다.
     * 변경 결과에 따라 작품의 최신 회차 번호도 다시 계산한다.
     */
    EpisodeResponse updateEpisode(Long memberId, UUID workId, UUID episodeId, EpisodeUpdateRequest request);

    /**
     * 작품 소유권과 회차 소속을 확인한 뒤 S3 원문과 회차 데이터를 삭제한다.
     * 삭제 후 작품의 최신 회차 번호를 다시 계산한다.
     */
    void deleteEpisode(Long memberId, UUID workId, UUID episodeId);

    /**
     * 작품 소유권을 확인한 뒤 업로드 파일을 파싱해 회차 업로드 흐름을 처리한다.
     * 업로드 batch를 만들고, 원본 파일 단위의 UploadFile 추적 정보와 파싱된 Episode를 함께 생성한다.
     * 회차 본문은 S3에 저장하고 DB에는 S3 key/version/hash/글자 수 메타데이터를 남긴다.
     * 설정집 파일이 함께 전달되면 같은 batch 안에서 설정집 UploadFile로 추적한다.
     */
    EpisodeUploadResponse uploadEpisodes(
            Long memberId,
            UUID workId,
            EpisodeUploadRequest request,
            List<MultipartFile> episodeFiles,
            MultipartFile settingBookFile
    );
}
