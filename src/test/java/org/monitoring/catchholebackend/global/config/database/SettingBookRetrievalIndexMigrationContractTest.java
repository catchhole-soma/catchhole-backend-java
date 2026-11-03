package org.monitoring.catchholebackend.global.config.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

@DisplayName("접수 때 색인 채우기의 설정집 RAG 색인 migration(V114) 계약")
class SettingBookRetrievalIndexMigrationContractTest {

    @Test
    @DisplayName("새 표 두 개만 만들고 revision·설정집·작품 삭제를 따라 지워지며 READY는 임베딩 identity를 요구한다")
    void addsOnlyTheTwoSettingBookIndexTables() throws IOException {
        String sql = new ClassPathResource("db/migration/V114__add_setting_book_retrieval_index.sql").getContentAsString(StandardCharsets.UTF_8);
        String statements = sql.lines().filter(line -> !line.stripLeading().startsWith("--")).reduce("", (a, b) -> a + b + "\n");

        assertThat(statements)
                .contains("CREATE TABLE retrieval_setting_book_sources (")
                .contains("revision_id UUID PRIMARY KEY REFERENCES setting_book_revisions(id) ON DELETE CASCADE")
                .contains("setting_book_id UUID NOT NULL REFERENCES upload_files(id) ON DELETE CASCADE")
                .contains("work_id UUID NOT NULL REFERENCES works(id) ON DELETE CASCADE")
                .contains("CREATE TABLE retrieval_setting_book_chunks (")
                .contains("revision_id UUID NOT NULL REFERENCES retrieval_setting_book_sources(revision_id) ON DELETE CASCADE")
                .contains("embedding VECTOR(1536)")
                .contains("CONSTRAINT uk_retrieval_setting_book_chunk_index UNIQUE(revision_id,chunk_index)")
                .contains("(embedding IS NOT NULL AND embedding_model IS NOT NULL AND embedding_version IS NOT NULL AND embedded_at IS NOT NULL)")
                .doesNotContain("ALTER TABLE")
                .doesNotContain("UPDATE ")
                .doesNotContainPattern("(?i)drop\\s");
    }
}
