package org.monitoring.catchholebackend.domain.upload.type;

public enum UploadStatus {

    //TODO: 파일 업로드시 로직 정의를 다시 해야할 듯(현재 peding 상태에서 failed 로 갔는지 processing 에서 failed 로 갔는지 확인이 불가능함
    //TODO: enum 변수들중 상태값 관련 enum 들은 무슨 작업이 완료되면 어디에서 상태값이 어떻게 변이되는지에 대한 내용을 주석으로 추가해두기
    PENDING, // 초기 작업 생성 단계 (회차 분리 확인)
    PROCESSING,
    COMPLETED,
    FAILED
}
