package org.monitoring.catchholebackend.domain.retrieval.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.entity.EpisodeSourcePurgeRequest;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@DisplayName("PostgreSQL 검색 색인 지연 회차 집계")
class RetrievalIndexLagRepositoryPostgresIntegrationTest {
    @Container
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_DB", "gh422_test")
            .withEnv("POSTGRES_USER", "gh422")
            .withEnv("POSTGRES_PASSWORD", "test-only")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(1)));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost()
                + ":" + POSTGRES.getMappedPort(5432) + "/gh422_test");
        properties.add("spring.datasource.username", () -> "gh422");
        properties.add("spring.datasource.password", () -> "test-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.docker.compose.enabled", () -> "false");
    }

    static final String HASH = "a".repeat(64);
    static final String POLICY = "cl100k-800-100-boundary-v1";
    static final String VECTOR = "[" + String.join(",", Collections.nCopies(1536, "1")) + "]";
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 11, 12, 0);
    static final LocalDateTime CUTOFF = NOW.minusMinutes(60);

    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RetrievalIndexLagRepository repository;
    private int nextEpisode;

    @Test
    @DisplayName("AI 백필의 색인 대상 중 기준 시간보다 오래 READY가 아닌 회차만 작품별로 센다")
    void countsOnlyEligibleEpisodesWithoutACompleteIndex() {
        Work a = work("a", "ACTIVE", "ACTIVE");
        episode(a, NOW.minusHours(3));                                        // 색인 없음
        episode(a, NOW.minusMinutes(30));                                     // 기준 시간 안
        indexed(episode(a, NOW.minusHours(3)), 2, 2, "v1");                    // 완료
        indexed(episode(a, NOW.minusHours(3)), 2, 1, "v1");                    // 조각 하나 MISSING
        Episode stale = episode(a, NOW.minusHours(3));
        source(stale, "c".repeat(64), "v1", POLICY, 1);                       // 이전 원문의 색인
        indexed(episode(a, NOW.minusHours(3)), 2, 2, "v0");                    // 다른 임베딩 버전
        Episode missingChunk = episode(a, NOW.minusHours(3));
        source(missingChunk, HASH, "v1", POLICY, 3);
        chunks(missingChunk, 2, 2, "v1");                                     // 저장 조각 수 부족
        Episode otherPolicy = episode(a, NOW.minusHours(3));
        source(otherPolicy, HASH, "v1", "other-policy", 0);                   // 다른 조각 규칙
        Episode noVersion = episode(a, NOW.minusHours(3));
        jdbc.update("UPDATE episodes SET content_s3_version=NULL WHERE id=?", noVersion.getId());
        source(noVersion, HASH, null, POLICY, 0);                             // 버전 없는 원문·빈 원문 완료

        // 백필이 색인하지 않는 회차
        episode(a, NOW.minusHours(3)).archive();
        Episode purging = episode(a, NOW.minusHours(3));
        em.persist(EpisodeSourcePurgeRequest.requestReplacement(purging, null, "source/new"));
        jdbc.update("UPDATE episodes SET content_hash=? WHERE id=?", "A".repeat(64), episode(a, NOW.minusHours(3)).getId());
        jdbc.update("UPDATE episodes SET content_s3_key='' WHERE id=?", episode(a, NOW.minusHours(3)).getId());
        episode(work("purging-work", "PURGING", "ACTIVE"), NOW.minusHours(9));
        episode(work("suspended", "ACTIVE", "SUSPENDED"), NOW.minusHours(9));

        Work b = work("b", "ACTIVE", "ACTIVE");
        episode(b, NOW.minusHours(4));
        episode(b, NOW.minusHours(4));
        Work c = work("c", "ACTIVE", "ACTIVE");
        episode(c, NOW.minusHours(5));
        Work d = work("d", "ACTIVE", "ACTIVE");
        episode(d, NOW.minusHours(2));
        em.flush();

        var snapshot = repository.read(CUTOFF, "v1", 3);

        assertThat(snapshot.episodes()).isEqualTo(10);
        assertThat(snapshot.works()).isEqualTo(4);
        assertThat(snapshot.oldestContentUpdatedAt()).isEqualTo(NOW.minusHours(5));
        // 회차 수가 같으면 더 오래 기다린 작품이 먼저다.
        assertThat(snapshot.topWorkIds()).containsExactly(a.getId(), b.getId(), c.getId());
    }

    @Test
    @DisplayName("지연 회차가 없으면 0과 빈 목록을 돌려준다")
    void returnsZeroWithoutLag() {
        Work a = work("zero", "ACTIVE", "ACTIVE");
        indexed(episode(a, NOW.minusHours(3)), 1, 1, "v1");
        episode(a, NOW.minusMinutes(10));
        em.flush();

        var snapshot = repository.read(CUTOFF, "v1", 3);

        assertThat(snapshot.episodes()).isZero();
        assertThat(snapshot.works()).isZero();
        assertThat(snapshot.oldestContentUpdatedAt()).isNull();
        assertThat(snapshot.topWorkIds()).isEqualTo(List.of());
    }

    private Work work(String suffix, String lifecycle, String memberStatus) {
        Member member = Member.register(suffix + "-" + UUID.randomUUID() + "@test.invalid", "test-password", null, "작가");
        em.persist(member);
        Work work = Work.create(member, "합성 작품", WorkGenre.FANTASY, null);
        em.persist(work);
        em.flush();
        jdbc.update("UPDATE members SET status=? WHERE id=?", memberStatus, member.getId());
        jdbc.update("UPDATE works SET lifecycle_status=? WHERE id=?", lifecycle, work.getId());
        return work;
    }

    private Episode episode(Work work, LocalDateTime contentUpdatedAt) {
        int no = ++nextEpisode;
        Episode episode = Episode.create(work, null, no, null, "source/" + no, "v1", HASH, 100);
        em.persist(episode);
        em.flush();
        jdbc.update("UPDATE episodes SET content_updated_at=? WHERE id=?", contentUpdatedAt, episode.getId());
        return episode;
    }

    private Episode indexed(Episode episode, int chunkCount, int ready, String version) {
        source(episode, HASH, "v1", POLICY, chunkCount);
        chunks(episode, chunkCount, ready, version);
        return episode;
    }

    private void source(Episode episode, String hash, String version, String policy, int chunkCount) {
        jdbc.update("INSERT INTO retrieval_sources VALUES (?,?,?,?,?,?,?,localtimestamp)", episode.getId(),
                episode.getWork().getId(), episode.getContentS3Key(), version, hash, policy, chunkCount);
    }

    private void chunks(Episode episode, int stored, int ready, String version) {
        for (int i = 0; i < stored; i++) {
            if (i < ready) {
                jdbc.update("""
                        INSERT INTO retrieval_chunks(id,episode_id,chunk_index,chunk_text,start_offset,end_offset,chunk_hash,
                            token_count,embedding,embedding_model,embedding_version,embedded_at,status)
                        VALUES (?,?,?,'합성',0,2,?,2,cast(? as vector),'text-embedding-3-small',?,localtimestamp,'READY')
                        """, UUID.randomUUID(), episode.getId(), i, "b".repeat(64), VECTOR, version);
            } else {
                jdbc.update("""
                        INSERT INTO retrieval_chunks(id,episode_id,chunk_index,chunk_text,start_offset,end_offset,chunk_hash,
                            token_count,status) VALUES (?,?,?,'합성',0,2,?,2,'MISSING')
                        """, UUID.randomUUID(), episode.getId(), i, "b".repeat(64));
            }
        }
    }
}
