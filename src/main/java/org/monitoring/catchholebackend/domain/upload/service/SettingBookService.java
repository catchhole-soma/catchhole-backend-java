package org.monitoring.catchholebackend.domain.upload.service;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.upload.dto.response.SettingBookResponse;
import org.monitoring.catchholebackend.domain.upload.dto.response.SettingBookSummaryResponse;
import org.springframework.web.multipart.MultipartFile;

public interface SettingBookService {

    List<SettingBookSummaryResponse> getSettingBooks(Long memberId, UUID workId);

    SettingBookSummaryResponse uploadSettingBook(Long memberId, UUID workId, MultipartFile file);

    SettingBookResponse getSettingBook(Long memberId, UUID workId, UUID settingBookId);

    void deleteSettingBook(Long memberId, UUID workId, UUID settingBookId);
}
