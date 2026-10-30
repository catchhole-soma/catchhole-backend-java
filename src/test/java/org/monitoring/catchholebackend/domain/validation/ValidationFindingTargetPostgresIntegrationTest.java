package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.transaction.BeforeTransaction;

/**
 * #314 B2 V102를 실제 PostgreSQL에서 확인한다. 상위 H2 테스트 메서드(대상·설정명·필터 JPQL·정정 연결)를 그대로 상속하고,
 * V102 Flyway·JPA validate·CHECK 제약과 V102 이전 행 백필 SQL을 실행한다. 상위 클래스는 테스트 트랜잭션 안에서 실행되므로
 * 트랜잭션 시작 전에 합성 작품·회원 그래프를 지운다(Worker claim은 DB 전체의 대기 Job을 고른다).
 * Destructive fixture cleanup is guarded to a new disposable loopback DB, never .env/shared DB.
 */
// chat.recovery-delay-ms: 이 클래스와 무관한 채팅 복구 scheduler가 fixture TRUNCATE와 겹쳐 읽지 않도록 Context 시작 1회만 돌게 한다.
@SpringBootTest(properties={"spring.config.import=","spring.docker.compose.enabled=false","chat.recovery-delay-ms=3600000"})
@EnabledIfEnvironmentVariable(named="GH310_EXECUTION_POSTGRES_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gh310_execution_test")
@DisplayName("격리 PostgreSQL의 검수 finding 대상 컬럼(V102)·백필·필터")
class ValidationFindingTargetPostgresIntegrationTest extends ValidationFindingTargetIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->System.getenv("GH310_EXECUTION_POSTGRES_JDBC_URL"));p.add("spring.datasource.username",()->"gh310");p.add("spring.datasource.password",()->"gh310-disposable-only");
        p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");p.add("spring.jpa.hibernate.ddl-auto",()->"validate");p.add("spring.flyway.enabled",()->"true");
    }
    @Autowired JdbcTemplate jdbc;
    @BeforeTransaction void clean() {org.monitoring.catchholebackend.support.PostgresFixtureCleanup.truncate(jdbc,"TRUNCATE TABLE works,members CASCADE");}

    @Test @DisplayName("V102는 nullable 컬럼과 CHECK를 더하고 백필은 V102 이전 행의 등급·대상을 쓰기 경로와 같게 채우며 설정명은 비워 둔다")
    void flywayV102BackfillsLegacyRows() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='102' and success",Integer.class)).isEqualTo(1);
        var report=completedWith(List.of(new Question(List.of("S1"),"age","CAUTION"),new Question(List.of("S2"),"서식지","SEVERE"),new Question(List.of(),"age","REFERENCE")));
        em.flush();
        var written=columns(report);
        assertThat(written).hasSize(5);
        // V102 이전 행처럼 새 컬럼을 비운 뒤 migration의 백필 SQL만 다시 실행한다(멱등: NULL인 행만 채운다).
        jdbc.update("update validation_findings set impact=null,subject_type=null,subject_id=null,subject_name=null,setting_key=null,setting_name=null where report_id=?",report);
        String sql=new ClassPathResource("db/migration/V102__add_validation_finding_targets.sql").getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(sql.substring(sql.indexOf("-- B2 backfill")));
        var backfilled=columns(report);
        for(var row:written.entrySet()) {
            var expected=new ArrayList<>(row.getValue());expected.set(4,null);expected.set(5,null);
            assertThat(backfilled.get(row.getKey())).as(row.getKey()).isEqualTo(expected);
        }
        assertThat(backfilled.get("Q1").subList(0,4)).containsExactly("CAUTION","CHARACTER",character.getId().toString(),"합성 인물");
        assertThat(backfilled.get("Q2").subList(0,4)).containsExactly("SEVERE","WORLD_SETTING",world.getId().toString(),"합성 종족");
        assertThat(backfilled.get("Q3").subList(0,4)).as("no subject_refs and no BASELINE entry").containsExactly("REFERENCE",null,null,null);
        assertThat(written.get("Q1").get(4)).as("the write path sets the key the backfill leaves null").isEqualTo("AGE:age");
        em.clear();
        assertThat(findings(report,"?subjectType=WORLD_SETTING&impact=SEVERE").path("content").path(0).path("unitRef").asText()).isEqualTo("Q2");
        rejected("update validation_findings set impact='HIGH' where report_id=?",report);
        rejected("update validation_findings set impact='SEVERE' where report_id=? and branch='TYPO'",report);
        jdbc.update("update validation_findings set subject_type='CHARACTER',setting_key='AGE:age',setting_name='나이' where report_id=? and unit_ref='Q1'",report);
        rejected("update validation_findings set subject_type=null,subject_id=null,subject_name=null where report_id=? and unit_ref='Q1'",report);
        rejected("update validation_findings set setting_name=null where report_id=? and unit_ref='Q1'",report);
    }
    /** CHECK 위반은 테스트 트랜잭션을 abort하므로 savepoint 안에서 확인하고 되돌린다. */
    void rejected(String sql,UUID report) {
        jdbc.execute("savepoint b2_check");
        assertThatThrownBy(()->jdbc.update(sql,report)).as(sql).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.execute("rollback to savepoint b2_check");
    }

    /** unitRef(TYPO는 TYPO:start) → [impact, subject_type, subject_id, subject_name, setting_key, setting_name]. */
    Map<String,List<Object>> columns(UUID report) {
        var result=new TreeMap<String,List<Object>>();
        jdbc.query("select unit_ref,payload->'span'->>'start' as typo_start,impact,subject_type,subject_id::text,subject_name,setting_key,setting_name from validation_findings where report_id=?",rs->{
            String key=rs.getString("typo_start")==null?rs.getString("unit_ref"):"TYPO:"+rs.getString("typo_start");
            result.put(key,Arrays.asList(rs.getString("impact"),rs.getString("subject_type"),rs.getString("subject_id"),rs.getString("subject_name"),rs.getString("setting_key"),rs.getString("setting_name")));
        },report);
        return result;
    }
}
