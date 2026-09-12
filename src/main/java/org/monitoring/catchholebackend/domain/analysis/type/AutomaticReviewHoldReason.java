package org.monitoring.catchholebackend.domain.analysis.type;

/** AI 비교 결과와 구분하여 기록하는 자동 반영 보류 사유다. */
public enum AutomaticReviewHoldReason {
    SUBJECT_RESOLUTION_FAILED("대상 연결을 완료하지 못했습니다. 원문을 보고 누구에 대한 설정인지 확인해 주세요."),
    COMPARISON_INPUT_TOO_LARGE("비교할 내용이 많아 이 설정은 직접 확인이 필요합니다. 다른 설정의 분석은 계속됩니다."),
    SUBJECT_CONFIRMATION_REQUIRED("이 설정들이 같은 대상을 설명하는지 확인해 주세요."),
    DEPENDENCY_CONFIRMATION_REQUIRED("먼저 확인해야 할 관련 설정이 남아 있습니다."),
    CURRENT_SETTING_CHANGED("비교한 뒤 기존 설정이 바뀌었습니다. 현재 내용과 제안을 확인해 주세요."),
    SETTING_VALUE_CONFIRMATION_REQUIRED("저장할 설정의 분류와 내용을 확인해 주세요."),
    SETTING_LOCATION_CONFLICT("이 설정을 어느 항목에 반영할지 확인해 주세요."),
    REVIEW_REQUIRED("자동으로 반영하지 못한 설정입니다. 원문과 제안을 직접 확인해 주세요.");

    private final String message;

    AutomaticReviewHoldReason(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
