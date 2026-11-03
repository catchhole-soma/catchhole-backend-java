package org.monitoring.catchholebackend.global.config.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

@DisplayName("#349 검수 실행 단위 cap의 run_start_generation migration 계약")
class ValidationJobRunStartGenerationMigrationContractTest {

    @Test
    @DisplayName("기존 Job은 1로 채우는 NOT NULL 컬럼이며 1 이상 generation 이하만 허용하고 다른 테이블·사용량 행은 바꾸지 않는다")
    void addsRunStartGenerationWithConservativeDefault() throws IOException {
        String sql = new ClassPathResource("db/migration/V113__add_validation_job_run_start_generation.sql").getContentAsString(StandardCharsets.UTF_8);
        String statements = sql.lines().filter(line -> !line.stripLeading().startsWith("--")).reduce("", (a, b) -> a + b + "\n");

        assertThat(statements)
                .contains("ALTER TABLE validation_jobs ADD COLUMN run_start_generation BIGINT NOT NULL DEFAULT 1")
                .contains("CHECK (run_start_generation >= 1 AND run_start_generation <= generation)")
                .doesNotContain("UPDATE")
                .doesNotContain("ai_token_usages")
                .doesNotContainPattern("(?i)drop\\s");
        assertThat(sql.lines().filter(line -> line.startsWith("ALTER TABLE")).allMatch(line -> line.startsWith("ALTER TABLE validation_jobs"))).isTrue();
    }
}
