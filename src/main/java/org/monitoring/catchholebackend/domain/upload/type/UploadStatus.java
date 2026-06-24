package org.monitoring.catchholebackend.domain.upload.type;

public enum UploadStatus {

    /**
     * UploadBatch가 생성된 직후의 초기 상태.
     * 현재 회차 업로드 흐름에서는 파일 파싱과 중복 회차 검증을 통과한 뒤 batch row가 저장될 때 설정된다.
     */
    PENDING,

    /**
     * 원본 업로드 파일, 회차 원문, Episode row를 저장하는 중인 상태.
     * EpisodeUploadProcessor가 batch를 저장한 직후 startProcessing()으로 전환한다.
     */
    PROCESSING,

    /**
     * 업로드 요청에 포함된 모든 회차/설정집 파일 저장과 Episode 생성이 끝난 상태.
     * EpisodeUploadProcessor가 응답을 반환하기 직전에 complete()로 전환한다.
     */
    COMPLETED,

    /**
     * 업로드 처리 중 실패한 상태.
     * TODO: 현재 동기 업로드는 예외 시 트랜잭션 rollback으로 FAILED가 영속화되지 않을 수 있다.
     * 실패 이력을 남기는 방식을 정할 때 batch 생성 시점, 별도 트랜잭션, 실패 사유 저장 위치를 함께 검토한다.
     */
    FAILED
}
