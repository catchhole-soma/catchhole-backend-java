package org.monitoring.catchholebackend.global.config.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.Query;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PostgreSQL 의견 안내 조회 인덱스")
class FeedbackPromptMigrationIntegrationTest {
    @Container
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_USER", "migration_test")
            .withEnv("POSTGRES_PASSWORD", "migration-test-only")
            .withEnv("POSTGRES_DB", "feedback_prompt")
            .withExposedPorts(5432)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(
                    ".*database system is ready to accept connections.*\\n", 2));

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost()
                + ":" + POSTGRES.getMappedPort(5432) + "/feedback_prompt");
        registry.add("spring.datasource.username", () -> "migration_test");
        registry.add("spring.datasource.password", () -> "migration-test-only");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Test
    @DisplayName("전체 migration 뒤 10만 회차에서도 인덱스를 사용해 세 행에서 자격 조회를 멈춘다")
    void boundsLookupWithEligibilityIndexes() throws Exception {
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/feedback_prompt";
        Flyway flyway = Flyway.configure().dataSource(url, "migration_test", "migration-test-only")
                .locations("classpath:db/migration").load();
        flyway.migrate();
        flyway.validate();
        try (var connection = DriverManager.getConnection(url, "migration_test", "migration-test-only");
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT indexname FROM pg_indexes WHERE schemaname='public'")) {
                var indexes = new ArrayList<String>();
                while (rows.next()) indexes.add(rows.getString(1));
                assertThat(indexes).contains("idx_works_active_member", "idx_episodes_non_archived_work");
            }
            statement.executeUpdate("""
                    INSERT INTO members (id,email,password_hash,phone_verified,display_name,status,role,created_at,updated_at)
                    SELECT n,'prompt-' || n || '@example.com','hash',false,'작가','ACTIVE','AUTHOR',now(),now()
                    FROM generate_series(1,2000) n
                    """);
            statement.executeUpdate("""
                    INSERT INTO works (id,member_id,title,genre,latest_episode_no,created_at,updated_at)
                    SELECT md5('work-' || n)::uuid,n,'작품','FANTASY',50,now(),now() FROM generate_series(1,2000) n
                    """);
            statement.executeUpdate("""
                    INSERT INTO episodes (id,work_id,episode_no,char_count,status,created_at,updated_at,content_updated_at)
                    SELECT md5('episode-' || n)::uuid,md5('work-' || ((n-1)/50+1))::uuid,
                           (n-1)%50+1,100,'UPLOADED',now(),now(),now() FROM generate_series(1,100000) n
                    """);
            statement.execute("ANALYZE works");
            statement.execute("ANALYZE episodes");
            String sql = EpisodeRepository.class
                    .getMethod("existsAtLeastThreePromptEligibleEpisodes", Long.class)
                    .getAnnotation(Query.class).value().replace(":memberId", "1");
            try (var rows = statement.executeQuery("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql)) {
                assertThat(rows.next()).isTrue();
                List<JsonNode> nodes = new ArrayList<>();
                collectPlans(new ObjectMapper().readTree(rows.getString(1)).get(0).path("Plan"), nodes);
                assertThat(nodes).extracting(node -> node.path("Index Name").asText())
                        .contains("idx_works_active_member", "idx_episodes_non_archived_work");
                assertThat(nodes).anySatisfy(node -> {
                    assertThat(node.path("Node Type").asText()).isEqualTo("Limit");
                    assertThat(node.path("Actual Rows").asInt()).isEqualTo(3);
                    // 집계 입력과 하위 조인은 모두 최대 세 행만 반환해야 한다.
                    assertThat(node.path("Plans").get(0).path("Actual Rows").asInt()).isEqualTo(3);
                });
            }
        }
    }

    private static void collectPlans(JsonNode node, List<JsonNode> nodes) {
        nodes.add(node);
        for (JsonNode child : node.path("Plans")) collectPlans(child, nodes);
    }
}
