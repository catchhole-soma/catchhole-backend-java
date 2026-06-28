package org.monitoring.catchholebackend.domain.character.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("설정 후보 검토 API 통합 테스트")
class SettingCandidateControllerIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WorkRepository workRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private UploadFileRepository uploadFileRepository;

    @Autowired
    private UploadBatchRepository uploadBatchRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private SettingCandidateRepository settingCandidateRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Member member;
    private Member otherMember;
    private Work work;
    private Work otherWork;
    private Episode episode;
    private AnalysisJob analysisJob;
    private String accessToken;

    @BeforeEach
    void setUp() {
        settingCandidateRepository.deleteAll();
        analysisJobRepository.deleteAll();
        episodeRepository.deleteAll();
        uploadFileRepository.deleteAll();
        uploadBatchRepository.deleteAll();
        workRepository.deleteAll();
        memberRepository.deleteAll();

        member = memberRepository.save(Member.register(
                "writer@example.com",
                "encoded-password",
                "01012345678",
                "작가"
        ));
        otherMember = memberRepository.save(Member.register(
                "other@example.com",
                "encoded-password",
                "01087654321",
                "다른 작가"
        ));
        work = workRepository.save(Work.create(member, "내 작품", "판타지", "내 설명"));
        otherWork = workRepository.save(Work.create(otherMember, "다른 작품", "무협", "다른 설명"));
        episode = episodeRepository.save(Episode.create(
                work,
                null,
                1,
                "1화",
                "works/%s/episodes/1.txt".formatted(work.getId()),
                "version-1",
                "hash-1",
                100
        ));
        analysisJob = analysisJobRepository.save(AnalysisJob.create(
                work,
                null,
                episode,
                AnalysisJobType.SETTING_EXTRACTION
        ));
        accessToken = jwtTokenProvider.generateAccessToken(member);
    }

    @Test
    @DisplayName("설정 후보 목록을 응답한다")
    void getSettingCandidatesReturnsCandidatesForAuthenticatedWork() throws Exception {
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "age",
                "17"
        ));

        mockMvc.perform(get("/api/v1/works/{workId}/setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(candidate.getId().toString()))
                .andExpect(jsonPath("$.data[0].workId").value(work.getId().toString()))
                .andExpect(jsonPath("$.data[0].entityName").value("아리아"))
                .andExpect(jsonPath("$.data[0].attributeName").value("age"))
                .andExpect(jsonPath("$.data[0].reviewStatus").value("PENDING_REVIEW"));
    }

    @Test
    @DisplayName("설정 후보 상세 조회에서 JSON payload를 응답한다")
    void getSettingCandidateReturnsJsonPayloads() throws Exception {
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "age",
                "17"
        ));

        mockMvc.perform(get("/api/v1/works/{workId}/setting-candidates/{candidateId}", work.getId(), candidate.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(candidate.getId().toString()))
                .andExpect(jsonPath("$.data.episodeId").value(episode.getId().toString()))
                .andExpect(jsonPath("$.data.analysisJobId").value(analysisJob.getId().toString()))
                .andExpect(jsonPath("$.data.entityType").value("CHARACTER"))
                .andExpect(jsonPath("$.data.valueType").value("NUMBER"))
                .andExpect(jsonPath("$.data.valueJson.value").value(17))
                .andExpect(jsonPath("$.data.evidenceSpans[0].paragraph_index").value(1))
                .andExpect(jsonPath("$.data.rawAiResultJson.raw_value").value("17"));
    }

    @Test
    @DisplayName("검토 대기 설정 후보의 보정 가능 필드만 수정한다")
    void updateSettingCandidateUpdatesEditableFieldsOnly() throws Exception {
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "age",
                "17"
        ));

        mockMvc.perform(patch("/api/v1/works/{workId}/setting-candidates/{candidateId}", work.getId(), candidate.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "entityName": "아리아",
                                  "attributeName": "level",
                                  "attributeValue": "23",
                                  "valueType": "NUMBER",
                                  "valueJson": {
                                    "value": 23,
                                    "source": "user_review"
                                  },
                                  "evidenceSpans": [
                                    {
                                      "paragraph_index": 2,
                                      "quote": "아리아는 스물셋의 경지에 올랐다."
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("설정 후보가 수정되었습니다."))
                .andExpect(jsonPath("$.data.id").value(candidate.getId().toString()))
                .andExpect(jsonPath("$.data.entityName").value("아리아"))
                .andExpect(jsonPath("$.data.attributeName").value("level"))
                .andExpect(jsonPath("$.data.attributeValue").value("23"))
                .andExpect(jsonPath("$.data.valueType").value("NUMBER"))
                .andExpect(jsonPath("$.data.valueJson.value").value(23))
                .andExpect(jsonPath("$.data.evidenceSpans[0].paragraph_index").value(2))
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING_REVIEW"));
    }

    @Test
    @DisplayName("다른 회원 작품의 설정 후보 목록 조회는 거절한다")
    void getSettingCandidatesRejectsOtherMemberWork() throws Exception {
        mockMvc.perform(get("/api/v1/works/{workId}/setting-candidates", otherWork.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("WORK_NOT_FOUND"));
    }

    @Test
    @DisplayName("설정 후보 목록 조회는 인증을 요구한다")
    void getSettingCandidatesRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/works/{workId}/setting-candidates", work.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
    }

    private SettingCandidate candidate(
            Work targetWork,
            Episode targetEpisode,
            AnalysisJob targetAnalysisJob,
            String entityName,
            String attributeName,
            String attributeValue
    ) {
        return SettingCandidate.create(
                targetWork,
                targetEpisode,
                UUID.randomUUID(),
                targetAnalysisJob,
                SettingEntityType.CHARACTER,
                entityName,
                attributeName,
                attributeValue,
                SettingValueType.NUMBER,
                valueJson(attributeValue),
                evidenceSpans(),
                new BigDecimal("0.8000"),
                rawAiResultJson(attributeValue)
        );
    }

    private JsonNode valueJson(String value) {
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return objectMapper.createObjectNode()
                    .put("value", value);
        }
        return objectMapper.createObjectNode()
                .put("value", Integer.parseInt(digits));
    }

    private JsonNode evidenceSpans() {
        return objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                        .put("paragraph_index", 1)
                        .put("quote", "열일곱 살의 아리아는 북부 기사단의 훈련장을 빠져나왔다."));
    }

    private JsonNode rawAiResultJson(String value) {
        return objectMapper.createObjectNode()
                .put("raw_value", value);
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}
