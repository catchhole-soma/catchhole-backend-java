package org.monitoring.catchholebackend.global.config.swagger;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("OpenAPI 인증 및 프론트 연동 계약")
class OpenApiContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("공개 Auth API와 JWT·내부 API Key 보호 API의 보안 계약을 구분한다")
    void openApiContractDistinguishesPublicBearerAndInternalApiKeyOperations() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.security").doesNotExist())
                .andExpect(jsonPath("$['components']['securitySchemes']['internalApiKey']['type']")
                        .value("apiKey"))
                .andExpect(jsonPath("$['components']['securitySchemes']['internalApiKey']['in']")
                        .value("header"))
                .andExpect(jsonPath("$['components']['securitySchemes']['internalApiKey']['name']")
                        .value("X-Internal-Api-Key"))
                .andExpect(jsonPath("$['paths']['/api/internal/v1/analysis-jobs/claim']['post']['security'][0]['internalApiKey']")
                        .isArray())
                .andExpect(jsonPath("$['paths']['/api/v1/auth/signup']['post']['operationId']").value("signup"))
                .andExpect(jsonPath("$['paths']['/api/v1/auth/signup']['post']['security']").doesNotExist())
                .andExpect(jsonPath("$['paths']['/api/v1/auth/signup']['post']['responses']['200']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonResponseAuthTokenResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/auth/signup']['post']['responses']['400']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/auth/login']['post']['operationId']").value("login"))
                .andExpect(jsonPath("$['paths']['/api/v1/auth/login']['post']['security']").doesNotExist())
                .andExpect(jsonPath("$['paths']['/api/v1/auth/refresh']['post']['operationId']").value("refresh"))
                .andExpect(jsonPath("$['paths']['/api/v1/auth/logout']['post']['operationId']").value("logout"))
                .andExpect(jsonPath("$['paths']['/api/v1/auth/me']['get']['operationId']").value("getMe"))
                .andExpect(jsonPath("$['paths']['/api/v1/auth/me']['get']['security'][0]['bearerAuth']").isArray())
                .andExpect(jsonPath("$['components']['schemas']['AuthSignupRequest']['properties']['password']['minLength']")
                        .value(8))
                .andExpect(jsonPath("$['components']['schemas']['AuthSignupRequest']['properties']['password']['maxLength']")
                        .value(64))
                .andExpect(jsonPath("$['components']['schemas']['AuthSignupRequest']['properties']['displayName']['maxLength']")
                        .value(20))
                .andExpect(jsonPath("$['paths']['/api/v1/works']['get']['operationId']").value("getMyWorks"))
                .andExpect(jsonPath("$['paths']['/api/v1/works']['post']['operationId']").value("createWork"))
                .andExpect(jsonPath("$['paths']['/api/v1/works']['post']['responses']['400']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['components']['schemas']['WorkCreateRequest']['required']")
                        .value(org.hamcrest.Matchers.hasItems("title", "genre")))
                .andExpect(jsonPath("$['components']['schemas']['WorkCreateRequest']['properties']['genre']['enum']")
                        .value(org.hamcrest.Matchers.contains(
                                "판타지", "로맨스", "추리", "코미디", "SF",
                                "스포츠", "호러", "무협", "일상", "기타"
                        )))
                .andExpect(jsonPath("$['components']['schemas']['WorkCreateRequest']['properties']['description']['maxLength']")
                        .value(50))
                .andExpect(jsonPath("$['components']['schemas']['WorkUpdateRequest']['properties']['description']['maxLength']")
                        .value(50))
                .andExpect(jsonPath("$['components']['schemas']['WorkResponse']['properties']['description']['maxLength']")
                        .value(50))
                .andExpect(jsonPath("$['components']['schemas']['WorkResponse']['required']")
                        .value(org.hamcrest.Matchers.hasItems(
                                "id", "title", "genre", "latestEpisodeNo", "createdAt", "updatedAt"
                        )))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters']['get']['operationId']")
                        .value("getCharacters"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters']['get']['parameters'][1]['name']")
                        .value("page"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters']['get']['parameters'][2]['name']")
                        .value("size"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters']['get']['parameters'][2]['schema']['maximum']")
                        .value(24))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters']['get']['responses']['400']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters']['get']['responses']['401']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters']['get']['responses']['404']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters/{characterId}']['get']['operationId']")
                        .value("getCharacter"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters/{characterId}']['get']['responses']['400']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters/{characterId}']['get']['responses']['401']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters/{characterId}']['get']['responses']['404']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters/{characterId}']['patch']['operationId']")
                        .value("updateCharacter"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters/{characterId}']['patch']['responses']['400']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters/{characterId}']['patch']['responses']['401']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters/{characterId}']['patch']['responses']['404']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters/{characterId}']['patch']['responses']['409']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters/{characterId}']['delete']['operationId']")
                        .value("deleteCharacter"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters/{characterId}']['delete']['responses']['400']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters/{characterId}']['delete']['responses']['401']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters/{characterId}']['delete']['responses']['404']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/characters/{characterId}/restore']['patch']['responses']['400']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['components']['schemas']['PageResponseCharacterSummaryResponse']['properties']['content']['items']['$ref']")
                        .value("#/components/schemas/CharacterSummaryResponse"))
                .andExpect(jsonPath("$['components']['schemas']['PageResponseCharacterSummaryResponse']['properties']['hasNext']")
                        .exists())
                .andExpect(jsonPath("$['components']['schemas']['CharacterDetailResponse']['properties']['currentAgeFact']['$ref']")
                        .value("#/components/schemas/CharacterFactReferenceResponse"))
                .andExpect(jsonPath("$['components']['schemas']['CharacterDetailResponse']['properties']['currentLevelFact']['$ref']")
                        .value("#/components/schemas/CharacterFactReferenceResponse"))
                .andExpect(jsonPath("$['components']['schemas']['CharacterFactReferenceResponse']['required']")
                        .value(org.hamcrest.Matchers.hasItems("characterFactId", "hasEvidence")))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates']['get']['operationId']")
                        .value("getSettingCandidates"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates']['get']['parameters'][*]['name']")
                        .value(containsInAnyOrder(
                                "workId",
                                "batchId",
                                "reviewStatus",
                                "matchStatus",
                                "page",
                                "size"
                        )))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates']['get']['parameters'][1]['required']")
                        .value(true))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates']['get']['parameters'][5]['schema']['maximum']")
                        .value(100))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates']['get']['responses']['400']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates']['get']['responses']['401']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates']['get']['responses']['404']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateListResponse']['properties']['candidates']['$ref']")
                        .value("#/components/schemas/PageResponseSettingCandidateResponse"))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']['properties']['episodeNo']")
                        .exists())
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}']['get']['operationId']")
                        .value("getSettingCandidate"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}']['get']['parameters'][*]['name']")
                        .value(containsInAnyOrder("workId", "batchId", "candidateId")))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}']['get']['responses']['400']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}']['get']['responses']['401']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}']['get']['responses']['404']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"));
    }

    @Test
    @DisplayName("회차 감지와 최종 업로드 계약의 역할별 이름을 노출한다")
    void openApiContractExposesEpisodeDetectionAndConfirmationNames() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/episodes']['post']['operationId']")
                        .value("uploadEpisodes"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/episodes']['post']['requestBody']['required']")
                        .value(true))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/episodes/detect']['post']['operationId']")
                        .value("detectEpisodes"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/episodes/detect']['post']['requestBody']['required']")
                        .value(true))
                .andExpect(jsonPath("$['components']['schemas']['EpisodeDetectionRequest']['properties']['uploadType']['enum']")
                        .value(containsInAnyOrder(
                                "SINGLE_EPISODE",
                                "MULTI_EPISODE_SINGLE_FILE",
                                "MULTI_EPISODE_MULTI_FILE"
                        )))
                .andExpect(jsonPath("$['components']['schemas']['EpisodeDetectionRequest']['properties']['singleEpisodeNo']")
                        .exists())
                .andExpect(jsonPath("$['components']['schemas']['EpisodeDetectionRequest']['properties']['singleEpisodeTitle']")
                        .exists())
                .andExpect(jsonPath("$['components']['schemas']['EpisodeDetectionRequest']['properties']['episodeConfirmations']")
                        .doesNotExist())
                .andExpect(jsonPath("$['components']['schemas']['EpisodeUploadRequest']['properties']['episodeConfirmations']")
                        .exists())
                .andExpect(jsonPath("$['components']['schemas']['EpisodeUploadRequest']['properties']['uploadType']['enum']")
                        .value(containsInAnyOrder(
                                "SINGLE_EPISODE",
                                "MULTI_EPISODE_SINGLE_FILE",
                                "MULTI_EPISODE_MULTI_FILE"
                        )))
                .andExpect(jsonPath("$['components']['schemas']['EpisodeUploadRequest']['properties']['episodes']")
                        .doesNotExist())
                .andExpect(jsonPath("$['components']['schemas']['EpisodeUploadConfirmationRequest']['properties']['detectionOrder']")
                        .exists())
                .andExpect(jsonPath("$['components']['schemas']['EpisodeDetectionResponse']['properties']['detectedEpisodes']")
                        .exists())
                .andExpect(jsonPath("$['components']['schemas']['EpisodeDetectionResponse']['required']")
                        .value(containsInAnyOrder(
                                "uploadType",
                                "episodeCount",
                                "totalCharCount",
                                "detectedEpisodes"
                        )))
                .andExpect(jsonPath("$['components']['schemas']['EpisodeDetectionResponse']['properties']['episodes']")
                        .doesNotExist())
                .andExpect(jsonPath("$['components']['schemas']['DetectedEpisodeResponse']['properties']['detectionOrder']")
                        .exists())
                .andExpect(jsonPath("$['components']['schemas']['DetectedEpisodeResponse']['properties']['sourceFileIndex']")
                        .exists())
                .andExpect(jsonPath("$['components']['schemas']['DetectedEpisodeResponse']['properties']['sourceHeading']")
                        .exists())
                .andExpect(jsonPath("$['components']['schemas']['DetectedEpisodeResponse']['required']")
                        .value(containsInAnyOrder(
                                "detectionOrder",
                                "sourceFileIndex",
                                "episodeNo",
                                "title",
                                "sourceHeading",
                                "charCount",
                                "content"
                        )))
                .andExpect(jsonPath("$['components']['schemas']['DetectedEpisodeResponse']['properties']['tempId']")
                        .doesNotExist())
                .andExpect(jsonPath("$['components']['schemas']['EpisodeUploadResponse']['properties']['createdEpisodes']")
                        .exists())
                .andExpect(jsonPath("$['components']['schemas']['EpisodeUploadResponse']['required']")
                        .value(containsInAnyOrder(
                                "batchId",
                                "uploadType",
                                "status",
                                "episodeCount",
                                "createdEpisodes",
                                "files"
                        )))
                .andExpect(jsonPath("$['components']['schemas']['EpisodeUploadResponse']['properties']['episodes']")
                        .doesNotExist())
                .andExpect(jsonPath("$['components']['schemas']['UploadFileResponse']['properties']['episodeStartNo']")
                        .exists())
                .andExpect(jsonPath("$['components']['schemas']['UploadFileResponse']['properties']['episodeEndNo']")
                        .exists())
                .andExpect(jsonPath("$['components']['schemas']['UploadFileResponse']['properties']['episodeCount']")
                        .exists())
                .andExpect(jsonPath("$['components']['schemas']['UploadFileResponse']['properties']['detectedEpisodeStartNo']")
                        .doesNotExist());
    }
}
