-- 자동 연결도 이미지 선택 행에 저장한다. 기존 직접 선택·기본 복귀 의도는 유지한다.
ALTER TABLE world_setting_images DROP CONSTRAINT ck_world_setting_image_selection;
UPDATE world_setting_images SET selection_source = 'DEFAULT' WHERE selection_source IS NULL;
ALTER TABLE world_setting_images ADD CONSTRAINT ck_world_setting_image_selection CHECK (COALESCE((
    (private_image_id IS NULL AND selection_source = 'AUTO') OR
    (catalog_id IS NULL AND private_image_id IS NULL AND selection_source = 'DEFAULT') OR
    (catalog_id IS NOT NULL AND private_image_id IS NULL AND selection_source = 'MANUAL') OR
    (catalog_id IS NULL AND private_image_id IS NOT NULL AND selection_source = 'PRIVATE')
), FALSE));
ALTER TABLE character_images DROP CONSTRAINT ck_character_image_selection;
ALTER TABLE character_images ADD CONSTRAINT ck_character_image_selection CHECK (COALESCE((
    (catalog_id IS NULL AND private_image_id IS NULL AND selection_source IS NULL) OR
    (private_image_id IS NULL AND selection_source = 'AUTO') OR
    (catalog_id IS NULL AND private_image_id IS NULL AND selection_source = 'DEFAULT') OR
    (catalog_id IS NOT NULL AND private_image_id IS NULL AND selection_source = 'MANUAL') OR
    (catalog_id IS NULL AND private_image_id IS NOT NULL AND selection_source = 'PRIVATE')
), FALSE));
-- 기존 대상 매칭은 운영자 보정 배치에서 수행한다. 서버 기동 시 전체 대상을 처리하지 않는다.
