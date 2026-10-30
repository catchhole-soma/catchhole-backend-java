package org.monitoring.catchholebackend.global.config.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

@DisplayName("#314 B2 검수 finding 대상·등급 컬럼 migration 계약")
class ValidationFindingTargetMigrationContractTest {

    @Test
    @DisplayName("대상·등급 컬럼은 모두 nullable이고 SETTING 전용 CHECK를 두며 백필은 NULL인 행의 등급·대상만 채우고 설정 키는 추정하지 않는다")
    void addsNullableTargetColumnsWithConservativeBackfill() throws IOException {
        String sql = new ClassPathResource("db/migration/V102__add_validation_finding_targets.sql").getContentAsString(StandardCharsets.UTF_8);
        String ddl = sql.substring(0, sql.indexOf("-- B2 backfill"));
        String backfill = sql.substring(sql.indexOf("-- B2 backfill"));

        assertThat(ddl)
                .contains("ADD COLUMN impact VARCHAR(20)", "ADD COLUMN subject_type VARCHAR(20)", "ADD COLUMN subject_id UUID",
                        "ADD COLUMN subject_name TEXT", "ADD COLUMN setting_key TEXT", "ADD COLUMN setting_name TEXT")
                .contains("impact IN ('SEVERE', 'CAUTION', 'REFERENCE')", "subject_type IN ('CHARACTER', 'WORLD_SETTING')")
                .contains("CHECK ((setting_key IS NULL) = (setting_name IS NULL))")
                .doesNotContainPattern("ADD COLUMN \\w+ [A-Z0-9()]+ NOT NULL")
                .doesNotContain("SET NOT NULL")
                .doesNotContain("ck_validation_finding_owner");
        assertThat(backfill)
                .contains("WHERE branch = 'SETTING' AND impact IS NULL")
                .contains("f.subject_type IS NULL")
                .contains("validation_check_history")
                .doesNotContain("SET setting_key")
                .doesNotContain("setting_name =")
                .doesNotContain("characters c")
                .doesNotContain("world_settings");
    }
}
