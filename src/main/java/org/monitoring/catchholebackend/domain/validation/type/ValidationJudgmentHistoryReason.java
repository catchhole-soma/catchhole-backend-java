package org.monitoring.catchholebackend.domain.validation.type;
/** AUTHOR는 작가 저장, REVISION_SUPERSEDED는 같은 입력 재시도에서 항목 revision이 바뀌어 새 revision이 PENDING이 된 기록이다. */
public enum ValidationJudgmentHistoryReason { AUTHOR, REVISION_SUPERSEDED }
