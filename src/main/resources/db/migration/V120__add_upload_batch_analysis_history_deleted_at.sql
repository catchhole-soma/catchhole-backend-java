-- 분석 목록에서 작가가 지운 업로드 묶음 시각. 분석 Job·후보·확정 설정·검수 report는 지우지 않고 목록에서만 숨긴다.
-- 이 시각 뒤에 같은 묶음에 새 분석 요청이나 검수 접수가 생기면 묶음은 다시 목록에 나온다.
alter table upload_batches add column analysis_history_deleted_at timestamp;
