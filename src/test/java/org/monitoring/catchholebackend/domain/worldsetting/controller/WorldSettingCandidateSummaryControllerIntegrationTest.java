package org.monitoring.catchholebackend.domain.worldsetting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.support.CandidateReviewApiFixture;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = {"spring.config.import=", "springdoc.packages-to-scan=org.monitoring.catchholebackend.domain"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("세계관 후보 요약 HTTP 계약")
class WorldSettingCandidateSummaryControllerIntegrationTest extends CandidateReviewApiFixture {
    @Test
    @DisplayName("실제 OpenAPI에 요약 operation과 전체 응답 필드를 노출한다")
    void exposesSummaryOpenApiContract() throws Exception {
        String schema = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var document = json.readTree(schema);
        assertThat(document.path("paths").path("/api/v1/works/{workId}/world-setting-candidates/summary")
                .path("get").path("operationId").asText()).isEqualTo("getWorldSettingCandidateSummary");
        assertThat(document.path("components").path("schemas").path("WorldSettingCandidateSummaryResponse")
                .path("properties").propertyNames()).hasSize(19).doesNotContain("groups");
        String output = System.getenv("CANDIDATE_REVIEW_OPENAPI_OUTPUT");
        if (output != null) java.nio.file.Files.writeString(java.nio.file.Path.of(output), schema);
    }

    @Test
    @DisplayName("요약은 필터·페이지와 무관한 목록의 모든 최상위 집계와 일치한다")
    void summaryMatchesListMetadataWithoutCandidates() throws Exception {
        var origin = completedJob(3); var episodeCandidate = world(origin, "북부"); world(origin, "남부");
        var book = bookJob(); var bookCandidate = world(book, "설정집 장소");
        em.persist(AnalysisJob.createWorldSettingComparison(episodeCandidate));
        em.persist(AnalysisJob.createWorldSettingComparison(bookCandidate));
        em.flush(); em.clear();
        for (UUID source : new UUID[] {null, origin.getId(), book.getId()}) {
            ObjectNode expected = (ObjectNode) read(worldUrl(), source); expected.remove("groups");
            assertThat(read(worldUrl() + "/summary", source)).isEqualTo(expected);
        }
        var summary = read(worldUrl() + "/summary", null);
        assertThat(summary.path("totalCandidateCount").asLong()).isEqualTo(2);
        assertThat(summary.path("pendingCandidateCount").asLong()).isEqualTo(2);
        assertThat(summary.path("pendingComparisonCount").asLong()).isEqualTo(2);
        assertThat(summary.path("processingCandidateCount").asLong()).isEqualTo(2);
        assertThat(summary.path("directReviewCandidateCount").asLong()).isZero();
        assertThat(summary.path("activeComparisonJobCount").asLong()).isEqualTo(1);
        assertThat(read(worldUrl() + "/summary", book.getId()).path("activeComparisonJobCount").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("회차 스냅샷 번호가 없는 과거 수동 출처는 실제 회차로 범위만 복원한다")
    void preservesLegacyEpisodeRangeFallback() throws Exception {
        var origin = AnalysisJob.create(work, batch, episode(7), AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(origin, "sourceEpisodeNo", null); em.persist(origin); world(origin, "과거 장소");
        em.flush(); em.clear();
        var summary = read(worldUrl() + "/summary", origin.getId());
        assertThat(summary.path("episodeStartNo").asInt()).isEqualTo(7);
        assertThat(summary.path("episodeEndNo").asInt()).isEqualTo(7);
        assertThat(summary.path("episodeCount").asLong()).isEqualTo(1);
        var candidate = read(worldUrl(), origin.getId()).path("groups").path("content").get(0).path("candidates").get(0);
        assertThat(candidate.path("source").path("episodeNo").isNull()).isTrue();
    }

    @Test
    @DisplayName("빈 배치는 후보·페이지 없이 모든 집계를 0으로 반환한다")
    void returnsEmptySummary() throws Exception {
        var data = read(worldUrl() + "/summary", null);
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("batchId", "episodeStartNo", "episodeEndNo", "episodeCount",
                "totalCandidateCount", "reviewedCandidateCount", "pendingCandidateCount", "pendingComparisonCount",
                "processingComparisonCount", "activeComparisonJobCount", "failedComparisonCount", "tokenInterruptedComparisonCount",
                "canResumeTokenInterruptedComparisons", "recomparisonRequiredCount", "conflictCandidateCount", "confirmedCandidateCount",
                "dismissedCandidateCount", "directReviewCandidateCount", "processingCandidateCount");
        assertThat(data.path("totalCandidateCount").asLong()).isZero();
        assertThat(data.path("episodeCount").asLong()).isZero();
        assertThat(data.path("episodeStartNo").isNull()).isTrue();
        assertThat(data.path("canResumeTokenInterruptedComparisons").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("인증·필수 배치와 작품·배치·추출 출처 소유권을 검사한다")
    void rejectsForeignBatchWorkAndComparisonJob() throws Exception {
        mvc.perform(get(worldUrl() + "/summary").queryParam("batchId", batch.getId().toString())).andExpect(status().isUnauthorized());
        mvc.perform(get(worldUrl() + "/summary").header("Authorization", authorization)).andExpect(status().isBadRequest());
        mvc.perform(get(worldUrl() + "/summary").header("Authorization", authorization).queryParam("batchId", "invalid")).andExpect(status().isBadRequest());
        var origin = completedJob(3); var candidate = world(origin, "북부");
        AnalysisJob comparison = AnalysisJob.createWorldSettingComparison(candidate); em.persist(comparison);
        AnalysisJob foreign = AnalysisJob.create(otherWork, otherBatch, null, AnalysisJobType.SETTING_EXTRACTION); em.persist(foreign);
        var anotherBatch = org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.create(work, member,
                org.monitoring.catchholebackend.domain.upload.type.UploadType.SINGLE_EPISODE,
                org.monitoring.catchholebackend.domain.upload.type.UploadSourceType.FILE); em.persist(anotherBatch);
        AnalysisJob otherOrigin = AnalysisJob.create(work, anotherBatch, origin.getEpisode(), AnalysisJobType.SETTING_EXTRACTION); em.persist(otherOrigin);
        em.flush();
        for (UUID id : java.util.List.of(UUID.randomUUID(), comparison.getId(), foreign.getId(), otherOrigin.getId()))
            mvc.perform(request(worldUrl() + "/summary", id)).andExpect(status().isNotFound());
        mvc.perform(get(worldUrl() + "/summary").header("Authorization", authorization).queryParam("batchId", otherBatch.getId().toString())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/works/" + otherWork.getId() + "/world-setting-candidates/summary")
                .header("Authorization", authorization).queryParam("batchId", otherBatch.getId().toString())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("과거·보관 설정집 출처는 읽되 수동 반영 가능으로 승격하지 않는다")
    void readsHistoricalBookWithoutMakingItActionable() throws Exception {
        var origin = bookJob(); var candidate = world(origin, "설정집 장소");
        ReflectionTestUtils.setField(candidate, "comparisonStatus", org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus.COMPLETED);
        ReflectionTestUtils.setField(origin.getSourceSettingBook(), "archivedAt", LocalDateTime.now());
        em.flush(); em.clear();
        assertThat(read(worldUrl() + "/summary", origin.getId()).path("totalCandidateCount").asLong()).isEqualTo(1);
        var response = read(worldUrl(), origin.getId()).path("groups").path("content").get(0).path("candidates").get(0);
        assertThat(response.path("manualReviewAvailable").asBoolean()).isFalse();
        assertThat(response.path("source").path("referenceTimeKind").asText()).isEqualTo("EPISODE");
    }
}
