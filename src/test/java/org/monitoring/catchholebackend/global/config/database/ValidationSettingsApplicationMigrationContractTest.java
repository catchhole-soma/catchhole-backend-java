package org.monitoring.catchholebackend.global.config.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

@DisplayName("GH312 S3 회차 전체 확정 receipt migration 계약")
class ValidationSettingsApplicationMigrationContractTest {

    @Test
    @DisplayName("report당 receipt 하나와 검토 종료·추출 Job FK, 상태·경로·적용 시각 CHECK와 요청 키 부분 unique를 만든다")
    void createsSettingsApplicationConstraints() throws IOException {
        String sql = new ClassPathResource("db/migration/V99__add_validation_settings_applications.sql").getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE validation_settings_applications")
                .contains("report_id UUID NOT NULL UNIQUE REFERENCES validation_reports(id)")
                .contains("work_id UUID NOT NULL REFERENCES works(id)")
                .contains("completion_id UUID NOT NULL REFERENCES validation_review_completions(id)")
                .contains("extraction_job_id UUID NOT NULL REFERENCES analysis_jobs(id)")
                .contains("status VARCHAR(20) NOT NULL CHECK (status IN ('HELD_REVIEW','APPLIED','FAILED'))")
                .contains("trigger VARCHAR(20) NOT NULL CHECK (trigger IN ('AUTOMATIC','EXPLICIT'))")
                .contains("hold_reasons JSONB")
                .contains("attempt BIGINT NOT NULL DEFAULT 1")
                .contains("CHECK ((status = 'APPLIED') = (applied_at IS NOT NULL))")
                .contains("CHECK ((status = 'HELD_REVIEW') = (hold_reasons IS NOT NULL))")
                .contains("(trigger = 'AUTOMATIC' AND idempotency_key IS NULL AND request_hash IS NULL)")
                .contains("CREATE UNIQUE INDEX uk_validation_settings_applications_key ON validation_settings_applications(work_id, idempotency_key) WHERE idempotency_key IS NOT NULL")
                .doesNotContain("ON DELETE CASCADE")
                .doesNotContain("ALTER TABLE validation_reports")
                .doesNotContain("ck_validation_report_status");
    }
}
