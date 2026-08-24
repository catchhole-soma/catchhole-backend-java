-- 작품 파기 감사 기록은 회원 hard delete 뒤에도 보존하므로 member FK를 제거한다.
ALTER TABLE work_purge_requests
    DROP CONSTRAINT fk_work_purge_requests_member;

-- 다른 데이터에 남은 검수자 표시는 회원 삭제를 막지 않고 익명화한다.
ALTER TABLE world_setting_candidates
    DROP CONSTRAINT fk_world_setting_candidates_reviewer,
    ADD CONSTRAINT fk_world_setting_candidates_reviewer
        FOREIGN KEY (reviewed_by) REFERENCES members (id) ON DELETE SET NULL;

CREATE TABLE member_withdrawal_requests (
    id UUID PRIMARY KEY,
    member_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    requested_at TIMESTAMP NOT NULL,
    processing_started_at TIMESTAMP,
    next_attempt_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    retention_expires_at TIMESTAMP,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error_code VARCHAR(80),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_member_withdrawal_requests_member UNIQUE (member_id),
    CONSTRAINT ck_member_withdrawal_requests_status
        CHECK (status IN ('REQUESTED', 'PROCESSING', 'COMPLETED')),
    CONSTRAINT ck_member_withdrawal_requests_attempt_count
        CHECK (attempt_count >= 0)
);

CREATE INDEX idx_member_withdrawal_requests_process
    ON member_withdrawal_requests (status, next_attempt_at, requested_at);

CREATE INDEX idx_member_withdrawal_requests_retention
    ON member_withdrawal_requests (retention_expires_at);
