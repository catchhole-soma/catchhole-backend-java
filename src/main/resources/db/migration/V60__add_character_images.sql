-- 캐릭터 설정·분석 version과 독립적인 이미지 선택. 종족 자동 연결은 조회 시 계산한다.
CREATE TABLE character_images (
    character_id UUID PRIMARY KEY REFERENCES characters(id) ON DELETE CASCADE,
    catalog_id VARCHAR(100) REFERENCES world_image_catalog(id),
    private_image_id UUID REFERENCES private_world_images(id),
    selection_source VARCHAR(20),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_character_image_selection CHECK (
        (catalog_id IS NULL AND private_image_id IS NULL AND selection_source IS NULL) OR
        (catalog_id IS NULL AND private_image_id IS NULL AND selection_source = 'DEFAULT') OR
        (catalog_id IS NOT NULL AND private_image_id IS NULL AND selection_source = 'MANUAL') OR
        (catalog_id IS NULL AND private_image_id IS NOT NULL AND selection_source = 'PRIVATE')
    )
);
CREATE INDEX idx_character_images_private ON character_images(private_image_id);
