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
@org.springframework.boot.test.context.SpringBootTest(webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.config.import=")
@Testcontainers(disabledWithoutDocker = true)
class EpisodeRelationshipGraphPostgresIntegrationTest
        extends EpisodeRelationshipGraphIntegrationTest {
    @Autowired RelationshipExplorerService explorer;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider jwt;
    @org.springframework.boot.test.web.server.LocalServerPort int httpPort;
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
            assertThatThrownBy(() -> explorer.exploreRelationships(owner(), work(), null, null, 0))
                    .isInstanceOfSatisfying(AppException.class, actual ->
                        assertThat(actual.getResultCode()).isEqualTo(error.getResultCode()));
            return;
        }
        compare(null, old);
        var selected = explorer.exploreSelectedRelationships(owner(), work(), null, null, List.of(), 0).graph();
        assertThat(selected.selectedEpisode()).isEqualTo(old.selectedEpisode());
        assertThat(selected.incompleteEpisodeNos()).isEqualTo(old.incompleteEpisodeNos());
        if (largeFixture) return;
        for (EpisodeRef episode : old.availableEpisodes())
            compare(episode.episodeNo(), service.getRelationshipGraph(owner(), work(), episode.episodeNo()));
    }

    private void compare(Integer episode, Graph old) {
        var exploration = explorer.exploreRelationships(owner(), work(), episode, null, 0);
        var actual = exploration.graph();
        var selection = explorer.exploreSelectedRelationships(owner(), work(), episode, null, List.of(), 0);
        assertThat(selection.graph()).isEqualTo(actual);
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
                var detail = explorer.getRelationshipDetail(owner(), work(), edge, episode);
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
        var large = explorer.exploreRelationships(owner(), work(), null, null, 0);
        assertThat(large.selectionRequired()).isTrue();
        assertThat(large.graph().edges()).isEmpty();
        assertThat(large.totalNodeCount()).isEqualTo(301);
        assertThat(large.suggestedPeople().getFirst().node().id()).isEqualTo(center);
        assertThat(large.suggestedPeople().getFirst().neighborCount()).isEqualTo(300);
        assertThat(explorer.searchRelationshipPeople(owner(), work(), null, "인물299", 0, 20).content()).hasSize(1);
        assertThat(explorer.searchRelationshipPeople(owner(), work(), null, "%", 0, 20).content()).isEmpty();
        var firstPeople = explorer.searchRelationshipPeople(owner(), work(), null, "", 0, 20);
        assertThat(firstPeople.totalElements()).isEqualTo(301);
        assertThat(firstPeople.totalPages()).isEqualTo(16);
        assertThat(firstPeople.content()).hasSize(20);
        assertThat(firstPeople.hasNext()).isTrue();
        var lastPeople = explorer.searchRelationshipPeople(owner(), work(), null, "", 15, 20);
        assertThat(lastPeople.content()).hasSize(1);
        assertThat(lastPeople.hasNext()).isFalse();
        var pastPeople = explorer.searchRelationshipPeople(owner(), work(), null, "", 16, 20);
        assertThat(pastPeople.content()).isEmpty();
        assertThat(pastPeople.totalElements()).isEqualTo(301);
        assertThat(pastPeople.totalPages()).isEqualTo(16);
        assertThat(pastPeople.hasNext()).isFalse();
        assertThat(explorer.searchRelationshipPeople(owner(), work(), null, "%", 0, 20).totalPages()).isZero();
        Set<UUID> found = new HashSet<>();
        for (int page = 0; page < 3; page++) {
            var slice = explorer.exploreRelationships(owner(), work(), null, center, page);
            assertThat(slice.graph().edges()).hasSize(100);
            assertThat(slice.hasNext()).isEqualTo(page < 2);
            slice.graph().edges().forEach(e -> assertThat(found.add(e.id())).isTrue());
        }
        assertThat(found).hasSize(300);
        jdbc.update("UPDATE relationship_graph_edges SET suppressed=true WHERE id=?", found.iterator().next());
        assertThat(explorer.exploreRelationships(owner(),work(),null,null,0).selectionRequired()).isTrue();
        // Collapse alias nodes onto 40 distinct people: 299 edges with fewer than 100 people is an overview.
        var leaves = jdbc.queryForList("SELECT id FROM relationship_graph_nodes WHERE work_id=? AND id<>? ORDER BY id",UUID.class,work(),center);
        for (int i=40;i<leaves.size();i++) jdbc.update("UPDATE relationship_graph_nodes SET merged_into_node_id=?,merged_at_episode_no=1 WHERE id=?",leaves.get(i%40),leaves.get(i));
        var small = explorer.exploreRelationships(owner(), work(), null, null, 0);
        assertThat(small.totalNodeCount()).isLessThan(100);
        assertThat(small.selectionRequired()).isFalse();
        assertThat(small.graph().edges()).hasSize(299);
        assertThatThrownBy(() -> explorer.exploreRelationships((Long)ReflectionTestUtils.getField(this,"other"),work(),null,null,0)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> explorer.exploreRelationships(owner(),work(),null,UUID.randomUUID(),0)).isInstanceOf(AppException.class);
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
        List<String> selectionMeasurements = new ArrayList<>();
        var selectedIds = explorer.searchRelationshipCounterparts(owner(),work(),null,center,"",0,24).content().subList(0,20).stream().map(p -> p.node().id()).toList();
        for (int i = 0; i < 7; i++) {
            statistics.clear();
            long before = System.nanoTime();
            Graph legacy = service.getRelationshipGraph(owner(), work(), null);
            double legacyMs = (System.nanoTime() - before) / 1_000_000.0;
            long legacyQueries = statistics.getPrepareStatementCount();
            org.mockito.Mockito.clearInvocations(explorerJdbc);
            statistics.clear();
            before = System.nanoTime();
            var overview = explorer.exploreRelationships(owner(), work(), null, null, 0);
            double overviewMs = (System.nanoTime() - before) / 1_000_000.0;
            long overviewQueries = statistics.getPrepareStatementCount() + nativeQueryUpperBound();
            assertThat(overviewQueries).isLessThanOrEqualTo(9);
            org.mockito.Mockito.clearInvocations(explorerJdbc);
            statistics.clear();
            before = System.nanoTime();
            var focused = explorer.exploreRelationships(owner(), work(), null, center, 0);
            double focusMs = (System.nanoTime() - before) / 1_000_000.0;
            long focusQueries = statistics.getPrepareStatementCount() + nativeQueryUpperBound();
            assertThat(focusQueries).isLessThanOrEqualTo(10);
            before = System.nanoTime();
            var search = explorer.searchRelationshipPeople(owner(), work(), null, "인물2999", 0, 20);
            double searchMs = (System.nanoTime() - before) / 1_000_000.0;
            assertThat(search.content()).hasSize(1);
            before = System.nanoTime();
            var directory = explorer.searchRelationshipPeople(owner(),work(),null,"",0,24);
            double directoryMs = (System.nanoTime() - before) / 1_000_000.0;
            assertThat(directory.content()).hasSize(24);
            before = System.nanoTime();
            var chooser = explorer.exploreSelectedRelationships(owner(),work(),null,center,List.of(),0);
            double chooserMs = (System.nanoTime() - before) / 1_000_000.0;
            assertThat(chooser.graph().edges()).isEmpty();
            org.mockito.Mockito.clearInvocations(explorerJdbc); statistics.clear();
            explorer.exploreSelectedRelationships(owner(),work(),null,center,selectedIds.subList(0,1),0);
            long singleQueryBound = statistics.getPrepareStatementCount() + nativeQueryUpperBound();
            org.mockito.Mockito.clearInvocations(explorerJdbc); statistics.clear();
            before = System.nanoTime();
            var selection = explorer.exploreSelectedRelationships(owner(),work(),null,center,selectedIds,0);
            double selectionMs = (System.nanoTime() - before) / 1_000_000.0;
            long selectionQueryBound = statistics.getPrepareStatementCount() + nativeQueryUpperBound();
            assertThat(selectionQueryBound).isEqualTo(singleQueryBound).isLessThanOrEqualTo(12);
            assertThat(selection.graph().edges()).hasSize(20);
            if (i >= 2) selectionMeasurements.add(String.format(java.util.Locale.ROOT,
                "directory_ms=%.2f chooser_ms=%.2f selected20_ms=%.2f selected_query_bound=%d directory_bytes=%d chooser_bytes=%d selected_bytes=%d",
                directoryMs,chooserMs,selectionMs,selectionQueryBound,json.writeValueAsBytes(directory).length,
                json.writeValueAsBytes(chooser).length,json.writeValueAsBytes(selection).length));
            if (i >= 2) measurements.add(String.format(java.util.Locale.ROOT,
                "legacy_ms=%.2f overview_ms=%.2f focus_ms=%.2f search_ms=%.2f legacy_queries=%d overview_query_bound=%d focus_query_bound=%d legacy_bytes=%d overview_bytes=%d focus_bytes=%d",
                legacyMs, overviewMs, focusMs, searchMs, legacyQueries, overviewQueries, focusQueries,
                json.writeValueAsBytes(legacy).length, json.writeValueAsBytes(overview).length, json.writeValueAsBytes(focused).length));
        }
        statistics.setStatisticsEnabled(false);
        System.out.println("GH416_SELECTION_198_EPISODES_3129_EDGES\n" + String.join("\n", selectionMeasurements));
        System.out.println("GH412_SYNTHETIC_198_EPISODES_3129_EDGES\n" + String.join("\n", measurements));
        String query = new org.springframework.core.io.ClassPathResource("queries/relationship/explorer.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("workId",work()).addValue("cutoff",198);
        var plan = explorerJdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + query + "SELECT * FROM people ORDER BY edge_count DESC,name,id LIMIT 24",params,String.class);
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
        assertThat(explorer.searchRelationshipPeople(owner(),work(),1,"작은별",0,20).content()).isEmpty();
        UUID third = ReflectionTestUtils.invokeMethod(this,"job",3);
        // Empty successful episode advances the selectable cutoff without introducing another relation.
        jdbc.update("UPDATE relationship_graph_analyses SET status='APPLIED' WHERE id=?",third);
        assertThat(explorer.searchRelationshipPeople(owner(),work(),3,"작은별",0,20).content()).extracting(p -> p.node().id()).containsExactly(node);
        jdbc.update("UPDATE episodes SET content_s3_version='replaced' WHERE work_id=? AND episode_no=2",work());
        assertThat(explorer.searchRelationshipPeople(owner(),work(),3,"작은별",0,20).content()).isEmpty();
    }

    @Test @DisplayName("관계가 300개 미만이어도 인물이 100명 이상이면 선택부터 시작한다")
    void nodeThresholdProtectsSparseLargeGraphs() {
        seedEdges(99);
        var result = explorer.exploreRelationships(owner(),work(),null,null,0);
        assertThat(result.totalNodeCount()).isEqualTo(100);
        assertThat(result.graph().totalEdgeCount()).isEqualTo(99);
        assertThat(result.selectionRequired()).isTrue();
        UUID edge = jdbc.queryForObject("SELECT id FROM relationship_graph_edges WHERE work_id=? LIMIT 1",UUID.class,work());
        jdbc.update("UPDATE relationship_graph_edges SET suppressed=true WHERE id=?",edge);
        var smaller = explorer.exploreRelationships(owner(),work(),null,null,0);
        assertThat(smaller.totalNodeCount()).isEqualTo(99);
        assertThat(smaller.selectionRequired()).isFalse();
    }

    private void duplicatePair(UUID template, int copies) {
        for (int n = 0; n < copies; n++) {
            UUID edge = UUID.randomUUID();
            jdbc.update("INSERT INTO relationship_graph_edges(id,work_id,source_node_id,target_node_id,axis,kind,canonical_key,version,suppressed,created_at,updated_at) SELECT ?,work_id,source_node_id,target_node_id,axis,kind,?,0,false,now(),now() FROM relationship_graph_edges WHERE id=?", edge, edge.toString(), template);
            jdbc.update("INSERT INTO relationship_graph_events(id,work_id,analysis_id,edge_id,episode_id,episode_no,source_name,target_name,label,summary,direction,status,evidence,created_at,updated_at) SELECT ?,work_id,analysis_id,?,episode_id,episode_no,source_name,target_name,label,summary,direction,status,evidence,now(),now() FROM relationship_graph_events WHERE edge_id=?", UUID.randomUUID(), edge, template);
        }
    }

    @Test @DisplayName("중심 관계 199개는 바로 표시하고 200개부터 상대를 선택한다")
    void selectedExplorerBoundaryAndScopedDirectory() {
        largeFixture = true;
        UUID center = seedEdges(200);
        var choice = explorer.exploreSelectedRelationships(owner(), work(), null, center, List.of(), 0);
        assertThat(choice.counterpartSelectionRequired()).isTrue();
        assertThat(choice.graph().edges()).isEmpty();
        assertThat(choice.focusEdgeCount()).isEqualTo(200);
        var people = explorer.searchRelationshipCounterparts(owner(), work(), null, center, "", 0, 24);
        assertThat(people.totalElements()).isEqualTo(200);
        assertThat(people.content()).hasSize(24).allSatisfy(p -> assertThat(p.edgeCount()).isEqualTo(1));
        assertThat(people.content()).noneSatisfy(p -> assertThat(p.node().id()).isEqualTo(center));
        var ids = people.content().subList(0, 20).stream().map(p -> p.node().id()).toList();
        var selected = explorer.exploreSelectedRelationships(owner(), work(), null, center, ids, 0);
        assertThat(selected.graph().edges()).hasSize(20);
        assertThat(selected.graph().nodes()).hasSize(21);
        assertThat(selected.selectedPeople()).hasSize(20);
        assertThat(selected.graph().edges()).allSatisfy(e -> {
            assertThat(List.of(e.sourceNodeId(), e.targetNodeId())).contains(center);
            assertThat(ids).contains(e.sourceNodeId().equals(center) ? e.targetNodeId() : e.sourceNodeId());
        });
        jdbc.update("UPDATE relationship_graph_edges SET suppressed=true WHERE id=?", selected.graph().edges().getFirst().id());
        var small = explorer.exploreSelectedRelationships(owner(), work(), null, center, List.of(), 0);
        assertThat(small.counterpartSelectionRequired()).isFalse();
        assertThat(small.graph().edges()).hasSize(199);
        assertThat(small.hasNext()).isFalse();
    }

    @Test @DisplayName("전체 인물은 상대 수가 아닌 관계 수순이며 한 상대의 초과 관계도 누락 없이 조회한다")
    void selectedExplorerSortAndOversizedSinglePair() {
        largeFixture = true;
        UUID center = seedEdges(30);
        var original = explorer.exploreRelationships(owner(), work(), null, center, 0).graph().edges().getFirst();
        UUID other = original.sourceNodeId().equals(center) ? original.targetNodeId() : original.sourceNodeId();
        duplicatePair(original.id(), 205);
        var global = explorer.searchRelationshipPeople(owner(), work(), null, "", 0, 24);
        assertThat(global.content().get(0).node().id()).isEqualTo(center);
        assertThat(global.content().get(1).node().id()).isEqualTo(other);
        assertThat(global.content().get(1).edgeCount()).isEqualTo(206);
        assertThat(global.content().get(1).neighborCount()).isEqualTo(1);
        var peers = explorer.searchRelationshipCounterparts(owner(), work(), null, center, "", 0, 24);
        assertThat(peers.content().getFirst().node().id()).isEqualTo(other);
        assertThat(peers.content().getFirst().edgeCount()).isEqualTo(206);
        var first = explorer.exploreSelectedRelationships(owner(), work(), null, center, List.of(other), 0);
        var second = explorer.exploreSelectedRelationships(owner(), work(), null, center, List.of(other), 1);
        assertThat(first.graph().edges()).hasSize(200);
        assertThat(first.hasNext()).isTrue();
        assertThat(second.graph().edges()).hasSize(6);
        assertThat(second.hasNext()).isFalse();
        Set<UUID> unique = new HashSet<>();
        first.graph().edges().forEach(e -> assertThat(unique.add(e.id())).isTrue());
        second.graph().edges().forEach(e -> assertThat(unique.add(e.id())).isTrue());
        assertThat(unique).hasSize(206);
        var otherPeer = peers.content().get(1).node().id();
        assertThatThrownBy(() -> explorer.exploreSelectedRelationships(owner(),work(),null,center,List.of(other,otherPeer),0))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getResultCode()).isEqualTo(
                    org.monitoring.catchholebackend.domain.relationship.exception.RelationshipGraphErrorCode.RELATIONSHIP_SELECTION_LIMIT_EXCEEDED));
    }

    @Test @DisplayName("복수 선택의 200개 경계·20명 제한과 잘못된 중심/상대를 서버가 검증한다")
    void selectedExplorerRejectsInvalidSelectionsAndPreservesTwoHundred() {
        largeFixture = true;
        UUID center = seedEdges(30);
        var edges = explorer.exploreRelationships(owner(),work(),null,center,0).graph().edges();
        var first = edges.get(0); var second = edges.get(1);
        UUID one = first.sourceNodeId().equals(center) ? first.targetNodeId() : first.sourceNodeId();
        UUID two = second.sourceNodeId().equals(center) ? second.targetNodeId() : second.sourceNodeId();
        duplicatePair(first.id(), 119); duplicatePair(second.id(), 79);
        assertThat(explorer.exploreSelectedRelationships(owner(),work(),null,center,List.of(one,two),0).graph().edges()).hasSize(200);
        duplicatePair(second.id(), 1);
        var peers = explorer.searchRelationshipCounterparts(owner(),work(),null,center,"",0,24).content();
        for (List<UUID> invalid : List.of(List.of(one,two), List.of(one,one), List.of(center), List.of(UUID.randomUUID()),
                peers.subList(0,21).stream().map(p -> p.node().id()).toList()))
            assertThatThrownBy(() -> explorer.exploreSelectedRelationships(owner(),work(),null,center,invalid,0)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> explorer.exploreSelectedRelationships(owner(),work(),null,null,List.of(one),0)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> explorer.exploreSelectedRelationships(owner(),work(),null,center,List.of(one),1)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> explorer.exploreSelectedRelationships((Long)ReflectionTestUtils.getField(this,"other"),work(),null,center,List.of(one),0)).isInstanceOf(AppException.class);
        assertThat(explorer.searchRelationshipCounterparts(owner(),work(),null,center,"%",0,24).totalElements()).isZero();
        assertThat(explorer.searchRelationshipCounterparts(owner(),work(),null,center,"",10,24).totalElements()).isEqualTo(30);
    }

    @Test @DisplayName("실제 선택 API와 배열 입력·OpenAPI를 프론트 계약 검증으로 내보낸다")
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="GH416_FIXTURE_OUTPUT", matches="/tmp/gh416-selection-contract.json")
    void exportSelectionHttpContract() throws Exception {
        largeFixture = true;
        UUID center = seedEdges(230);
        var principal = tx.execute(t -> org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal.from(
                em.find(org.monitoring.catchholebackend.domain.member.entity.Member.class, owner())));
        var auth = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal,"",principal.getAuthorities()));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var fixture = json.createObjectNode().put("workId", work().toString()).put("center", center.toString());
        String base = "/api/v1/works/"+work()+"/relationship-graph";
        var get = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base+"/people").param("size","24").with(auth);
        fixture.set("directory", json.readTree(mvc.perform(get).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsByteArray()));
        fixture.set("overview", json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base+"/explore/selection").with(auth)).andReturn().getResponse().getContentAsByteArray()));
        fixture.set("focus", json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base+"/explore/selection").param("focusNodeId",center.toString()).with(auth)).andReturn().getResponse().getContentAsByteArray()));
        fixture.set("counterparts", json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base+"/people").param("focusNodeId",center.toString()).param("size","24").with(auth)).andReturn().getResponse().getContentAsByteArray()));
        String one = fixture.path("counterparts").path("data").path("content").get(0).path("node").path("id").asText();
        String two = fixture.path("counterparts").path("data").path("content").get(1).path("node").path("id").asText();
        fixture.set("selected", json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base+"/explore/selection").param("focusNodeId",center.toString()).param("counterpartNodeIds",one,two).with(auth)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsByteArray()));
        assertThat(fixture.path("selected").path("data").path("graph").path("edges").size()).isEqualTo(2);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base+"/explore/selection")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base+"/explore/selection").param("counterpartNodeIds",one).with(auth)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("GH416_FIXTURE_OUTPUT")),json.writeValueAsString(fixture));
        var openapi = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(openapi).path("paths").path(base.replace(work().toString(),"{workId}")+"/explore/selection").path("get").path("operationId").asText()).isEqualTo("exploreSelectedEpisodeRelationships");
        java.nio.file.Files.writeString(java.nio.file.Path.of("/tmp/gh416-openapi.json"),openapi);
        runSelectionBrowser(center);
    }

    private void runSelectionBrowser(UUID center) throws Exception {
        String frontend = System.getenv("GH416_FRONT_CHECKOUT");
        if (frontend == null || frontend.isBlank()) return;
        var directory = java.nio.file.Files.createTempDirectory("gh416-relationship-browser-",
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        var input = directory.resolve("fixture.json");
        java.nio.file.Files.createFile(input, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        String token = tx.execute(t -> jwt.generateAccessToken(em.find(org.monitoring.catchholebackend.domain.member.entity.Member.class, owner())));
        java.nio.file.Files.writeString(input, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "testOnly", true, "apiBaseUrl", "http://127.0.0.1:" + httpPort, "accessToken", token,
                "workId", work(), "center", center)));
        var builder = new ProcessBuilder("node", "scripts/verify-relationship-selection-live.mjs", input.toString())
                .directory(java.nio.file.Path.of(frontend).toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("browser.log").toFile());
        for (String key : List.of("LLM_API_KEY", "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "GOOGLE_API_KEY", "DATABASE_URL",
                "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN")) builder.environment().remove(key);
        Process process = null;
        try {
            process = builder.start();
            assertThat(process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)).as("실제 선택 화면 시간 제한; 비공개 로그: %s", directory).isTrue();
            assertThat(process.exitValue()).as("실제 선택 화면 실패; 비공개 로그: %s", directory).isZero();
        } finally {
            if (process != null) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
            java.nio.file.Files.deleteIfExists(input);
        }
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
        properties.add("springdoc.paths-to-exclude", () -> "/test/**,/test");
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
