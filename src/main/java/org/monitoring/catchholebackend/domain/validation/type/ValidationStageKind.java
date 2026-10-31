package org.monitoring.catchholebackend.domain.validation.type;
/** RELATED_SELECTION은 grid v1의 저장 이력 읽기용으로만 남는다. grid v2(GH349)는 SUBJECT_RESOLUTION을 요구한다. */
public enum ValidationStageKind { PLANNER, RELATED_SELECTION, SUBJECT_RESOLUTION, COVERAGE, BOOK_SOURCE, BOOK_COVERAGE, MERGED_PLAN, TYPO_SEGMENT }
