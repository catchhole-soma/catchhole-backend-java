-- 재시도에 계속 실패하는 이미지가 후속 삭제·오래된 업로드를 가로막지 않도록 시도 순서를 보존한다.
ALTER TABLE private_world_images ADD COLUMN cleanup_attempted_at TIMESTAMP;
