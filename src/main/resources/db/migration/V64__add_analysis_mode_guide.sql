-- 계정의 첫 분석 전에만 안내하고, 작품 삭제 후에도 경험 여부를 보존한다.
ALTER TABLE members ADD COLUMN analysis_guide_shown_at TIMESTAMP;
ALTER TABLE members ADD COLUMN first_analysis_started_at TIMESTAMP;
UPDATE members m
SET first_analysis_started_at = history.started_at
FROM (
    SELECT w.member_id, MIN(j.created_at) AS started_at
    FROM analysis_jobs j JOIN works w ON w.id = j.work_id
    GROUP BY w.member_id
) history
WHERE m.id = history.member_id;
