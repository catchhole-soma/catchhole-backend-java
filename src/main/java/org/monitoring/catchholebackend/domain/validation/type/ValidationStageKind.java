package org.monitoring.catchholebackend.domain.validation.type;
/**
 * 저장된 의미 단계 종류(EnumType.STRING). SETTING grid v4(#349 개편)는 PLANNER(구간마다)·MERGED_PLAN만, TYPO grid v1은 TYPO_SEGMENT만 요구한다.
 * RELATED_SELECTION(grid v1)·SUBJECT_RESOLUTION·COVERAGE(grid v2·v3)·BOOK_SOURCE·BOOK_COVERAGE는 저장 이력 읽기용으로만 남으며 v4 grid에서 게시하면 거절한다.
 */
public enum ValidationStageKind { PLANNER, RELATED_SELECTION, SUBJECT_RESOLUTION, COVERAGE, BOOK_SOURCE, BOOK_COVERAGE, MERGED_PLAN, TYPO_SEGMENT }
