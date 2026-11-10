package org.monitoring.catchholebackend.domain.relationship.service;

import org.springframework.test.annotation.DirtiesContext;
import static org.assertj.core.api.Assertions.*;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
class EpisodeRelationshipGraphPostgresIntegrationTest
        extends EpisodeRelationshipGraphIntegrationTest {
    @Autowired RelationshipExplorerService explorer;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate explorerJdbc;
    private boolean largeFixture;

    private UUID work() { return (UUID) ReflectionTestUtils.getField(this, "workId"); }
    private Long owner() { return (Long) ReflectionTestUtils.getField(this, "owner"); }

    @AfterEach
    void compareExplorerWithLegacyReadContract() {
        Graph old;
        try { old = service.getRelationshipGraph(owner(), work(), null); }
        catch (AppException error) {
            assertThatThrownBy(() -> explorer.explore(owner(), work(), null, null, 0))
                    .isInstanceOfSatisfying(AppException.class, actual ->
                        assertThat(actual.getResultCode()).isEqualTo(error.getResultCode()));
            return;
        }
        compare(null, old);
        if (largeFixture) return;
        for (EpisodeRef episode : old.availableEpisodes())
            compare(episode.episodeNo(), service.getRelationshipGraph(owner(), work(), episode.episodeNo()));
    }

    private void compare(Integer episode, Graph old) {
        var exploration = explorer.explore(owner(), work(), episode, null, 0);
        var actual = exploration.graph();
        assertThat(actual.selectedEpisode()).isEqualTo(old.selectedEpisode());
        assertThat(actual.availableEpisodes()).isEqualTo(old.availableEpisodes());
        assertThat(actual.totalEdgeCount()).isEqualTo(old.totalEdgeCount());
        assertThat(actual.analysisStatus()).isEqualTo(old.analysisStatus());
        assertThat(actual.partial()).isEqualTo(old.partial());
        assertThat(actual.incompleteEpisodeNos()).isEqualTo(old.incompleteEpisodeNos());
        if (old.totalEdgeCount() < 200 && !exploration.selectionRequired()) {
            assertThat(actual.edges()).containsExactlyInAnyOrderElementsOf(old.edges());
            assertThat(actual.nodes()).containsExactlyInAnyOrderElementsOf(old.nodes());
            if (!old.edges().isEmpty()) {
                UUID edge = old.edges().getFirst().id();
                var expected = service.getRelationshipEdge(owner(), work(), edge, episode);
                var detail = explorer.detail(owner(), work(), edge, episode);
                assertThat(detail.edge()).isEqualTo(expected.edge());
                assertThat(detail.evidence()).containsExactlyInAnyOrderElementsOf(expected.evidence());
            }
        }
    }

    private UUID seedEdges(int count) {
        UUID job = ReflectionTestUtils.invokeMethod(this, "job", 1);
        Context context = ReflectionTestUtils.invokeMethod(this, "prepare", job);
        Decision decision = ReflectionTestUtils.invokeMethod(this, "add", context, "O1", Kind.COMPANIONSHIP, "함께 여행한다");
        ReflectionTestUtils.invokeMethod(this, "apply", job, context, new Decision[]{decision});
        Graph graph = service.getRelationshipGraph(owner(), work(), null);
        UUID center = graph.edges().getFirst().sourceNodeId();
        UUID template = graph.edges().getFirst().id();
        for (int n = 1; n < count; n++) {
            UUID node = UUID.randomUUID(), edge = UUID.randomUUID();
            jdbc.update("INSERT INTO relationship_graph_nodes(id,work_id,name,first_episode_no,created_at,updated_at) VALUES (?,?,?,1,now(),now())", node, work(), "인물" + n);
            jdbc.update("INSERT INTO relationship_graph_edges(id,work_id,source_node_id,target_node_id,axis,kind,canonical_key,version,suppressed,created_at,updated_at) SELECT ?,work_id,?,? ,axis,kind,?,0,false,now(),now() FROM relationship_graph_edges WHERE id=?", edge, center, node, edge.toString(), template);
            jdbc.update("INSERT INTO relationship_graph_events(id,work_id,analysis_id,edge_id,episode_id,episode_no,source_name,target_name,label,summary,direction,status,evidence,created_at,updated_at) SELECT ?,work_id,analysis_id,?,episode_id,episode_no,source_name,?,label,summary,direction,status,evidence,now(),now() FROM relationship_graph_events WHERE edge_id=?", UUID.randomUUID(), edge, "인물" + n, template);
        }
        return center;
    }

    @Test @DisplayName("관계 300개 또는 인물 100명부터 선택하며 전체 검색과 페이지에 누락이 없다")
    void explorerBoundarySearchAndPages() {
        UUID center = seedEdges(300);
        var large = explorer.explore(owner(), work(), null, null, 0);
        assertThat(large.selectionRequired()).isTrue();
        assertThat(large.graph().edges()).isEmpty();
        assertThat(large.totalNodeCount()).isEqualTo(301);
        assertThat(large.suggestedPeople().getFirst().node().id()).isEqualTo(center);
        assertThat(large.suggestedPeople().getFirst().neighborCount()).isEqualTo(300);
        assertThat(explorer.people(owner(), work(), null, "인물299", 0, 20).content()).hasSize(1);
        assertThat(explorer.people(owner(), work(), null, "%", 0, 20).content()).isEmpty();
        Set<UUID> found = new HashSet<>();
        for (int page = 0; page < 3; page++) {
            var slice = explorer.explore(owner(), work(), null, center, page);
            assertThat(slice.graph().edges()).hasSize(100);
            assertThat(slice.hasNext()).isEqualTo(page < 2);
            slice.graph().edges().forEach(e -> assertThat(found.add(e.id())).isTrue());
        }
        assertThat(found).hasSize(300);
        jdbc.update("UPDATE relationship_graph_edges SET suppressed=true WHERE id=?", found.iterator().next());
        assertThat(explorer.explore(owner(),work(),null,null,0).selectionRequired()).isTrue();
        // Collapse alias nodes onto 40 distinct people: 299 edges with fewer than 100 people is an overview.
        var leaves = jdbc.queryForList("SELECT id FROM relationship_graph_nodes WHERE work_id=? AND id<>? ORDER BY id",UUID.class,work(),center);
        for (int i=40;i<leaves.size();i++) jdbc.update("UPDATE relationship_graph_nodes SET merged_into_node_id=?,merged_at_episode_no=1 WHERE id=?",leaves.get(i%40),leaves.get(i));
        var small = explorer.explore(owner(), work(), null, null, 0);
        assertThat(small.totalNodeCount()).isLessThan(100);
        assertThat(small.selectionRequired()).isFalse();
        assertThat(small.graph().edges()).hasSize(299);
        assertThatThrownBy(() -> explorer.explore((Long)ReflectionTestUtils.getField(this,"other"),work(),null,null,0)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> explorer.explore(owner(),work(),null,UUID.randomUUID(),0)).isInstanceOf(AppException.class);
    }

    @Test @DisplayName("198회차와 3129개 관계의 합성 자료에서 비용과 응답 크기를 비교한다")
    void largeFixtureReadCost() throws Exception {
        largeFixture = true;
        UUID center = seedEdges(3129);
        for (int n = 2; n <= 198; n++) ReflectionTestUtils.invokeMethod(this, "job", n);
        jdbc.update("UPDATE relationship_graph_analyses SET status='APPLIED', context=jsonb_build_object('padding',repeat(md5(id::text),1250)) WHERE work_id=?", work());
        var statistics = em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        List<String> measurements = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            statistics.clear();
            long before = System.nanoTime();
            Graph legacy = service.getRelationshipGraph(owner(), work(), null);
            double legacyMs = (System.nanoTime() - before) / 1_000_000.0;
            long legacyQueries = statistics.getPrepareStatementCount();
            org.mockito.Mockito.clearInvocations(explorerJdbc);
            statistics.clear();
            before = System.nanoTime();
            var overview = explorer.explore(owner(), work(), null, null, 0);
            double overviewMs = (System.nanoTime() - before) / 1_000_000.0;
            long overviewQueries = statistics.getPrepareStatementCount() + nativeQueryUpperBound();
            assertThat(overviewQueries).isLessThanOrEqualTo(9);
            org.mockito.Mockito.clearInvocations(explorerJdbc);
            statistics.clear();
            before = System.nanoTime();
            var focused = explorer.explore(owner(), work(), null, center, 0);
            double focusMs = (System.nanoTime() - before) / 1_000_000.0;
            long focusQueries = statistics.getPrepareStatementCount() + nativeQueryUpperBound();
            assertThat(focusQueries).isLessThanOrEqualTo(10);
            before = System.nanoTime();
            var search = explorer.people(owner(), work(), null, "인물2999", 0, 20);
            double searchMs = (System.nanoTime() - before) / 1_000_000.0;
            assertThat(search.content()).hasSize(1);
            if (i >= 2) measurements.add(String.format(java.util.Locale.ROOT,
                "legacy_ms=%.2f overview_ms=%.2f focus_ms=%.2f search_ms=%.2f legacy_queries=%d overview_query_bound=%d focus_query_bound=%d legacy_bytes=%d overview_bytes=%d focus_bytes=%d",
                legacyMs, overviewMs, focusMs, searchMs, legacyQueries, overviewQueries, focusQueries,
                json.writeValueAsBytes(legacy).length, json.writeValueAsBytes(overview).length, json.writeValueAsBytes(focused).length));
        }
        statistics.setStatisticsEnabled(false);
        System.out.println("GH412_SYNTHETIC_198_EPISODES_3129_EDGES\n" + String.join("\n", measurements));
        String query = new org.springframework.core.io.ClassPathResource("queries/relationship/explorer.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("workId",work()).addValue("cutoff",198);
        var plan = explorerJdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + query + "SELECT * FROM people ORDER BY neighbor_count DESC,name,id LIMIT 8",params,String.class);
        System.out.println("GH412_QUERY_PLAN\n" + String.join("\n",plan));
    }

    /** Includes the Class overload delegating to a RowMapper overload: a conservative statement bound. */
    private long nativeQueryUpperBound() {
        return org.mockito.Mockito.mockingDetails(explorerJdbc).getInvocations().stream()
            .filter(i -> (i.getMethod().getName().equals("query") || i.getMethod().getName().equals("queryForObject"))
                && i.getArguments().length == 3 && (i.getArgument(2) instanceof org.springframework.jdbc.core.RowMapper<?>
                    || i.getArgument(2) instanceof org.springframework.jdbc.core.RowCallbackHandler || i.getArgument(2) instanceof Class<?>)).count();
    }

    @Test @DisplayName("실제 MVC·PostgreSQL 탐색 응답과 접근 경계를 브라우저 검증용으로 내보낸다")
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="GH412_FIXTURE_OUTPUT", matches="/tmp/gh412-explorer-contract.json")
    void exportExplorerHttpContract() throws Exception {
        UUID center = seedEdges(300);
        var principal = tx.execute(t -> org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal.from(
            em.find(org.monitoring.catchholebackend.domain.member.entity.Member.class, owner())));
        var auth = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal,"",principal.getAuthorities()));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var fixture = json.createObjectNode();
        fixture.put("workId",work().toString());
        fixture.put("center",center.toString());
        String base = "/api/v1/works/" + work() + "/relationship-graph";
        var overview = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base+"/explore").with(auth))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsByteArray();
        fixture.set("overview",json.readTree(overview));
        var search = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base+"/people").param("query","인물299").with(auth))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsByteArray();
        fixture.set("search",json.readTree(search));
        String person = fixture.path("search").path("data").path("content").get(0).path("node").path("id").asText();
        fixture.put("person",person);
        var focus = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base+"/explore").param("focusNodeId",person).with(auth))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsByteArray();
        fixture.set("focus",json.readTree(focus));
        String edge = fixture.path("focus").path("data").path("graph").path("edges").get(0).path("id").asText();
        fixture.set("detail",json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base+"/explore/edges/"+edge).with(auth))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsByteArray()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base+"/explore"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base+"/explore").param("page","-1").with(auth))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base+"/people").param("episodeNo","999").with(auth))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("GH412_FIXTURE_OUTPUT")),json.writeValueAsString(fixture));
    }

    @Test @DisplayName("확정 별칭은 해당 회차부터 검색되며 원문 교체 후 검색되지 않는다")
    void confirmedAliasSearchRespectsCutoffAndSource() {
        UUID character = ReflectionTestUtils.invokeMethod(this,"character","아라 린");
        UUID node = ReflectionTestUtils.invokeMethod(this,"profileNodeAt",1,"아라 린과 보라는 함께 길을 떠났다.","아라 린","보라",character);
        ReflectionTestUtils.invokeMethod(this,"confirmedAliasAt",2,"작은별은 아라 린의 별명이다.",character,"작은별");
        assertThat(explorer.people(owner(),work(),1,"작은별",0,20).content()).isEmpty();
        UUID third = ReflectionTestUtils.invokeMethod(this,"job",3);
        // Empty successful episode advances the selectable cutoff without introducing another relation.
        jdbc.update("UPDATE relationship_graph_analyses SET status='APPLIED' WHERE id=?",third);
        assertThat(explorer.people(owner(),work(),3,"작은별",0,20).content()).extracting(p -> p.node().id()).containsExactly(node);
        jdbc.update("UPDATE episodes SET content_s3_version='replaced' WHERE work_id=? AND episode_no=2",work());
        assertThat(explorer.people(owner(),work(),3,"작은별",0,20).content()).isEmpty();
    }

    @Test @DisplayName("관계가 300개 미만이어도 인물이 100명 이상이면 선택부터 시작한다")
    void nodeThresholdProtectsSparseLargeGraphs() {
        seedEdges(99);
        var result = explorer.explore(owner(),work(),null,null,0);
        assertThat(result.totalNodeCount()).isEqualTo(100);
        assertThat(result.graph().totalEdgeCount()).isEqualTo(99);
        assertThat(result.selectionRequired()).isTrue();
        UUID edge = jdbc.queryForObject("SELECT id FROM relationship_graph_edges WHERE work_id=? LIMIT 1",UUID.class,work());
        jdbc.update("UPDATE relationship_graph_edges SET suppressed=true WHERE id=?",edge);
        var smaller = explorer.explore(owner(),work(),null,null,0);
        assertThat(smaller.totalNodeCount()).isEqualTo(99);
        assertThat(smaller.selectionRequired()).isFalse();
    }

    @Container
    static final GenericContainer<?> POSTGRES =
            new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
                    .withEnv("POSTGRES_USER", "gh323")
                    .withEnv("POSTGRES_PASSWORD", "test-only")
                    .withEnv("POSTGRES_DB", "gh323_test")
                    .withExposedPorts(5432)
                    .waitingFor(
                            Wait.forLogMessage(
                                    ".*database system is ready to accept connections.*\\n", 2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRES.getHost()
                                + ":"
                                + POSTGRES.getMappedPort(5432)
                                + "/gh323_test");
        properties.add("spring.datasource.username", () -> "gh323");
        properties.add("spring.datasource.password", () -> "test-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.docker.compose.enabled", () -> "false");
    }
}
