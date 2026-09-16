-- 개인 이미지의 복호화 키·원본 파일명·이미지 평문은 서버에 저장하지 않는다.
CREATE TABLE private_image_vaults (
    id UUID PRIMARY KEY,
    member_id BIGINT NOT NULL UNIQUE REFERENCES members(id) ON DELETE CASCADE,
    key_check VARCHAR(512) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE TABLE private_world_images (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL REFERENCES works(id) ON DELETE CASCADE,
    vault_id UUID NOT NULL REFERENCES private_image_vaults(id),
    encrypted_metadata VARCHAR(4096) NOT NULL,
    image_bytes BIGINT NOT NULL,
    thumbnail_bytes BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_private_world_images_work_created ON private_world_images(work_id, created_at DESC, id);
ALTER TABLE world_setting_images ADD COLUMN private_image_id UUID REFERENCES private_world_images(id);
ALTER TABLE world_setting_images DROP CONSTRAINT ck_world_setting_image_selection;
ALTER TABLE world_setting_images ADD CONSTRAINT ck_world_setting_image_selection CHECK (
    (catalog_id IS NULL AND private_image_id IS NULL AND selection_source IS NULL) OR
    (catalog_id IS NOT NULL AND private_image_id IS NULL AND selection_source = 'MANUAL') OR
    (catalog_id IS NULL AND private_image_id IS NOT NULL AND selection_source = 'PRIVATE')
);
