package org.monitoring.catchholebackend.global.config.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

@DisplayName("캐릭터 Fact 비교 migration 계약")
class CharacterFactComparisonMigrationContractTest {

    @Test
    @DisplayName("기존 후보를 끝난 분석 Job의 영구 PENDING 상태로 이관하지 않는다")
    void legacyCandidatesAreNotBackfilledAsPending() throws IOException {
        String sql = readMigration("db/migration/V20__add_character_fact_comparison.sql");
        String candidateBackfill = sql.substring(
                sql.indexOf("UPDATE setting_candidates"),
                sql.indexOf("ALTER TABLE setting_candidates\n    ADD CONSTRAINT")
        );

        assertThat(candidateBackfill)
                .contains("THEN 'WAITING_FOR_CHARACTER_MATCH'")
                .contains("ELSE 'NOT_REQUIRED'")
                .doesNotContain("THEN 'PENDING'");
    }

    @Test
    @DisplayName("기존 current 이상 데이터는 slot별 최신 Fact 한 건만 provenance로 이관한다")
    void currentFactBackfillSelectsOneDeterministicSourcePerSlot() throws IOException {
        String sql = readMigration("db/migration/V20__add_character_fact_comparison.sql");

        assertThat(sql)
                .contains("PARTITION BY current_fact.character_id, current_fact.fact_type, current_fact.fact_key")
                .contains("ORDER BY current_fact.effective_from_episode_no DESC NULLS LAST")
                .contains("WHERE ranked_fact.current_rank = 1");
    }

    @Test
    @DisplayName("provenance는 같은 캐릭터와 canonical slot의 Fact만 참조한다")
    void provenanceUsesSameCharacterAndSlotCompositeForeignKey() throws IOException {
        String sql = readMigration("db/migration/V22__enforce_character_snapshot_source_slot.sql");

        assertThat(sql)
                .contains("UNIQUE (character_id, fact_type, fact_key, id)")
                .contains("FOREIGN KEY (character_id, fact_type, fact_key, source_fact_id)")
                .contains("REFERENCES character_facts (character_id, fact_type, fact_key, id)")
                .contains("DROP CONSTRAINT uk_character_facts_character_id_id");
    }

    @Test
    @DisplayName("slot 순서 unique index와 같은 보조 index를 중복 생성하지 않는다")
    void provenanceDoesNotCreateDuplicateSlotIndex() throws IOException {
        String sql = readMigration("db/migration/V20__add_character_fact_comparison.sql");

        assertThat(sql)
                .contains("uk_character_snapshot_sources_slot_order")
                .doesNotContain("idx_character_snapshot_sources_slot");
    }

    @Test
    @DisplayName("후보별 활성 hidden 비교 Job은 partial unique index로 최종 중복을 방어한다")
    void activeCharacterComparisonJobIsUniquePerCandidate() throws IOException {
        String sql = readMigration("db/migration/V20__add_character_fact_comparison.sql");

        assertThat(sql)
                .contains("CREATE UNIQUE INDEX uk_analysis_jobs_active_setting_candidate")
                .contains("ON analysis_jobs (setting_candidate_id)")
                .contains("status IN ('PENDING', 'RUNNING')");
    }

    private String readMigration(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
