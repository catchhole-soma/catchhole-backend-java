package org.monitoring.catchholebackend.domain.upload.type;

import org.monitoring.catchholebackend.domain.upload.exception.UploadErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;

public enum SettingBookReferenceTimeKind {
    UNSPECIFIED, WORK_START, EPISODE;

    public void validate(Integer episodeNo) {
        if (this == EPISODE ? episodeNo == null || episodeNo < 1 : episodeNo != null) {
            throw new AppException(UploadErrorCode.UPLOAD_SETTING_BOOK_REFERENCE_INVALID);
        }
    }
}
