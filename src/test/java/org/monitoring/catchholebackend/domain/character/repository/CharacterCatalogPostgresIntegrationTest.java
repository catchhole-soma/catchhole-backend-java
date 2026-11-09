package org.monitoring.catchholebackend.domain.character.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.processor.LikePatternEscaper;
import org.monitoring.catchholebackend.domain.character.service.CharacterService;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PostgreSQL 캐릭터 누적 집계와 경량 목록")
class CharacterCatalogPostgresIntegrationTest {
    @Container static final GenericContainer<?> PG = new GenericContainer<>("pgvector/pgvector:0.8.2-pg16")
            .withEnv("POSTGRES_USER", "catalog_test").withEnv("POSTGRES_PASSWORD", "test-only")
            .withEnv("POSTGRES_DB", "catalog_test").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/catalog_test");
        p.add("spring.datasource.username", () -> "catalog_test");
        p.add("spring.datasource.password", () -> "test-only");
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        p.add("spring.flyway.enabled", () -> "true");
        p.add("spring.docker.compose.enabled", () -> "false");
        p.add("spring.config.import", () -> "");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired CharacterCardQueryRepository cards;
    @Autowired CharacterService service;
    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager transactions;
    UUID work;
    long member;

    @BeforeEach void seed() {
        work = UUID.randomUUID();
        member = jdbc.queryForObject("""
                insert into members(email,password_hash,phone_number,phone_verified,display_name,status,role,created_at,updated_at)
                values (?,'test-only',?,true,'테스트','ACTIVE','AUTHOR',now(),now()) returning id
                """, Long.class, work + "@example.invalid", work.toString().substring(0, 20));
        jdbc.update("insert into works(id,member_id,title,genre,latest_episode_no,created_at,updated_at) values (?,?,'합성 작품','FANTASY',0,now(),now())",work,member);
    }
    UUID character(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into characters(id,work_id,name,status,created_at,updated_at) values (?, ?, ?, 'ACTIVE',now(),now())",id,work,name);
        return id;
    }
    void facts(UUID character, int n) {
        jdbc.update("""
                insert into character_facts(id,character_id,fact_type,fact_key,fact_value,created_at,updated_at)
                select gen_random_uuid(),?,'PROFILE','profile.history','기록 '||g,now(),now() from generate_series(1,?) g
                """,character,n);
    }
    long count(UUID character) { return jdbc.queryForObject("select record_count from characters where id=?",Long.class,character); }
    void alias(UUID character,String name,String review) {
        jdbc.update("""
                insert into setting_candidates(id,work_id,entity_type,entity_name,matched_character_id,match_status,review_status,candidate_kind,created_at,updated_at)
                values (gen_random_uuid(),?,'CHARACTER',?,?,'MATCHED',?,'CHARACTER_DISCOVERY',now(),now())
                """,work,name,character,review);
    }

