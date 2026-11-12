ALTER TABLE works ADD COLUMN cover_image_id UUID;
ALTER TABLE works ADD COLUMN cover_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE work_cover_images (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL REFERENCES works(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('UPLOADING', 'READY', 'DELETING')),
    cleanup_attempted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_work_cover_images_work ON work_cover_images(work_id);
CREATE INDEX idx_work_cover_images_cleanup ON work_cover_images(status, cleanup_attempted_at, updated_at);
ALTER TABLE works ADD CONSTRAINT fk_works_cover_image
    FOREIGN KEY (cover_image_id) REFERENCES work_cover_images(id);
