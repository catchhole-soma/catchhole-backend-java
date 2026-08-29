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

    private String readMigration() throws IOException {
        return new ClassPathResource("db/migration/V36__add_world_setting_scope_review.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
