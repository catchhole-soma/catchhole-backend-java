package org.monitoring.catchholebackend.domain.upload.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Uses a new disposable database; exercises all Flyway migrations, Hibernate validation and real worker claims. */
@SpringBootTest(properties = {"internal.api-key=test-internal-api-key", "spring.config.import="})
@Testcontainers(disabledWithoutDocker = true)
class SettingBookAnalysisPostgresIntegrationTest extends SettingBookAnalysisIntegrationTest {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_DB", "gh221_migration_test")
            .withEnv("POSTGRES_USER", "gh221_test")
            .withEnv("POSTGRES_PASSWORD", "gh221-disposable-test-only")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(1)));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> "jdbc:postgresql://"+POSTGRES.getHost()+":"+POSTGRES.getMappedPort(5432)+"/gh221_migration_test");
        p.add("spring.datasource.username", () -> "gh221_test");
        p.add("spring.datasource.password", () -> "gh221-disposable-test-only");
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        p.add("spring.flyway.enabled", () -> "true");
        p.add("spring.docker.compose.enabled", () -> "false");
    }

    @Test void migratedSchemaRetainsEpisodeConstraintsAndRejectsMalformedBookRuns() throws Exception {
        var book=upload("하린은 기사다.");
        UUID bookJob=UUID.fromString(book.path("latestAnalysisJob").path("id").asText());
        var ep=Episode.create(work,null,1,"1화","works/test/1.txt","v1","d".repeat(64),10);em.persist(ep);
        var episodeJob=AnalysisJob.create(work,null,ep,AnalysisJobType.SETTING_EXTRACTION);
        states.initializeRun(List.of(episodeJob));em.flush();
        assertThat(sql.queryForObject("select count(*) from flyway_schema_history where version='77' and success",Integer.class)).isEqualTo(1);
        rejectUpdate(bookJob,"source_kind='EPISODE', source_setting_book_id=null, source_setting_book_revision_id=null");
        rejectUpdate(bookJob,"review_mode='MANUAL'");
        rejectUpdate(bookJob,"run_sequence=1, predecessor_job_id='"+episodeJob.getId()+"', run_base_state=null");
        rejectUpdate(bookJob,"source_episode_no=1");
        rejectUpdate(episodeJob.getId(),"source_episode_no=null");
        rejectUpdate(episodeJob.getId(),"episode_id=null");
        assertThat(sql.queryForObject("select source_kind from analysis_jobs where id=?",String.class,bookJob)).isEqualTo("SETTING_BOOK");
    }

    private void rejectUpdate(UUID id,String assignment) {
        sql.execute((ConnectionCallback<Void>) c -> {
            var savepoint=c.setSavepoint();
            try (var statement=c.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate("update analysis_jobs set "+assignment+" where id='"+id+"'"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("check constraint");
            } finally { c.rollback(savepoint); c.releaseSavepoint(savepoint); }
            return null;
        });
    }
}
