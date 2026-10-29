package org.monitoring.catchholebackend.global.config.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

@DisplayName("GH312 검토 종료 receipt migration 계약")
class ValidationReviewCompletionMigrationContractTest {

    @Test
    @DisplayName("report당 receipt 하나와 요청 키 부분 unique·추출 Job FK·경로별 키 제약을 만든다")
    void createsReviewCompletionConstraints() throws IOException {
        String sql = new ClassPathResource("db/migration/V97__add_validation_review_completions.sql").getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE validation_review_completions")
                .contains("report_id UUID NOT NULL UNIQUE REFERENCES validation_reports(id)")
                .contains("work_id UUID NOT NULL REFERENCES works(id)")
                .contains("episode_id UUID NOT NULL REFERENCES episodes(id)")
                .contains("trigger VARCHAR(20) NOT NULL CHECK (trigger IN ('AUTOMATIC','EXPLICIT'))")
                .contains("extraction_job_id UUID NOT NULL REFERENCES analysis_jobs(id)")
                .contains("extraction_job_generation BIGINT NOT NULL DEFAULT 1")
                .contains("(trigger = 'AUTOMATIC' AND idempotency_key IS NULL AND request_hash IS NULL)")
                .contains("CREATE UNIQUE INDEX uk_validation_review_completions_key ON validation_review_completions(work_id, idempotency_key) WHERE idempotency_key IS NOT NULL")
                .doesNotContain("ON DELETE CASCADE")
                .doesNotContain("validation_reports ADD")
                .doesNotContain("ALTER TABLE validation_reports");
    }

    @Test
    @DisplayName("자동 검토 종료 보류 사유는 report의 nullable 코드·시각 쌍으로만 추가한다")
    void addsNullableReleaseHoldPair() throws IOException {
        String sql = new ClassPathResource("db/migration/V98__add_validation_report_release_hold.sql").getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("ALTER TABLE validation_reports ADD COLUMN release_hold_code VARCHAR(60), ADD COLUMN release_hold_at TIMESTAMP")
                .contains("CHECK ((release_hold_code IS NULL) = (release_hold_at IS NULL))")
                .doesNotContain("NOT NULL")
                .doesNotContain("ck_validation_report_status");
    }
}
