package org.monitoring.catchholebackend.domain.validation.type;
import java.util.List;
/** 작가 판단. 판단 행이 없으면 PENDING이다. 설정 항목의 NOT_CONFLICT와 오탈자의 KEEP_ORIGINAL은 서로 재사용하지 않는다. */
public enum ValidationJudgmentValue {
    PENDING, NEEDS_FIX, NOT_CONFLICT, KEEP_ORIGINAL;
    public static List<ValidationJudgmentValue> allowedFor(ValidationBranch branch) { return branch==ValidationBranch.SETTING?List.of(PENDING,NEEDS_FIX,NOT_CONFLICT):List.of(PENDING,NEEDS_FIX,KEEP_ORIGINAL); }
    public boolean allowedIn(ValidationBranch branch) { return allowedFor(branch).contains(this); }
}
