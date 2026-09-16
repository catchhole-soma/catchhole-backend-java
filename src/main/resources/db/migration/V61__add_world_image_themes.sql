CREATE TABLE world_image_theme_assets (
    id VARCHAR(100) PRIMARY KEY,
    theme VARCHAR(30) NOT NULL,
    purpose VARCHAR(10) NOT NULL CHECK (purpose IN ('OVERVIEW','DEFAULT')),
    slot VARCHAR(40) NOT NULL CHECK (slot IN ('RACE','FACTION','LOCATION','MONSTER','POWER_SYSTEM','WORLD_RULE_HISTORY','IMPORTANT_ITEM','ALL')),
    name VARCHAR(100) NOT NULL,
    thumbnail_sha VARCHAR(64) NOT NULL,
    image_sha VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(theme,purpose,slot),
    CHECK (slot <> 'ALL' OR purpose = 'OVERVIEW')
);
CREATE INDEX idx_world_image_theme_thumbnail ON world_image_theme_assets(thumbnail_sha);
CREATE INDEX idx_world_image_theme_full ON world_image_theme_assets(image_sha);
CREATE TABLE world_image_recommendations (
    catalog_id VARCHAR(100) NOT NULL REFERENCES world_image_catalog(id) ON DELETE CASCADE,
    theme VARCHAR(30) NOT NULL,
    PRIMARY KEY(catalog_id,theme)
);
CREATE INDEX idx_world_image_recommendations_theme ON world_image_recommendations(theme,catalog_id);
