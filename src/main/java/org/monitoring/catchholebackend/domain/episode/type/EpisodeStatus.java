package org.monitoring.catchholebackend.domain.episode.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum EpisodeStatus {

    /**
     * 회차 원문이 S3에 저장되고 Episode row가 생성 또는 수정된 상태.
     * Episode.create()와 updateContent()가 이 상태로 둔다.
     */
    UPLOADED("원문 저장 완료"),

    /**
     * 원문을 문단/장면/토큰 기준 청크로 나누는 중인 상태.
     * TODO: Worker 또는 내부 API에서 markChunking()을 호출하는 시점을 확정한다.
     */
    CHUNKING("원문 청킹 중"),

    /**
     * 원문 청크 저장이 끝난 상태.
     * TODO: ManuscriptChunk 모델 구현 후 markChunked() 호출 위치를 확정한다.
     */
    CHUNKED("청크 저장 완료"),

    /**
     * 청크를 LLM 입력에 맞게 정규화/요약/구조화하는 중인 상태.
     * TODO: 전처리 산출물 저장 모델을 정한 뒤 markPreprocessing() 호출 위치를 확정한다.
     */
    PREPROCESSING("LLM 전처리 중"),

    /**
     * LLM 전처리 산출물 저장이 끝난 상태.
     * TODO: PreprocessedManuscriptChunk 구현 후 markPreprocessed() 호출 위치를 확정한다.
     */
    PREPROCESSED("LLM 전처리 완료"),

    /**
     * 설정 추출 또는 신규 회차 검수 분석이 진행 중인 상태.
     * TODO: AnalysisJob 진행 단계와 Episode 단위 상태를 어떻게 동기화할지 후속 결정한다.
     */
    ANALYZING("AI 분석 중"),

    /**
     * 회차 기준 분석 산출물 저장이 끝난 상태.
     * TODO: 분석 결과가 부분 성공일 때 ANALYZED로 볼지 FAILED로 볼지 후속 결정한다.
     */
    ANALYZED("AI 분석 완료"),

    /**
     * 청킹, 전처리, 분석 중 회차 단위 처리가 실패한 상태.
     * TODO: 실패 사유를 Episode, AnalysisJob, 리포트 계층에 둘지, 공통 실패 이력 테이블 분리까지 고려할지 후속 검토한다.
     */
    FAILED("처리 실패"),

    /**
     * 회차를 일반 조회/분석 대상에서 제외한 보관 상태.
     * TODO: 현재 삭제 API는 hard delete이므로 archive/restore API 도입 여부를 후속 결정한다.
     */
    ARCHIVED("보관됨");

    private final String toKorean;
}
