package org.monitoring.catchholebackend.global.config.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

@DisplayName("캐릭터 목록 정렬 migration 계약")
class CharacterPageMigrationContractTest {

    @Test
    @DisplayName("생성순 인덱스를 최근 수정순과 ID 역순 인덱스로 교체한다")
    void replacesCreatedAtIndexWithUpdatedAtIndex() throws IOException {
        String sql = new ClassPathResource("db/migration/V23__sort_characters_by_recent_update.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("DROP INDEX IF EXISTS idx_characters_work_status_created_id")
                .contains("CREATE INDEX idx_characters_work_status_updated_id")
                .contains("ON characters (work_id, status, updated_at DESC, id DESC)");
    }
}
