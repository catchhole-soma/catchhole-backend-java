package org.monitoring.catchholebackend.domain.upload.service;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.upload.dto.request.SettingBookUpdateRequest;
import org.monitoring.catchholebackend.domain.upload.dto.response.SettingBookResponse;
import org.monitoring.catchholebackend.domain.upload.dto.response.SettingBookSummaryResponse;
import org.springframework.web.multipart.MultipartFile;

public interface SettingBookService {

    List<SettingBookSummaryResponse> getSettingBooks(Long memberId, UUID workId);

    SettingBookSummaryResponse uploadSettingBook(Long memberId, UUID workId, MultipartFile file);

    SettingBookSummaryResponse uploadSettingBook(Long memberId, UUID workId, MultipartFile file,
            org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind referenceTimeKind,
            Integer referenceEpisodeNo);

    SettingBookResponse getSettingBook(Long memberId, UUID workId, UUID settingBookId);

    SettingBookResponse updateSettingBook(
            Long memberId,
            UUID workId,
            UUID settingBookId,
            SettingBookUpdateRequest request
    );

    org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisJobResponse analyzeSettingBook(Long memberId, UUID workId, UUID settingBookId);

    org.monitoring.catchholebackend.domain.upload.dto.response.SettingBookRevisionResponse getSettingBookRevision(
            Long memberId, UUID workId, UUID settingBookId, UUID revisionId);

    void deleteSettingBook(Long memberId, UUID workId, UUID settingBookId);
}
