package org.monitoring.catchholebackend.domain.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodePurgeDataRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("검색 청크 전체 migration·JPA 매핑·원문 생명주기")
class RetrievalIndexPostgresIntegrationTest {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_USER", "gh239").withEnv("POSTGRES_PASSWORD", "test-only")
            .withEnv("POSTGRES_DB", "gh239_test").withExposedPorts(5432)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(
                    ".*database system is ready to accept connections.*\\n", 2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/gh239_test");
        r.add("spring.datasource.username", () -> "gh239");
        r.add("spring.datasource.password", () -> "test-only");
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EpisodePurgeDataRepository purge;
    UUID episode;
    UUID work;

    @BeforeEach
    void source() {
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            var member = Member.register(UUID.randomUUID() + "@test.invalid", "test", null, "검증 작가");
            em.persist(member);
            var w = Work.create(member, "합성 원고", WorkGenre.FANTASY, null);
            em.persist(w);
            work = w.getId();
            var e = Episode.create(w, null, 1, null, "synthetic-source", "v1", "a".repeat(64), 2);
            em.persist(e);
            episode = e.getId();
        });
        jdbc.update("""
            INSERT INTO retrieval_sources VALUES (?,?,'synthetic-source','v1',?,'cl100k-800-100-boundary-v1',1,localtimestamp)
            """, episode, work, "a".repeat(64));
        jdbc.update("""
            INSERT INTO retrieval_chunks(id,episode_id,chunk_index,chunk_text,start_offset,end_offset,chunk_hash,
                token_count,embedding,embedding_model,embedding_version,embedded_at,status)
            VALUES (?,?,0,'합성',0,2,?,2,cast(? as vector),'text-embedding-3-small','v1',localtimestamp,'READY')
            """, UUID.randomUUID(), episode, "b".repeat(64), "[" + String.join(",", Collections.nCopies(1536, "1")) + "]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"hash", "key", "version", "archive", "purge", "work"})
    @DisplayName("원문이 변경되거나 파기 중이면 물리 벡터가 남아도 조회 대상에서 제외한다")
    void invalidSourceNeverAppears(String mutation) {
        assertThat(count("retrieval_current_chunks")).isEqualTo(1);
        switch (mutation) {
            case "hash" -> jdbc.update("UPDATE episodes SET content_hash=? WHERE id=?", "c".repeat(64), episode);
            case "key" -> jdbc.update("UPDATE episodes SET content_s3_key='changed' WHERE id=?", episode);
            case "version" -> jdbc.update("UPDATE episodes SET content_s3_version='changed' WHERE id=?", episode);
            case "archive" -> jdbc.update("UPDATE episodes SET status='ARCHIVED' WHERE id=?", episode);
            case "work" -> jdbc.update("UPDATE works SET lifecycle_status='PURGING' WHERE id=?", work);
            case "purge" -> jdbc.update("""
                INSERT INTO episode_source_purge_requests(id,episode_id,work_id,previous_episode_no,
                   retained_content_key,status,requested_at,attempt_count,created_at,updated_at)
                VALUES (?,?,?,1,'synthetic-source','REQUESTED',localtimestamp,0,localtimestamp,localtimestamp)
                """, UUID.randomUUID(), episode, work);
        }
        assertThat(count("retrieval_chunks")).isEqualTo(1);
        assertThat(count("retrieval_current_chunks")).isZero();
    }

    @Test
    @DisplayName("회차 파기는 새 검색 청크의 원문·벡터와 manifest도 물리 삭제한다")
    void sourcePurgeDeletesSearchTextAndVectors() {
        new TransactionTemplate(transactions).executeWithoutResult(s -> purge.deleteChunks(episode));
        assertThat(count("retrieval_chunks")).isZero();
        assertThat(count("retrieval_sources")).isZero();
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success=true", String.class)).contains("76");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE episode_id=?", Integer.class, episode);
    }
}
