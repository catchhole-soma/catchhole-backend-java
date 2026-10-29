package org.monitoring.catchholebackend.global.config.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

@DisplayName("GH312 S4 검수 report 무효화 시각 migration 계약")
class ValidationReportInvalidatedAtMigrationContractTest {

    @Test
    @DisplayName("무효화 시각은 nullable 컬럼 하나이며 INVALIDATED report에만 값을 허용하고 과거 행을 추정해 채우지 않는다")
    void addsNullableInvalidatedAtWithoutBackfill() throws IOException {
        String sql = new ClassPathResource("db/migration/V100__add_validation_report_invalidated_at.sql").getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("ALTER TABLE validation_reports ADD COLUMN invalidated_at TIMESTAMP;")
                .contains("CHECK (invalidated_at IS NULL OR status = 'INVALIDATED')")
                .doesNotContain("NOT NULL")
                .doesNotContain("UPDATE validation_reports")
                .doesNotContain("ck_validation_report_status");
    }
}
