CREATE TABLE world_image_catalog (
    id VARCHAR(100) PRIMARY KEY,
    category VARCHAR(40) NOT NULL,
    name VARCHAR(100) NOT NULL,
    search_text TEXT NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    thumbnail_sha VARCHAR(64) NOT NULL,
    image_sha VARCHAR(64) NOT NULL,
    CONSTRAINT ck_world_image_category CHECK (category IN ('RACE','FACTION','LOCATION','MONSTER','POWER_SYSTEM','WORLD_RULE_HISTORY','IMPORTANT_ITEM'))
);
CREATE UNIQUE INDEX uk_world_image_default ON world_image_catalog(category) WHERE is_default;
CREATE INDEX idx_world_image_category_name ON world_image_catalog(category, name, id);
CREATE INDEX idx_world_image_thumbnail ON world_image_catalog(thumbnail_sha);
CREATE INDEX idx_world_image_full ON world_image_catalog(image_sha);

CREATE TABLE world_image_aliases (
    catalog_id VARCHAR(100) NOT NULL REFERENCES world_image_catalog(id) ON DELETE CASCADE,
    alias VARCHAR(150) NOT NULL,
    PRIMARY KEY (catalog_id, alias)
);

-- 설정 내용·version과 독립적인 표시용 선택. 해제해도 version을 보존한다.
CREATE TABLE world_setting_images (
    world_setting_id UUID PRIMARY KEY REFERENCES world_settings(id) ON DELETE CASCADE,
    catalog_id VARCHAR(100) REFERENCES world_image_catalog(id),
    version BIGINT NOT NULL DEFAULT 0,
    selection_source VARCHAR(20),
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_world_setting_image_selection CHECK (
        (catalog_id IS NULL AND selection_source IS NULL) OR
        (catalog_id IS NOT NULL AND selection_source = 'MANUAL')
    )
);
