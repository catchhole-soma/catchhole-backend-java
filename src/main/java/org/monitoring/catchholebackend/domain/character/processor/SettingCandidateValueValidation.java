package org.monitoring.catchholebackend.domain.character.processor;

import java.util.Objects;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateValueValidationStatus;
import org.monitoring.catchholebackend.global.exception.AppException;

/** 조회용 검증 상태와 쓰기 경로의 fail-closed 처리가 공유하는 값 검증 결과다. */
public record SettingCandidateValueValidation(
        SettingCandidateValueValidationStatus status,
        CharacterErrorCode errorCode,
        boolean repairable
) {

    public SettingCandidateValueValidation {
        Objects.requireNonNull(status);
        if ((status == SettingCandidateValueValidationStatus.INVALID) != (errorCode != null)) {
            throw new IllegalArgumentException("INVALID 상태에만 errorCode가 있어야 합니다.");
        }
        if (status != SettingCandidateValueValidationStatus.INVALID && repairable) {
            throw new IllegalArgumentException("INVALID 상태만 수정 가능할 수 있습니다.");
        }
    }

    public static SettingCandidateValueValidation valid() {
        return new SettingCandidateValueValidation(
                SettingCandidateValueValidationStatus.VALID,
                null,
                false
        );
    }

    public static SettingCandidateValueValidation invalid(CharacterErrorCode errorCode) {
        return new SettingCandidateValueValidation(
                SettingCandidateValueValidationStatus.INVALID,
                Objects.requireNonNull(errorCode),
                true
        );
    }

    public static SettingCandidateValueValidation unrepairableInvalid(CharacterErrorCode errorCode) {
        return new SettingCandidateValueValidation(
                SettingCandidateValueValidationStatus.INVALID,
                Objects.requireNonNull(errorCode),
                false
        );
    }

    public static SettingCandidateValueValidation notApplicable() {
        return new SettingCandidateValueValidation(
                SettingCandidateValueValidationStatus.NOT_APPLICABLE,
                null,
                false
        );
    }

    public boolean isInvalid() {
        return status == SettingCandidateValueValidationStatus.INVALID;
    }

    public void throwIfInvalid() {
        if (isInvalid()) {
            throw new AppException(errorCode);
        }
    }
}
