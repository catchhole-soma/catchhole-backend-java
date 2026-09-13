-- 비교 결과를 덮어쓰지 않고 자동 반영에서 확인한 보류 사유를 보존한다.
-- 과거 후보에는 사유를 추측하여 채우지 않는다.
ALTER TABLE setting_candidates ADD COLUMN automatic_review_hold_reason VARCHAR(50);
ALTER TABLE world_setting_candidates ADD COLUMN automatic_review_hold_reason VARCHAR(50);
