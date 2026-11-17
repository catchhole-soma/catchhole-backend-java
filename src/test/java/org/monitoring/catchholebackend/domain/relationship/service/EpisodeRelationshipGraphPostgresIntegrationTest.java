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
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphRules;
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
        Edge original = graph.edges().getFirst();
        UUID center = original.sourceNodeId();
        UUID template = original.id();
        for (int n = 1; n < count; n++) {
            UUID node = UUID.randomUUID(), edge = UUID.randomUUID();
            jdbc.update("INSERT INTO relationship_graph_nodes(id,work_id,name,first_episode_no,created_at,updated_at) VALUES (?,?,?,1,now(),now())", node, work(), "인물" + n);
            String key = hasher.hash(RelationshipGraphRules.canonical(center.toString(), node.toString(),
                    original.axis(), original.kind(), original.direction()));
            jdbc.update("INSERT INTO relationship_graph_edges(id,work_id,source_node_id,target_node_id,axis,kind,canonical_key,version,suppressed,created_at,updated_at) SELECT ?,work_id,?,? ,axis,kind,?,0,false,now(),now() FROM relationship_graph_edges WHERE id=?", edge, center, node, key, template);
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
        // 300 physical edges collapse to 40 canonical slots; suppression on any member hides its slot.
        var leaves = jdbc.queryForList("SELECT id FROM relationship_graph_nodes WHERE work_id=? AND id<>? ORDER BY id",UUID.class,work(),center);
        for (int i=40;i<leaves.size();i++) jdbc.update("UPDATE relationship_graph_nodes SET merged_into_node_id=?,merged_at_episode_no=1 WHERE id=?",leaves.get(i%40),leaves.get(i));
        var small = explorer.exploreRelationships(owner(), work(), null, null, 0);
        assertThat(small.totalNodeCount()).isLessThan(100);
        assertThat(small.selectionRequired()).isFalse();
        assertThat(small.graph().edges()).hasSize(39);
        assertThat(small.graph().totalEdgeCount()).isEqualTo(39);
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

    private void addPairSlots(UUID template, int count) {
        Detail originalDetail = explorer.getRelationshipDetail(owner(), work(), template, null);
        Edge original = originalDetail.edge();
        Set<String> keys = new HashSet<>(jdbc.queryForList(
                "SELECT canonical_key FROM relationship_graph_edges WHERE work_id=?", String.class, work()));
        int added = 0;
        for (Axis axis : Axis.values()) for (Kind kind : Kind.values()) for (Direction direction : Direction.values()) {
            int orientations = direction == Direction.DIRECTED ? 2 : 1;
            for (int reversed = 0; reversed < orientations; reversed++) {
                UUID source = reversed == 0 ? original.sourceNodeId() : original.targetNodeId();
                UUID target = reversed == 0 ? original.targetNodeId() : original.sourceNodeId();
                String key = hasher.hash(RelationshipGraphRules.canonical(source.toString(), target.toString(), axis, kind, direction));
                if (!keys.add(key)) continue;
                UUID edge = UUID.randomUUID();
                jdbc.update("INSERT INTO relationship_graph_edges(id,work_id,source_node_id,target_node_id,axis,kind,canonical_key,version,suppressed,created_at,updated_at) SELECT ?,work_id,?,?,?,?,?,0,false,now(),now() FROM relationship_graph_edges WHERE id=?",
                        edge, source, target, axis.name(), kind.name(), key, template);
                jdbc.update("INSERT INTO relationship_graph_events(id,work_id,analysis_id,edge_id,episode_id,episode_no,source_name,target_name,label,summary,direction,status,evidence,created_at,updated_at) SELECT ?,work_id,analysis_id,?,episode_id,episode_no,?,?,label,summary,?,status,evidence,now(),now() FROM relationship_graph_events WHERE edge_id=?",
                        UUID.randomUUID(), edge,
                        reversed == 0 ? originalDetail.sourceNode().name() : originalDetail.targetNode().name(),
                        reversed == 0 ? originalDetail.targetNode().name() : originalDetail.sourceNode().name(),
                        direction.name(), template);
                if (++added == count) return;
            }
        }
        throw new AssertionError("Requested more distinct slots than the current relation enums allow");
    }

    private UUID duplicateAliasSlot(UUID template, boolean reverseEndpoints) {
        return duplicateAliasSlot(template, reverseEndpoints, UUID.randomUUID());
    }

    private UUID duplicateAliasSlot(UUID template, boolean reverseEndpoints, UUID edge) {
        Detail originalDetail = explorer.getRelationshipDetail(owner(), work(), template, null);
        Edge original = originalDetail.edge();
        UUID alias = UUID.randomUUID();
        jdbc.update("INSERT INTO relationship_graph_nodes(id,work_id,name,first_episode_no,merged_into_node_id,merged_at_episode_no,created_at,updated_at) VALUES (?,?,?,1,?,1,now(),now())",
                alias, work(), "별칭", original.sourceNodeId());
        UUID source = reverseEndpoints ? original.targetNodeId() : alias;
        UUID target = reverseEndpoints ? alias : original.targetNodeId();
        String key = hasher.hash(RelationshipGraphRules.canonical(source.toString(), target.toString(), original.axis(), original.kind(), original.direction()));
        jdbc.update("INSERT INTO relationship_graph_edges(id,work_id,source_node_id,target_node_id,axis,kind,canonical_key,version,suppressed,created_at,updated_at) SELECT ?,work_id,?,?,axis,kind,?,1,false,now(),now() FROM relationship_graph_edges WHERE id=?",
                edge, source, target, key, template);
        jdbc.update("INSERT INTO relationship_graph_events(id,work_id,analysis_id,edge_id,episode_id,episode_no,source_name,target_name,label,summary,direction,status,evidence,created_at,updated_at) SELECT ?,work_id,analysis_id,?,episode_id,episode_no,?,?,label,summary,direction,status,evidence,now(),now() FROM relationship_graph_events WHERE edge_id=?",
                UUID.randomUUID(), edge,
                reverseEndpoints ? originalDetail.targetNode().name() : originalDetail.sourceNode().name(),
                reverseEndpoints ? originalDetail.sourceNode().name() : originalDetail.targetNode().name(), template);
        return edge;
    }

    private UUID appendReadEvent(UUID edge, int episodeNo, EdgeStatus status, String summary) {
        UUID analysis = ReflectionTestUtils.invokeMethod(this, "job", episodeNo);
        jdbc.update("UPDATE relationship_graph_analyses SET status='APPLIED' WHERE id=?", analysis);
        jdbc.update("""
                INSERT INTO relationship_graph_events(id,work_id,analysis_id,edge_id,episode_id,episode_no,
                    source_name,target_name,label,summary,direction,status,evidence,created_at,updated_at)
                SELECT ?, old.work_id, a.id, old.edge_id, a.episode_id, a.episode_no,
                    old.source_name, old.target_name, old.label, ?, old.direction, ?,
                    jsonb_set(jsonb_set(old.evidence, '{0,episodeId}', to_jsonb(a.episode_id::text)),
                        '{0,episodeNo}', to_jsonb(a.episode_no)), now(), now()
                FROM relationship_graph_events old JOIN relationship_graph_analyses a ON a.id=?
                WHERE old.edge_id=? ORDER BY old.episode_no, old.id LIMIT 1
                """, UUID.randomUUID(), summary, status.name(), analysis, edge);
        jdbc.update("UPDATE relationship_graph_edges SET version=version+1 WHERE id=?", edge);
        return analysis;
    }

    @Test @DisplayName("별칭 슬롯은 회차별 최신 종료·재개 상태와 중복 없는 전체 근거를 같은 대표 ID로 조회한다")
    void explorerProjectsAliasLifecycleAndEvidenceBeforePaging() {
        UUID center = seedEdges(1);
        Edge original = explorer.exploreRelationships(owner(), work(), 1, center, 0).graph().edges().getFirst();
        UUID alias = duplicateAliasSlot(original.id(), true);
        // Same-episode collisions keep the canonical/unmoved member's state, not the newest raw row.
        jdbc.update("UPDATE relationship_graph_events SET status='ENDED',summary='같은 회차 별칭 종료' WHERE edge_id=?", alias);
        assertThat(explorer.getRelationshipDetail(owner(), work(), alias, 1).edge().status()).isEqualTo(EdgeStatus.ACTIVE);
        assertThat(explorer.getRelationshipDetail(owner(), work(), alias, 1).evidence()).hasSize(1);

        appendReadEvent(alias, 2, EdgeStatus.ENDED, "2화에 동행이 끝났다");
        var ended = explorer.exploreRelationships(owner(), work(), 2, center, 0);
        assertThat(ended.graph().totalEdgeCount()).isEqualTo(1);
        assertThat(ended.graph().edges()).singleElement().satisfies(edge -> {
            assertThat(edge.id()).isEqualTo(original.id());
            assertThat(edge.status()).isEqualTo(EdgeStatus.ENDED);
            assertThat(edge.summary()).isEqualTo("2화에 동행이 끝났다");
            assertThat(edge.evidenceCount()).isEqualTo(2);
            assertThat(edge.version()).isEqualTo(original.version() + 2);
        });
        var canonicalDetail = explorer.getRelationshipDetail(owner(), work(), original.id(), 2);
        var aliasDetail = explorer.getRelationshipDetail(owner(), work(), alias, 2);
        assertThat(aliasDetail).isEqualTo(canonicalDetail);
        assertThat(aliasDetail.sourceNode().name()).isEqualTo("아라");
        assertThat(aliasDetail.targetNode().name()).isEqualTo("보라");
        assertThat(aliasDetail.evidence()).extracting(PublicEvidence::episodeNo).containsExactly(1, 2);
        assertThat(explorer.getRelationshipDetail(owner(), work(), alias, 1).edge().status()).isEqualTo(EdgeStatus.ACTIVE);
        assertThat(explorer.getRelationshipDetail(owner(), work(), alias, 1).evidence()).hasSize(1);

        appendReadEvent(original.id(), 3, EdgeStatus.ACTIVE, "3화에 다시 동행한다");
        assertThat(explorer.getRelationshipDetail(owner(), work(), alias, 3).edge().status()).isEqualTo(EdgeStatus.ACTIVE);
        assertThat(explorer.searchRelationshipCounterparts(owner(), work(), 3, center, "", 0, 20).content())
                .singleElement().satisfies(person -> assertThat(person.edgeCount()).isEqualTo(1));
    }

    @Test @DisplayName("다른 공개 세대의 별칭 정정과 삭제도 canonical 슬롯 전체에 보존한다")
    void explorerPreservesAliasCorrectionsAndSuppressionAcrossGenerations() {
        seedEdges(1);
        Edge original = explorer.exploreRelationships(owner(), work(), 1, null, 0).graph().edges().getFirst();
        UUID alias = duplicateAliasSlot(original.id(), false);
        UUID unpublished = appendReadEvent(alias, 2, EdgeStatus.ENDED, "미공개 세대 종료");
        jdbc.update("UPDATE relationship_graph_analyses SET generation=9 WHERE id=?", unpublished);
        jdbc.update("DELETE FROM relationship_graph_events WHERE edge_id=? AND episode_no=1", alias);
        jdbc.update("UPDATE relationship_graph_edges SET corrected_summary='별칭의 사용자 정정',version=version+1 WHERE id=?", alias);
        var detail = explorer.getRelationshipDetail(owner(), work(), original.id(), 1);
        assertThat(detail.edge().status()).isEqualTo(EdgeStatus.ACTIVE);
        assertThat(detail.edge().summary()).isEqualTo("별칭의 사용자 정정");
        assertThat(detail.evidence()).hasSize(1);
        jdbc.update("UPDATE relationship_graph_edges SET corrected_summary='대표의 사용자 정정',version=version+1 WHERE id=?", original.id());
        assertThat(explorer.getRelationshipDetail(owner(), work(), alias, 1).edge().summary()).isEqualTo("대표의 사용자 정정");
        jdbc.update("UPDATE relationship_graph_edges SET suppressed=true,version=version+1 WHERE id=?", alias);
        assertThat(explorer.exploreRelationships(owner(), work(), 1, null, 0).graph().edges()).isEmpty();
        assertThat(explorer.searchRelationshipPeople(owner(), work(), 1, "", 0, 20).totalElements()).isZero();
        for (UUID id : List.of(original.id(), alias))
            assertThatThrownBy(() -> explorer.getRelationshipDetail(owner(), work(), id, 1)).isInstanceOf(AppException.class);
    }

    @Test @DisplayName("같은 시각의 별칭 슬롯은 lexical UUID 순으로 대표·정정을 고르고 반대 event 이름을 맞춘다")
    void explorerAliasTieUsesLexicalIdsAndAlignedNames() {
        seedEdges(1);
        Edge original = explorer.exploreRelationships(owner(), work(), 1, null, 0).graph().edges().getFirst();
        UUID low = duplicateAliasSlot(original.id(), true, UUID.fromString("00000000-0000-0000-0000-000000000001"));
        UUID high = duplicateAliasSlot(original.id(), false, UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        UUID canonical = UUID.randomUUID();
        jdbc.update("INSERT INTO relationship_graph_nodes(id,work_id,name,first_episode_no,created_at,updated_at) VALUES (?,?,?,1,now(),now())",
                canonical, work(), "아라");
        jdbc.update("UPDATE relationship_graph_nodes SET merged_into_node_id=?,merged_at_episode_no=1 WHERE id=?", canonical, original.sourceNodeId());
        jdbc.update("UPDATE relationship_graph_edges SET created_at='2000-01-01',corrected_summary='작은 ID 정정' WHERE id=?", low);
        jdbc.update("UPDATE relationship_graph_edges SET created_at='2000-01-01',corrected_summary='큰 ID 정정' WHERE id=?", high);
        jdbc.update("UPDATE relationship_graph_events SET status='ENDED',created_at='2001-01-01' WHERE edge_id=?", low);
        jdbc.update("UPDATE relationship_graph_events SET status='ACTIVE',created_at='2030-01-01' WHERE edge_id=?", high);
        var first = explorer.getRelationshipDetail(owner(), work(), original.id(), 1);
        assertThat(first.edge().id()).isEqualTo(low);
        assertThat(first.edge().summary()).isEqualTo("작은 ID 정정");
        assertThat(first.edge().status()).isEqualTo(EdgeStatus.ENDED);
        appendReadEvent(high, 2, EdgeStatus.ACTIVE, "다시 시작된 동행");
        var second = explorer.getRelationshipDetail(owner(), work(), high, 2);
        assertThat(second.edge().id()).isEqualTo(low);
        assertThat(second.edge().status()).isEqualTo(EdgeStatus.ACTIVE);
        assertThat(second.sourceNode().name()).isEqualTo("보라");
        assertThat(second.targetNode().name()).isEqualTo("아라");
        assertThat(second.edge().sourceNodeId()).isEqualTo(original.targetNodeId());
        assertThat(second.edge().targetNodeId()).isEqualTo(canonical);
    }

    @Test @DisplayName("손상된 canonical key나 event 방향은 슬롯을 조용히 누락하지 않고 조회 충돌로 거절한다")
    void explorerRejectsMalformedSlotIdentity() {
        seedEdges(1);
        Edge original = explorer.exploreRelationships(owner(), work(), 1, null, 0).graph().edges().getFirst();
        String key = jdbc.queryForObject("SELECT canonical_key FROM relationship_graph_edges WHERE id=?", String.class, original.id());
        jdbc.update("UPDATE relationship_graph_edges SET canonical_key='broken' WHERE id=?", original.id());
        assertThatThrownBy(() -> explorer.exploreRelationships(owner(), work(), 1, null, 0)).isInstanceOf(AppException.class);
        jdbc.update("UPDATE relationship_graph_edges SET canonical_key=? WHERE id=?", key, original.id());
        jdbc.update("UPDATE relationship_graph_events SET direction='DIRECTED' WHERE edge_id=?", original.id());
        assertThatThrownBy(() -> explorer.exploreRelationships(owner(), work(), 1, null, 0)).isInstanceOf(AppException.class);
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

    @Test @DisplayName("관계 수 정렬과 선택은 서로 다른 슬롯을 세며 별칭 중복으로 페이지를 부풀리지 않는다")
    void selectedExplorerSortsDistinctSlotsWithoutAliasDuplicates() {
        largeFixture = true;
        UUID center = seedEdges(30);
        var original = explorer.exploreRelationships(owner(), work(), null, center, 0).graph().edges().getFirst();
        UUID other = original.sourceNodeId().equals(center) ? original.targetNodeId() : original.sourceNodeId();
        addPairSlots(original.id(), 100);
        for (int i = 0; i < 12; i++) duplicateAliasSlot(original.id(), i % 2 == 0);
        var global = explorer.searchRelationshipPeople(owner(), work(), null, "", 0, 24);
        assertThat(global.content().get(0).node().id()).isEqualTo(center);
        assertThat(global.content().get(1).node().id()).isEqualTo(other);
        assertThat(global.content().get(1).edgeCount()).isEqualTo(101);
        assertThat(global.content().get(1).neighborCount()).isEqualTo(1);
        var peers = explorer.searchRelationshipCounterparts(owner(), work(), null, center, "", 0, 24);
        assertThat(peers.content().getFirst().node().id()).isEqualTo(other);
        assertThat(peers.content().getFirst().edgeCount()).isEqualTo(101);
        var first = explorer.exploreSelectedRelationships(owner(), work(), null, center, List.of(other), 0);
        assertThat(first.graph().edges()).hasSize(101);
        assertThat(first.hasNext()).isFalse();
        Set<UUID> unique = new HashSet<>();
        first.graph().edges().forEach(e -> assertThat(unique.add(e.id())).isTrue());
        assertThat(unique).hasSize(101);
        assertThatThrownBy(() -> explorer.exploreSelectedRelationships(owner(), work(), null, center, List.of(other), 1))
                .isInstanceOf(AppException.class);
        var otherPeer = peers.content().get(1).node().id();
        assertThat(explorer.exploreSelectedRelationships(owner(),work(),null,center,List.of(other,otherPeer),0).graph().edges())
                .hasSize(102);
    }

    @Test @DisplayName("복수 선택의 200개 경계·20명 제한과 잘못된 중심/상대를 서버가 검증한다")
    void selectedExplorerRejectsInvalidSelectionsAndPreservesTwoHundred() {
        largeFixture = true;
        UUID center = seedEdges(30);
        var edges = explorer.exploreRelationships(owner(),work(),null,center,0).graph().edges();
        var first = edges.get(0); var second = edges.get(1);
        UUID one = first.sourceNodeId().equals(center) ? first.targetNodeId() : first.sourceNodeId();
        UUID two = second.sourceNodeId().equals(center) ? second.targetNodeId() : second.sourceNodeId();
        addPairSlots(first.id(), 119); addPairSlots(second.id(), 79);
        assertThat(explorer.exploreSelectedRelationships(owner(),work(),null,center,List.of(one,two),0).graph().edges()).hasSize(200);
        addPairSlots(second.id(), 1);
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
