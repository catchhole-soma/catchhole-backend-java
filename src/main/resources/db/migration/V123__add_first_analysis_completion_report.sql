-- 첫 분석 완료 GA4 전환 이벤트(#426)를 계정당 한 번만 보내기 위한 보고 선점 시각.
-- 완료 자체는 저장하지 않고 조회 때 남아 있는 분석 Job으로 판정한다.
ALTER TABLE members ADD COLUMN first_analysis_completion_reported_at TIMESTAMP;
COMMENT ON COLUMN members.first_analysis_completion_reported_at IS
    '첫 분석 완료 GA4 이벤트 보고 선점 시각(#426). V123 이전 완료 회원은 남아 있는 가장 이른 완료 시각으로 이관';

-- 배포 전에 이미 첫 설정 추출을 완료한 회원은 보고 대상에서 뺀다.
-- 과거 완료가 배포 직후 GA4에 한꺼번에 기록되지 않게 하기 위함이다.
-- 완료 조건은 AnalysisJobRepository.existsCompletedSettingExtractionByMemberId와 같다.
UPDATE members m
SET first_analysis_completion_reported_at = history.completed_at
FROM (
    SELECT w.member_id, MIN(COALESCE(j.completed_at, j.updated_at)) AS completed_at
    FROM analysis_jobs j
    JOIN works w ON w.id = j.work_id
    WHERE j.job_type = 'SETTING_EXTRACTION'
      AND j.status = 'SUCCEEDED'
      AND (j.analysis_mode <> 'ORDERED_PROVISIONAL'
           OR (j.journal_status = 'SEALED'
               AND (j.review_mode <> 'AUTOMATIC'
                    OR j.automatic_applied_at IS NOT NULL
                    OR j.review_application_held_at IS NOT NULL)))
    GROUP BY w.member_id
) history
WHERE m.id = history.member_id;
