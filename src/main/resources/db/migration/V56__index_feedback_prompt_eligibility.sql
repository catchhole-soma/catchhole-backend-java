-- 안내 조회의 리터럴 조건과 일치시켜 제외 대상은 인덱스에서부터 건너뛴다.
CREATE INDEX idx_works_active_member
    ON works (member_id, id)
    WHERE lifecycle_status = 'ACTIVE';

CREATE INDEX idx_episodes_non_archived_work
    ON episodes (work_id)
    WHERE status <> 'ARCHIVED';
