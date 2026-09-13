ALTER TABLE world_setting_candidates ADD COLUMN comparison_diagnostics JSONB;
ALTER TABLE world_setting_candidates ADD CONSTRAINT ck_world_setting_candidates_comparison_diagnostics
    CHECK (comparison_diagnostics IS NULL OR (jsonb_typeof(comparison_diagnostics) = 'array'
        AND jsonb_array_length(comparison_diagnostics) <= 30));
COMMENT ON COLUMN world_setting_candidates.comparison_diagnostics IS
    '시도 번호·검증 규칙·고정 비교 입력의 후보 ref와 기존 경로만 보존한다. 원문 prompt/response는 저장하지 않는다.';
