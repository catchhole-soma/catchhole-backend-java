-- 기존 CHI1 행은 유지하며 새 이미지는 작품 소유권으로 보호한다.
ALTER TABLE private_world_images ALTER COLUMN vault_id DROP NOT NULL;
ALTER TABLE private_world_images ALTER COLUMN encrypted_metadata DROP NOT NULL;
ALTER TABLE private_world_images ADD COLUMN display_name VARCHAR(180);
ALTER TABLE private_world_images ADD CONSTRAINT ck_private_image_storage_mode CHECK (
    (vault_id IS NOT NULL AND encrypted_metadata IS NOT NULL AND display_name IS NULL)
    OR (vault_id IS NULL AND encrypted_metadata IS NULL AND display_name IS NOT NULL)
);
