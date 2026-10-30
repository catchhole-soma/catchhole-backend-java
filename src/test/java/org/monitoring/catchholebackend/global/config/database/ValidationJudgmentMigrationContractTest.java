package org.monitoring.catchholebackend.global.config.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

@DisplayName("GH312 작가 판단 migration 계약")
class ValidationJudgmentMigrationContractTest {

    @Test
    @DisplayName("finding당 판단 하나와 분기별 판단 값·이력 사유 제약을 함께 만든다")
    void createsJudgmentAndHistoryConstraints() throws IOException {
        String sql = new ClassPathResource("db/migration/V96__add_validation_judgments.sql").getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE validation_judgments")
                .contains("finding_id UUID NOT NULL UNIQUE REFERENCES validation_findings(id)")
                .contains("judgment IN ('PENDING','NEEDS_FIX','NOT_CONFLICT','KEEP_ORIGINAL')")
                .contains("(branch='SETTING' AND judgment IN ('PENDING','NEEDS_FIX','NOT_CONFLICT')) OR (branch='TYPO' AND judgment IN ('PENDING','NEEDS_FIX','KEEP_ORIGINAL'))")
                .contains("CREATE INDEX idx_validation_judgments_report ON validation_judgments(report_id)")
                .contains("CREATE TABLE validation_judgment_history")
                .contains("reason IN ('AUTHOR','REVISION_SUPERSEDED')")
                .contains("CREATE INDEX idx_validation_judgment_history_report ON validation_judgment_history(report_id)")
                .doesNotContain("ON DELETE CASCADE");
    }
}
