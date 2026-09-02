package org.monitoring.catchholebackend.global.config.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

@DisplayName("세계관 설정 비교 migration 계약")
class WorldSettingComparisonMigrationContractTest {

    @Test
    @DisplayName("범위 확인 제안은 기존 경로와 구조화된 사유를 보존한다")
    void scopeReviewStoresMatchedPathAndReason() throws IOException {
        String sql = readMigration();

        assertThat(sql)
                .contains("ADD COLUMN matched_scope_name VARCHAR(100)")
                .contains("ADD COLUMN matched_property_name VARCHAR(100)")
                .contains("ADD COLUMN comparison_review_reason VARCHAR(40)")
                .contains("'REVIEW_REQUIRED'")
                .contains("'SCOPE_UNRESOLVED'");
    }

    @Test
    @DisplayName("REVIEW_REQUIRED는 AI 제안에만 허용하고 사용자 최종 operation 제약은 바꾸지 않는다")
    void reviewRequiredDoesNotExpandFinalOperation() throws IOException {
        String sql = readMigration();

        assertThat(sql)
                .contains("DROP CONSTRAINT ck_world_setting_candidates_suggested_operation")
                .doesNotContain("DROP CONSTRAINT ck_world_setting_candidates_final_operation");
    }

    @Test
    @DisplayName("범위 확인 operation과 사유의 NULL 조합을 DB 제약이 거절한다")
    void scopeReviewReasonConstraintIsNullSafe() throws IOException {
        String sql = readMigration();

        assertThat(sql)
                .contains("suggested_operation IS NOT NULL")
                .contains("comparison_review_reason IS NOT NULL");
    }

    @Test
    @DisplayName("batch 상한 검수 사유를 기존 후보 projection 제약에도 추가한다")
    void batchMigrationExpandsCandidateReviewReasonConstraint() throws IOException {
        String sql = readMigration(
                "db/migration/V38__add_world_setting_comparison_batches.sql"
        );

        assertThat(sql)
                .contains("DROP CONSTRAINT ck_world_setting_candidates_comparison_review_reason")
                .contains("comparison_review_reason IN ('SCOPE_UNRESOLVED', 'BATCH_LIMIT_EXCEEDED')");
    }

    @Test
    @DisplayName("candidate와 batch가 canonical 주체 해소 snapshot을 함께 보존한다")
    void batchMigrationStoresCanonicalSubjectResolutionSnapshot() throws IOException {
        String sql = readMigration(
                "db/migration/V38__add_world_setting_comparison_batches.sql"
        );

        assertThat(sql)
                .contains("ADD COLUMN subject_resolution_type VARCHAR(20)")
                .contains("ADD COLUMN canonical_subject_key VARCHAR(150)")
                .contains("ADD COLUMN canonical_subject_name VARCHAR(100)")
                .contains("ADD COLUMN resolved_target_world_setting_ids JSONB")
                .contains("subject_resolution_type VARCHAR(20) NOT NULL")
                .contains("resolved_target_world_setting_ids JSONB NOT NULL")
                .contains("jsonb_typeof(resolved_target_world_setting_ids) = 'array'");
    }

    @Test
    @DisplayName("V39는 기존 root 설정 이름과 비교 시점 값 snapshot만 추가한다")
    void rootPropertyMoveMigrationStoresJsonArraySnapshot() throws IOException {
        String sql = readMigration(
                "db/migration/V39__add_world_setting_root_property_move_snapshots.sql"
        );

        assertThat(sql)
                .contains("ADD COLUMN existing_root_property_move_snapshots JSONB")
                .contains("NOT NULL DEFAULT '[]'::JSONB")
                .contains("jsonb_typeof(existing_root_property_move_snapshots) = 'array'")
                .doesNotContain("root_property_moves_applied_world_setting_version")
                .doesNotContain("root_property_moves_disabled");
    }

    @Test
    @DisplayName("V40은 root 이동 적용 상태와 신규 비교 검증 사유를 추가한다")
    void rootPropertyMoveStateMigrationStoresApplicationBoundary() throws IOException {
        String sql = readMigration(
                "db/migration/V40__add_world_setting_root_property_move_state.sql"
        );

        assertThat(sql)
                .contains("ADD COLUMN root_property_moves_applied_world_setting_version BIGINT")
                .contains("ADD COLUMN root_property_moves_disabled BOOLEAN NOT NULL DEFAULT FALSE")
                .contains("root_property_moves_applied_world_setting_version >= 0")
                .contains("root_property_moves_disabled = FALSE")
                .contains("'ROOT_PROPERTY_MOVE_INVALID'")
                .contains("'SYNTHETIC_SCOPE_SINGLETON'")
                .contains("'BATCH_PROPOSED_PATH_DUPLICATED'");
    }

    private String readMigration() throws IOException {
        return readMigration("db/migration/V36__add_world_setting_scope_review.sql");
    }

    private String readMigration(String path) throws IOException {
        return new ClassPathResource(path)
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