    @Test @DisplayName("벌크 추가·동시 추가·멱등 재시도·수정·이동·삭제·롤백에 집계가 일치한다")
    void countsFollowCommittedRecords() throws Exception {
        UUID a=character("유비"), b=character("조조"); facts(a,3);
        assertThat(count(a)).isEqualTo(3);
        jdbc.update("insert into character_facts select * from character_facts where character_id=? on conflict(id) do nothing",a);
        jdbc.update("update character_facts set fact_value='수정' where character_id=?",a);
        assertThat(count(a)).isEqualTo(3);
        var tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> { facts(a,4); throw new IllegalStateException("rollback"); })).isInstanceOf(IllegalStateException.class);
        assertThat(count(a)).isEqualTo(3);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var x=pool.submit(() -> facts(a,10)); var y=pool.submit(() -> facts(a,10)); x.get(); y.get();
        }
        assertThat(count(a)).isEqualTo(23);
        jdbc.update("update character_facts set character_id=? where character_id=?",b,a);
        assertThat(count(a)).isZero(); assertThat(count(b)).isEqualTo(23);
        jdbc.update("delete from character_facts where character_id=?",b);
        assertThat(count(b)).isZero();
        facts(a,2);
        // 실제 작품 파기는 Fact를 먼저 삭제한 뒤 인물을 삭제한다.
        jdbc.update("delete from character_facts where character_id=?",a);
        jdbc.update("delete from characters where id=?",a);
        assertThat(jdbc.queryForObject("select count(*) from character_facts where character_id=?",Long.class,a)).isZero();
    }

    @Test @DisplayName("별칭은 확정 연결만 검색하고 중복 별칭·특수문자·보관 상태를 처리한다")
    void searchesConfirmedAliasesAndPagesWithoutEntityPayloads() {
        UUID a=character("조조"), b=character("조조의 동료"), pending=character("무명");
        facts(a,9); facts(b,3); alias(a,"맹덕","CONFIRMED"); alias(a,"맹덕","CONFIRMED");
        alias(pending,"맹덕","PENDING_REVIEW"); alias(a,"별%_\\칭","CONFIRMED");
        assertThat(cards.findPage(work,CharacterStatus.ACTIVE,"맹덕",PageRequest.of(0,1)).getTotalElements()).isEqualTo(1);
        for (String q : new String[]{"%","_","\\"}) {
            assertThat(cards.findPage(work,CharacterStatus.ACTIVE,LikePatternEscaper.escape(q),PageRequest.of(0,24)).getContent())
                    .extracting(CharacterCardRow::id).containsExactly(a);
        }
        jdbc.update("""
                insert into character_images(character_id,catalog_id,selection_source,version,created_at,updated_at)
                values (?,'race-elf','MANUAL',1,now(),now())
                """, a);
        var stats=emf.unwrap(SessionFactory.class).getStatistics(); stats.setStatisticsEnabled(true); stats.clear();
        var page=service.getCharacters(member,work,"조조",0,1);
        assertThat(page.content().getFirst().image().catalogId()).isEqualTo("race-elf");
        assertThat(page.content()).hasSize(1); assertThat(page.content().getFirst().recordCount()).isEqualTo(9);
        assertThat(stats.getEntityStatistics(WorkCharacter.class.getName()).getLoadCount()).isZero();
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(4);
        assertThat(cards.findPage(work,CharacterStatus.ACTIVE,"조조",PageRequest.of(1,1)).getContent()).extracting(CharacterCardRow::id).containsExactly(b);
        jdbc.update("update characters set status='ARCHIVED' where id=?",a);
        assertThat(cards.findPage(work,CharacterStatus.ACTIVE,"맹덕",PageRequest.of(0,24))).isEmpty();
        assertThat(cards.findPage(UUID.randomUUID(),CharacterStatus.ACTIVE,"",PageRequest.of(0,24))).isEmpty();
    }

    @Test @DisplayName("기존 이력은 마이그레이션에서 초기 집계되며 이후 트리거와 연결된다")
    void backfillsExistingHistory() throws Exception {
        UUID a=character("이전 인물");
        String migration=Files.readString(Path.of("src/main/resources/db/migration/V121__add_character_record_count.sql"));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("drop function increment_character_record_counts() cascade; drop function decrement_character_record_counts() cascade; drop function move_character_record_counts() cascade; alter table characters drop column record_count cascade; drop index idx_character_discovery_alias_lookup;");
            facts(a,7); jdbc.execute(migration); assertThat(count(a)).isEqualTo(7);
            facts(a,2); assertThat(count(a)).isEqualTo(9); status.setRollbackOnly();
        });
        assertThat(count(a)).isZero();
    }

    @Test @DisplayName("합성 인물 2천명·이력 20만건에서도 정렬은 집계 인덱스와 카드 페이지로 수행한다")
    void usesRankIndexWithLargeHistory() {
        jdbc.update("""
                insert into characters(id,work_id,name,status,profile_json,created_at,updated_at)
                select gen_random_uuid(),?,'인물 '||g,'ACTIVE',jsonb_build_object('large',repeat('x',10000)),now(),now() from generate_series(1,2000) g
                """,work);
        jdbc.update("""
                insert into character_facts(id,character_id,fact_type,fact_key,fact_value,created_at,updated_at)
                select gen_random_uuid(),c.id,'PROFILE','profile.history','기록 '||g,now(),now() from characters c cross join generate_series(1,100) g where c.work_id=?
                """,work);
        jdbc.execute("analyze characters");
        String plan=String.join("\n",jdbc.queryForList("explain (analyze,buffers) select id,name,record_count from characters where work_id=? and status='ACTIVE' order by record_count desc,id desc limit 24",String.class,work));
        assertThat(plan).contains("idx_characters_work_status_records_id").doesNotContain("character_facts").doesNotContain("Sort Key");
        var stats=emf.unwrap(SessionFactory.class).getStatistics(); stats.setStatisticsEnabled(true); stats.clear();
        var page=service.getCharacters(member,work,"인물",0,24);
        assertThat(page.content()).hasSize(24); assertThat(page.totalElements()).isEqualTo(2000);
        assertThat(page.content()).allMatch(c -> c.recordCount()==100);
        assertThat(stats.getEntityStatistics(WorkCharacter.class.getName()).getLoadCount()).isZero();
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(4);
        System.out.println("GH410 synthetic plan (2000 characters / 200000 facts):\n"+plan);
    }
}
