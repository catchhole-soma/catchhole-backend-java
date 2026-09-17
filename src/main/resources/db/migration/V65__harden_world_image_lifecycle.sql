-- 적용된 migration은 유지하고 기존 이미지의 저장소 경로·선택을 보존한다.
ALTER TABLE world_image_catalog ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE world_image_catalog ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE world_setting_images ADD COLUMN created_at TIMESTAMP;
UPDATE world_setting_images SET created_at = updated_at;
ALTER TABLE world_setting_images ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE world_setting_images ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX idx_world_setting_images_private ON world_setting_images(private_image_id);
ALTER TABLE private_world_images ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'READY';
ALTER TABLE private_world_images ADD COLUMN storage_attempt_id UUID;
ALTER TABLE private_world_images ADD CONSTRAINT ck_private_world_image_status CHECK (status IN ('UPLOADING', 'READY', 'DELETING'));
CREATE INDEX idx_private_world_images_cleanup ON private_world_images(status, updated_at, id);
