package org.monitoring.catchholebackend.domain.analysis.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.support.AnalysisProgressApiFixture;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("분석 진행 상태 묶음 HTTP 계약")
class AnalysisJobProgressControllerIntegrationTest extends AnalysisProgressApiFixture {
    @org.springframework.beans.factory.annotation.Autowired
    private org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository jobs;

    @Test
    @DisplayName("관계 백필은 공개 생성·상세·재시도와 최신 회차 분석 상태에 노출하지 않는다")
    void relationshipBackfillStaysOutsidePublicAnalysisBoundary() throws Exception {
        var core = job(1);
        var backfill = AnalysisJob.create(work, batch, core.getEpisode(), AnalysisJobType.RELATIONSHIP_EXTRACTION);
        em.persist(backfill);
        em.flush();
        String base = "/api/v1/works/" + work.getId() + "/analysis-jobs";
        mvc.perform(post(base).header("Authorization", authorization)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"RELATIONSHIP_EXTRACTION\",\"batchId\":\"" + batch.getId() + "\",\"episodeId\":\"" + core.getEpisode().getId() + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("ANALYSIS_JOB_TYPE_INVALID"));
        mvc.perform(get(base + "/" + backfill.getId()).header("Authorization", authorization))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("ANALYSIS_JOB_TYPE_INVALID"));
        mvc.perform(post(base + "/" + backfill.getId() + "/retry").header("Authorization", authorization))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("ANALYSIS_JOB_TYPE_INVALID"));
        assertThat(jobs.findAllWithTargetsByWorkIdOrderByCreatedAtDesc(work.getId()))
                .extracting(AnalysisJob::getId).containsExactly(core.getId());
        assertThat(jobs.findAllRelevantForEpisodeSummaries(List.of(batch.getId()), List.of(core.getEpisode().getId())))
                .extracting(AnalysisJob::getId).containsExactly(core.getId());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 30, 100})
    @DisplayName("허용한 크기의 작업을 작은 상태 응답으로 조회한다")
    void acceptsBoundedIds(int size) throws Exception {
        List<String> ids = IntStream.rangeClosed(1, size).mapToObj(n -> job(n).getId().toString()).toList();
        em.flush();
        var result = mvc.perform(get(progressUrl()).header("Authorization", authorization)
                .queryParam("analysisJobIds", String.join(",", ids))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(size))).andExpect(jsonPath("$.data[0].id").value(ids.getFirst()))
                .andExpect(jsonPath("$.data[0].episodes[0].episodeNo").value(1)).andReturn();
        var row = json.readTree(result.getResponse().getContentAsString()).path("data").get(0);
        assertThat(row.propertyNames()).containsExactlyInAnyOrder("id", "episodeId", "jobType", "status", "reviewMode", "automaticAppliedAt", "failureCode", "tokenInterruptedAfterExtraction", "analysisRun", "episodes");
        assertThat(row.path("episodes").get(0).propertyNames()).containsExactlyInAnyOrder("id", "episodeNo", "title", "status", "updatedAt");
    }

    @Test
    @DisplayName("중복 ID는 최초 순서를 보존하여 한 번씩 반환한다")
    void deduplicatesInRequestedOrder() throws Exception {
        var first = job(1); var second = job(2); em.flush();
        mvc.perform(get(progressUrl()).header("Authorization", authorization)
                .queryParam("analysisJobIds", second.getId() + "," + first.getId() + "," + second.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].id").value(second.getId().toString()))
                .andExpect(jsonPath("$.data[1].id").value(first.getId().toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid-uuid"})
    @DisplayName("비어 있거나 UUID가 아닌 입력은 거절한다")
    void rejectsInvalidIds(String value) throws Exception {
        mvc.perform(get(progressUrl()).header("Authorization", authorization).queryParam("analysisJobIds", value))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("중복 제거 전에 101개 입력과 누락을 거절한다")
    void rejectsMissingAndTooManyRawIds() throws Exception {
        String id = job(1).getId().toString(); em.flush();
        mvc.perform(get(progressUrl()).header("Authorization", authorization)).andExpect(status().isBadRequest());
        mvc.perform(get(progressUrl()).header("Authorization", authorization)
                .queryParam("analysisJobIds", String.join(",", java.util.Collections.nCopies(101, id))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("인증과 작품 소유권을 확인한다")
    void enforcesAuthenticationAndOwnership() throws Exception {
        String id = job(1).getId().toString(); em.flush();
        mvc.perform(get(progressUrl()).queryParam("analysisJobIds", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/works/" + otherWork.getId() + "/analysis-jobs/progress")
                .header("Authorization", authorization).queryParam("analysisJobIds", id)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("없는 ID나 다른 작품 ID가 섞이면 전체를 404로 거절한다")
    void rejectsWholeSetWhenOneJobIsMissingOrCrossWork() throws Exception {
        var owned = job(1);
        var crossWork = AnalysisJob.create(otherWork, otherBatch, null, AnalysisJobType.EPISODE_VALIDATION); em.persist(crossWork); em.flush();
        for (UUID invalid : List.of(UUID.randomUUID(), crossWork.getId())) {
            mvc.perform(get(progressUrl()).header("Authorization", authorization)
                    .queryParam("analysisJobIds", owned.getId() + "," + invalid)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.error.code").value("ANALYSIS_JOB_NOT_FOUND"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"WORLD_SETTING_COMPARISON", "CHARACTER_FACT_COMPARISON", "RELATIONSHIP_EXTRACTION"})
    @DisplayName("내부 비교 작업은 기존 공개 상세 정책과 같이 거절한다")
    void rejectsInternalJobTypes(String type) throws Exception {
        var hidden = AnalysisJob.create(work, batch, episode(1), AnalysisJobType.valueOf(type)); em.persist(hidden); em.flush();
        mvc.perform(get(progressUrl()).header("Authorization", authorization).queryParam("analysisJobIds", hidden.getId().toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("ANALYSIS_JOB_TYPE_INVALID"));
    }

    @Test
    @DisplayName("nullable 실패 코드는 생성 클라이언트가 읽는 enum 또는 null로 문서화한다")
    void documentsNullableFailureCode() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/works/{workId}/analysis-jobs/progress'].get.operationId").value("getAnalysisJobProgress"))
                .andExpect(jsonPath("$.components.schemas.AnalysisJobProgressResponse.properties.failureCode.anyOf[0].enum[0]").value("AI_TOKEN_QUOTA_EXHAUSTED"))
                .andExpect(jsonPath("$.components.schemas.AnalysisJobProgressResponse.properties.failureCode.anyOf[1].type").value("null"))
                .andExpect(jsonPath("$.components.schemas.AnalysisJobProgressResponse.properties.analysisRun.anyOf[0]['$ref']").value("#/components/schemas/AnalysisRunResponse"))
                .andExpect(jsonPath("$.components.schemas.AnalysisJobProgressResponse.properties.analysisRun.anyOf[1].type").value("null"));
    }
}
