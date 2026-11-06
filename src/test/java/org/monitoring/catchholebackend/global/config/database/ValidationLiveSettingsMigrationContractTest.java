package org.monitoring.catchholebackend.global.config.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

@DisplayName("#381 검수 live 설정 V119 migration 계약")
class ValidationLiveSettingsMigrationContractTest {

    @Test
    @DisplayName("READY 조건은 baseline_hash만 요구하고 quoted_subjects는 nullable 열이며 legacy seed 열·행은 지우거나 고치지 않는다")
    void relaxesReadyCheckAndAddsNullableQuotedSubjects() throws IOException {
        String sql = new ClassPathResource("db/migration/V119__validation_live_settings.sql").getContentAsString(StandardCharsets.UTF_8);
        String statements = sql.lines().filter(line -> !line.stripLeading().startsWith("--")).reduce("", (a, b) -> a + b + "\n");

        assertThat(statements)
                .contains("ALTER TABLE validation_reports DROP CONSTRAINT ck_validation_reports_ready_baseline;")
                .contains("ADD CONSTRAINT ck_validation_reports_ready_baseline_v2")
                .contains("CHECK (baseline_status <> 'READY' OR baseline_hash IS NOT NULL)")
                .contains("ALTER TABLE validation_findings ADD COLUMN quoted_subjects JSONB;")
                .contains("COMMENT ON COLUMN validation_reports.baseline_seed", "COMMENT ON COLUMN validation_reports.baseline_entries",
                        "COMMENT ON COLUMN validation_reports.confirmed_state_hash", "COMMENT ON COLUMN validation_findings.quoted_subjects")
                .doesNotContainPattern("(?i)quoted_subjects\\s+JSONB\\s+NOT\\s+NULL")
                .doesNotContainPattern("(?i)\\bUPDATE\\b")
                .doesNotContainPattern("(?i)\\bDELETE\\b")
                .doesNotContainPattern("(?i)DROP\\s+COLUMN");
        assertThat(statements.lines().filter(line -> line.startsWith("ALTER TABLE"))
                .allMatch(line -> line.startsWith("ALTER TABLE validation_reports") || line.startsWith("ALTER TABLE validation_findings"))).isTrue();
    }
}
