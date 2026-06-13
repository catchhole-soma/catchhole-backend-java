package org.monitoring.catchholebackend.domain.episode.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum EpisodeStatus {
    UPLOADED("원문 저장 완료"),
    CHUNKING("원문 청킹 중"),
    CHUNKED("청크 저장 완료"),
    PREPROCESSING("LLM 전처리 중"),
    PREPROCESSED("LLM 전처리 완료"),
    ANALYZING("AI 분석 중"),
    ANALYZED("AI 분석 완료"),
    FAILED("처리 실패"),
    ARCHIVED("보관됨");

    private final String toKorean;
}
