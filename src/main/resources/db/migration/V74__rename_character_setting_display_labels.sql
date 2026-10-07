-- 시스템 기본 소지품 표시명만 바꾼다. 저장 키·자료형·별칭·작가별 스키마는 유지한다.
UPDATE character_setting_schemas
SET display_name = '소지품',
    updated_at = CURRENT_TIMESTAMP
WHERE work_id IS NULL
  AND source = 'SYSTEM_SEED'
  AND schema_key = 'items.item'
  AND display_name = '아이템';
