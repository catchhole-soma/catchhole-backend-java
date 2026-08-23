package org.monitoring.catchholebackend.global.config.swagger;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
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
                .andExpect(jsonPath("$['paths']['/api/v1/auth/signup']['post']['responses']['503']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/auth/phone-verifications']['post']['operationId']")
                        .value("requestPhoneVerification"))
                .andExpect(jsonPath("$['paths']['/api/v1/auth/phone-verifications']['post']['security']")
                        .doesNotExist())
                .andExpect(jsonPath("$['paths']['/api/v1/auth/phone-verifications']['post']['parameters']")
                        .doesNotExist())
                .andExpect(jsonPath("$['paths']['/api/v1/auth/phone-verifications/{verificationId}/confirm']['post']['operationId']")
                        .value("confirmPhoneVerification"))
                .andExpect(jsonPath("$['components']['schemas']['PhoneVerificationSendRequest']['properties']['phoneNumber']['pattern']")
                        .value("^010\\d{8}$"))
                .andExpect(jsonPath("$['components']['schemas']['PhoneVerificationRequest']")
                        .doesNotExist())
                .andExpect(jsonPath("$['components']['schemas']['PhoneVerificationSendResponse']['properties']['verificationId']['example']")
                        .value("verification-id-example"))
                .andExpect(jsonPath("$['components']['schemas']['PhoneVerificationConfirmRequest']['properties']['code']['pattern']")
                        .value("^\\d{6}$"))
                .andExpect(jsonPath("$['components']['schemas']['PhoneVerificationConfirmResponse']['properties']['phoneVerificationToken']['example']")
                        .value("phone-verification-token-example"))
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
                .andExpect(jsonPath("$['components']['schemas']['AuthSignupRequest']['properties']['phoneNumber']")
                        .doesNotExist())
                .andExpect(jsonPath("$['components']['schemas']['AuthSignupRequest']['properties']['phoneVerificationToken']")
                        .exists())
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
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/analysis-jobs/batches']['get']['operationId']")
                        .value("getAnalysisBatches"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/analysis-jobs/batches']['get']['parameters'][*]['name']")
                        .value(containsInAnyOrder("workId", "page", "size")))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/analysis-jobs/batches']['get']['parameters'][2]['schema']['maximum']")
                        .value(20))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/analysis-jobs/batches']['get']['responses']['400']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/analysis-jobs/batches']['get']['responses']['401']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/analysis-jobs/batches']['get']['responses']['404']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['components']['schemas']['PageResponseAnalysisBatchSummaryResponse']['properties']['content']['items']['$ref']")
                        .value("#/components/schemas/AnalysisBatchSummaryResponse"))
                .andExpect(jsonPath("$['components']['schemas']['AnalysisBatchSummaryResponse']['properties']['status']['enum']")
                        .value(org.hamcrest.Matchers.hasItem("CANCELED")))
                .andExpect(jsonPath("$['components']['schemas']['AnalysisBatchJobGroupResponse']['properties']['canceledJobCount']")
                        .exists())
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
                .andExpect(jsonPath("$['components']['schemas']['CharacterDetailResponse']['properties']['currentAgeFact']['deprecated']")
                        .value(true))
                .andExpect(jsonPath("$['components']['schemas']['CharacterDetailResponse']['properties']['currentLevelFact']['deprecated']")
                        .value(true))
                .andExpect(jsonPath("$['components']['schemas']['CharacterDetailResponse']['properties']['currentAgeSourceFacts']['items']['$ref']")
                        .value("#/components/schemas/CharacterFactReferenceResponse"))
                .andExpect(jsonPath("$['components']['schemas']['CharacterDetailResponse']['properties']['currentLevelSourceFacts']['items']['$ref']")
                        .value("#/components/schemas/CharacterFactReferenceResponse"))
                .andExpect(jsonPath("$['components']['schemas']['CharacterFactReferenceResponse']['required']")
                        .value(org.hamcrest.Matchers.hasItems("characterFactId", "hasEvidence")))
                .andExpect(jsonPath("$['components']['schemas']['CharacterFactReferenceResponse']['properties']['sourceEpisodeId']")
                        .exists())
                .andExpect(jsonPath("$['components']['schemas']['CharacterFactReferenceResponse']['properties']['sourceEpisodeNo']")
                        .exists())
                .andExpect(jsonPath(
                        "$['paths']['/api/v1/works/{workId}/character-facts/{characterFactId}/evidence']['get']['operationId']"
                ).value("getCharacterFactEvidence"))
                .andExpect(jsonPath(
                        "$['paths']['/api/v1/works/{workId}/character-facts/{characterFactId}/evidence']['get']['responses']['400']['content']['application/json']['schema']['$ref']"
                ).value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath(
                        "$['paths']['/api/v1/works/{workId}/character-facts/{characterFactId}/evidence']['get']['responses']['401']['content']['application/json']['schema']['$ref']"
                ).value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath(
                        "$['paths']['/api/v1/works/{workId}/character-facts/{characterFactId}/evidence']['get']['responses']['404']['content']['application/json']['schema']['$ref']"
                ).value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['components']['schemas']['CharacterFactEvidenceResponse']['required']")
                        .value(org.hamcrest.Matchers.hasItems("characterFactId", "evidenceSpans")))
                .andExpect(jsonPath("$['components']['schemas']['CharacterSettingResponse']['properties']['attributeNameEditable']['type']")
                        .value("boolean"))
                .andExpect(jsonPath("$['components']['schemas']['CharacterSettingResponse']['properties']['attributeNamePrefix']")
                        .exists())
                .andExpect(jsonPath("$['components']['schemas']['CharacterSettingResponse']['properties']['displayNameEditable']['type']")
                        .value("boolean"))
                .andExpect(jsonPath("$['components']['schemas']['CharacterSettingResponse']['required']")
                        .value(org.hamcrest.Matchers.hasItems(
                                "attributeNameEditable",
                                "displayNameEditable"
                        )))
                .andExpect(jsonPath("$['components']['schemas']['CharacterSettingResponse']['properties']['characterFactId']['deprecated']")
                        .value(true))
                .andExpect(jsonPath("$['components']['schemas']['CharacterSettingResponse']['properties']['hasEvidence']['deprecated']")
                        .value(true))
                .andExpect(jsonPath("$['components']['schemas']['CharacterSettingResponse']['properties']['sourceFacts']['items']['$ref']")
                        .value("#/components/schemas/CharacterFactReferenceResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates']['get']['operationId']")
                        .value("getSettingCandidates"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates']['get']['parameters'][*]['name']")
                        .value(containsInAnyOrder(
                                "workId",
                                "batchId",
                                "reviewStatus",
                                "matchStatuses",
                                "page",
                                "size",
                                "includeLegacyCandidates"
                        )))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates']['get']['parameters'][1]['required']")
                        .value(true))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates']['get']['parameters'][3]['schema']['type']")
                        .value("array"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates']['get']['parameters'][3]['schema']['items']['enum']")
                        .value(containsInAnyOrder(
                                "MATCHED",
                                "AUTO_MATCHED_BY_NAME",
                                "UNRESOLVED",
                                "AMBIGUOUS"
                        )))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates']['get']['parameters'][5]['schema']['maximum']")
                        .value(100))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates']['get']['parameters'][6]['schema']['default']")
                        .value(true))
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
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']['properties']['candidateKind']['enum']")
                        .value(containsInAnyOrder("SETTING", "CHARACTER_DISCOVERY")))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']['properties']['attributeName']['type']")
                        .value(containsInAnyOrder("string", "null")))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']['properties']['valueType']['anyOf'][0]['enum']")
                        .value(containsInAnyOrder("STRING", "NUMBER", "BOOLEAN", "JSON", "UNKNOWN")))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']['properties']['valueType']['anyOf'][1]['type']")
                        .value("null"))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']['properties']['valueJson']['anyOf'][0]['$ref']")
                        .value("#/components/schemas/JsonNode"))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']['properties']['valueJson']['anyOf'][1]['type']")
                        .value("null"))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']['properties']['valueJson']['$ref']")
                        .doesNotExist())
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']['properties']['valueJson']['type']")
                        .doesNotExist())
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']['properties']['attributeNameEditable']['type']")
                        .value("boolean"))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']['properties']['attributeNamePrefix']")
                        .exists())
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']['required']")
                        .value(org.hamcrest.Matchers.hasItem("attributeNameEditable")))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}']['get']['operationId']")
                        .value("getSettingCandidate"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}']['get']['parameters'][*]['name']")
                        .value(containsInAnyOrder("workId", "batchId", "candidateId")))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}']['get']['responses']['400']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}']['get']['responses']['401']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}']['get']['responses']['404']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}']['patch']['operationId']")
                        .value("updateSettingCandidate"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}']['patch']['responses']['400']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}']['patch']['responses']['401']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}']['patch']['responses']['404']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}']['patch']['responses']['409']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateUpdateRequest']['required']")
                        .value(containsInAnyOrder("attributeName")))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateUpdateRequest']['properties']['attributeName']")
                        .exists())
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateUpdateRequest']['properties']['attributeValue']")
                        .exists())
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateUpdateRequest']['properties']['entityName']")
                        .doesNotExist())
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateUpdateRequest']['properties']['valueType']")
                        .doesNotExist())
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateUpdateRequest']['properties']['valueJson']")
                        .doesNotExist())
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateUpdateRequest']['properties']['evidenceSpans']")
                        .doesNotExist())
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}/character-match']['patch']['operationId']")
                        .value("updateSettingCandidateCharacterMatch"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}/character-match']['patch']['description']")
                        .value(containsString("CREATE_NEW는 캐릭터를 즉시 생성하거나 후보를 확정하지 않으며")))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateCharacterMatchRequest']['properties']['entityName']['description']")
                        .value(containsString("confirm 전 새 캐릭터 등록 예정인 UNRESOLVED 상태")))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}/character-match']['patch']['responses']['400']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}/character-match']['patch']['responses']['401']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}/character-match']['patch']['responses']['404']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}/character-match']['patch']['responses']['409']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/group-character-match']['patch']['operationId']")
                        .value("updateSettingCandidateGroupCharacterMatch"))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateGroupCharacterMatchRequest']['required']")
                        .value(containsInAnyOrder("batchId", "candidateIds", "resolutionType")))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateGroupCharacterMatchRequest']['properties']['candidateIds']['maxItems']")
                        .doesNotExist())
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateGroupConfirmRequest']['properties']['candidates']['maxItems']")
                        .doesNotExist())
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm']['post']['operationId']")
                        .value("confirmSettingCandidate"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm']['post']['responses']['400']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm']['post']['responses']['401']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm']['post']['responses']['404']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm']['post']['responses']['409']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}/dismiss']['post']['operationId']")
                        .value("dismissSettingCandidate"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}/dismiss']['post']['responses']['400']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}/dismiss']['post']['responses']['401']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}/dismiss']['post']['responses']['404']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/setting-candidates/{candidateId}/dismiss']['post']['responses']['409']['content']['application/json']['schema']['$ref']")
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
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/episodes/{episodeId}']['delete']['description']")
                        .value(containsString("업로드 원본의 모든 저장 버전")))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/episodes/{episodeId}']['delete']['description']")
                        .value(containsString("복구는 지원하지 않습니다")))
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

    @Test
    @DisplayName("AI 토큰 공개·내부 API의 경로와 요청·실패 계약을 노출한다")
    void openApiContractExposesAiTokenUsageOperations() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['paths']['/api/v1/ai-token-usages/me']['get']['operationId']")
                        .value("getMyAiTokenUsage"))
                .andExpect(jsonPath("$['paths']['/api/v1/ai-token-usages/me']['get']['description']")
                        .value(containsString("남은 사용량")))
                .andExpect(jsonPath("$['paths']['/api/internal/v1/ai-token-usages/reserve']['post']['operationId']")
                        .value("reserveAiTokens"))
                .andExpect(jsonPath("$['paths']['/api/internal/v1/ai-token-usages/{requestId}/settle']['post']['operationId']")
                        .value("settleAiTokens"))
                .andExpect(jsonPath("$['paths']['/api/internal/v1/ai-token-usages/{requestId}/release']['post']['operationId']")
                        .value("releaseAiTokens"))
                .andExpect(jsonPath("$['paths']['/api/internal/v1/ai-token-usages/reserve']['post']['responses']['400']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/internal/v1/ai-token-usages/reserve']['post']['responses']['401']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/internal/v1/ai-token-usages/reserve']['post']['responses']['404']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/internal/v1/ai-token-usages/reserve']['post']['responses']['409']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['components']['schemas']['AiTokenReserveRequest']['description']")
                        .value("AI provider 요청 전 토큰 예약 요청"))
                .andExpect(jsonPath("$['components']['schemas']['AiTokenReserveRequest']['properties']['reservedTokens']['description']")
                        .value(containsString("예약할 토큰 수")))
                .andExpect(jsonPath("$['components']['schemas']['AiTokenUsageResponse']['properties']['remainingPercent']['description']")
                        .value(containsString("남은 사용량 비율")));
    }

    @Test
    @DisplayName("세계관 직접 수정 version과 재비교 실패 응답 계약을 노출한다")
    void openApiContractExposesWorldSettingMutationContracts() throws Exception {
        String candidatePath = "$['paths']['/api/v1/works/{workId}/world-setting-candidates/{candidateId}']";
        String candidateDecisionPath = "$['paths']['/api/v1/works/{workId}/world-setting-candidates/decisions']['patch']";
        String retryComparison = "$['paths']['/api/v1/works/{workId}/world-setting-candidates/{candidateId}/recompare']['post']";

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['components']['schemas']['WorldSettingIdentityUpdateRequest']['required']")
                        .value(org.hamcrest.Matchers.hasItem("version")))
                .andExpect(jsonPath("$['components']['schemas']['WorldSettingPropertyCreateRequest']['required']")
                        .value(org.hamcrest.Matchers.hasItem("version")))
                .andExpect(jsonPath("$['components']['schemas']['WorldSettingPropertyUpdateRequest']['required']")
                        .value(org.hamcrest.Matchers.hasItem("version")))
                .andExpect(jsonPath("$['components']['schemas']['WorldSettingDetailResponse']['required']")
                        .value(org.hamcrest.Matchers.hasItem("version")))
                .andExpect(jsonPath(candidatePath + "['patch']").doesNotExist())
                .andExpect(jsonPath(candidateDecisionPath + "['operationId']")
                        .value("updateWorldSettingCandidateDecisions"))
                .andExpect(jsonPath(candidateDecisionPath
                        + "['requestBody']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/WorldSettingCandidateDecisionUpdateRequest"))
                .andExpect(jsonPath("$['components']['schemas']['WorldSettingCandidateDecisionUpdateRequest']"
                        + "['properties']['candidates']['items']['$ref']")
                        .value("#/components/schemas/WorldSettingCandidateDecisionUpdateItem"))
                .andExpect(jsonPath(candidateDecisionPath
                        + "['responses']['200']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonResponseWorldSettingCandidateDecisionUpdateResponse"))
                .andExpect(jsonPath(retryComparison + "['responses']['401']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath(retryComparison + "['responses']['404']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath(retryComparison + "['responses']['409']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"));
    }

    @Test
    @DisplayName("분석 실패 코드와 세계관 토큰 중단 일괄 재개 계약을 노출한다")
    void openApiContractExposesTokenInterruptedResumeContracts() throws Exception {
        String resumePath = "$['paths']['/api/v1/works/{workId}/world-setting-candidates"
                + "/batches/{batchId}/resume-token-interrupted']['post']";

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(resumePath + "['operationId']")
                        .value("resumeTokenInterruptedWorldSettingComparisons"))
                .andExpect(jsonPath(resumePath
                        + "['responses']['200']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonResponseWorldSettingTokenInterruptedResumeResponse"))
                .andExpect(jsonPath(resumePath
                        + "['responses']['409']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath("$['components']['schemas']['AnalysisJobResponse']"
                        + "['properties']['failureCode']['enum']")
                        .value(org.hamcrest.Matchers.hasItems(
                                "AI_TOKEN_QUOTA_EXHAUSTED",
                                "LLM_OUTPUT_TRUNCATED",
                                "LLM_NETWORK_ERROR",
                                "LLM_PROVIDER_ERROR",
                                "LLM_RESPONSE_PARSE_ERROR",
                                "COMPARISON_VALIDATION_FAILED"
                        )))
                .andExpect(jsonPath("$['components']['schemas']['AnalysisJobResponse']"
                        + "['properties']['tokenInterruptedAfterExtraction']").exists())
                .andExpect(jsonPath("$['components']['schemas']['AnalysisBatchSummaryResponse']"
                        + "['properties']['worldSettingTokenInterruptedCandidateCount']").exists())
                .andExpect(jsonPath("$['components']['schemas']['AnalysisBatchSummaryResponse']"
                        + "['properties']['canResumeTokenInterruptedWorldSettingComparisons']").exists())
                .andExpect(jsonPath("$['components']['schemas']['WorldSettingCandidateListResponse']"
                        + "['properties']['tokenInterruptedComparisonCount']").exists())
                .andExpect(jsonPath("$['components']['schemas']['WorldSettingCandidateListResponse']"
                        + "['properties']['canResumeTokenInterruptedComparisons']").exists())
                .andExpect(jsonPath("$['components']['schemas']['WorldSettingCandidateListResponse']"
                        + "['properties']['activeComparisonJobCount']").exists())
                .andExpect(jsonPath("$['components']['schemas']['WorldSettingCandidateResponse']"
                        + "['properties']['comparisonFailureCode']['enum']")
                        .value(org.hamcrest.Matchers.hasItem("AI_TOKEN_QUOTA_EXHAUSTED")))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']"
                        + "['properties']['comparisonFailureCode']['enum']")
                        .value(org.hamcrest.Matchers.hasItem("LLM_PROVIDER_ERROR")))
                .andExpect(jsonPath("$['components']['schemas']['WorkerAnalysisJobFailRequest']"
                        + "['properties']['failureCode']['enum']")
                        .value(org.hamcrest.Matchers.hasItem("LLM_OUTPUT_TRUNCATED")))
                .andExpect(jsonPath("$['components']['schemas']['WorkerCharacterFactComparisonFailRequest']"
                        + "['properties']['failureCode']['enum']")
                        .value(org.hamcrest.Matchers.hasItem("LLM_PROVIDER_ERROR")));
    }

    @Test
    @DisplayName("캐릭터 Fact 비교의 현재 snapshot과 제거 대상 schema를 서로 다른 계약으로 노출한다")
    void openApiContractSeparatesCharacterFactComparisonSnapshotEntries() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['components']['schemas']['WorkerCharacterCurrentSnapshotEntry']"
                        + "['properties']['factValue']").exists())
                .andExpect(jsonPath("$['components']['schemas']['WorkerCharacterCurrentSnapshotEntry']"
                        + "['properties']['valueJson']").exists())
                .andExpect(jsonPath("$['components']['schemas']['WorkerCharacterCurrentSnapshotEntry']"
                        + "['properties']['valueJson']['anyOf'][0]['$ref']")
                        .value("#/components/schemas/JsonNode"))
                .andExpect(jsonPath("$['components']['schemas']['WorkerCharacterCurrentSnapshotEntry']"
                        + "['properties']['valueJson']['anyOf'][1]['type']")
                        .value("null"))
                .andExpect(jsonPath("$['components']['schemas']['WorkerCharacterRemovedSnapshotEntry']"
                        + "['properties']['factType']").exists())
                .andExpect(jsonPath("$['components']['schemas']['WorkerCharacterRemovedSnapshotEntry']"
                        + "['properties']['factKey']").exists())
                .andExpect(jsonPath("$['components']['schemas']['WorkerCharacterRemovedSnapshotEntry']"
                        + "['properties']['factValue']").doesNotExist());
    }

    @Test
    @DisplayName("nullable 캐릭터 JSON 필드는 JsonNode와 null의 합집합으로 노출한다")
    void openApiContractExposesNullableCharacterJsonAsUnion() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']"
                        + "['properties']['proposedValueJson']['anyOf'][0]['$ref']")
                        .value("#/components/schemas/JsonNode"))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']"
                        + "['properties']['proposedValueJson']['anyOf'][1]['type']")
                        .value("null"))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']"
                        + "['properties']['evidenceSpans']['anyOf'][0]['$ref']")
                        .value("#/components/schemas/JsonNode"))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']"
                        + "['properties']['evidenceSpans']['anyOf'][1]['type']")
                        .value("null"))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']"
                        + "['properties']['rawAiResultJson']['anyOf'][0]['$ref']")
                        .value("#/components/schemas/JsonNode"))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']"
                        + "['properties']['rawAiResultJson']['anyOf'][1]['type']")
                        .value("null"))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']"
                        + "['properties']['suggestedOperation']['anyOf'][1]['type']")
                        .value("null"))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']"
                        + "['properties']['temporalScope']['anyOf'][1]['type']")
                        .value("null"))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateResponse']"
                        + "['properties']['comparisonTargetFactType']['anyOf'][1]['type']")
                        .value("null"))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateSnapshotChangeResponse']"
                        + "['properties']['beforeValueJson']['anyOf'][1]['type']")
                        .value("null"))
                .andExpect(jsonPath("$['components']['schemas']['SettingCandidateSnapshotChangeResponse']"
                        + "['properties']['proposedValueJson']['anyOf'][1]['type']")
                        .value("null"))
                .andExpect(jsonPath("$['components']['schemas']['WorkerCharacterFactComparisonCompleteRequest']"
                        + "['properties']['proposedValueJson']['anyOf'][1]['type']")
                        .value("null"))
                .andExpect(jsonPath("$['components']['schemas']['WorkerCharacterFactComparisonCandidatePayload']"
                        + "['properties']['valueJson']['anyOf'][1]['type']")
                        .value("null"))
                .andExpect(jsonPath("$['components']['schemas']['WorkerCharacterPriorFactCandidate']"
                        + "['properties']['valueJson']['anyOf'][1]['type']")
                        .value("null"))
                .andExpect(jsonPath("$['components']['schemas']['WorkerCharacterPriorFactCandidate']"
                        + "['properties']['proposedValueJson']['anyOf'][1]['type']")
                        .value("null"))
                .andExpect(jsonPath("$['components']['schemas']['WorkerCharacterPriorFactCandidate']"
                        + "['properties']['suggestedOperation']['anyOf'][1]['type']")
                        .value("null"));
    }

    @Test
    @DisplayName("회원가입 계약은 약관 동의와 개인정보처리방침 확인만 필수로 받는다")
    void openApiContractRequiresSignupLegalAcknowledgements() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['components']['schemas']['AuthSignupRequest']['required']",
                        org.hamcrest.Matchers.hasItems(
                                "termsAccepted",
                                "privacyPolicyAcknowledged"
                        )))
                .andExpect(jsonPath("$['components']['schemas']['AuthSignupRequest']"
                        + "['properties']['aiProcessingConsent']").doesNotExist());
    }

    @Test
    @DisplayName("원고 업로드 계약에는 별도 동의 API와 동의 필드를 노출하지 않는다")
    void openApiContractDoesNotExposePerUploadManuscriptConsent() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['paths']['/api/v1/manuscript-consents']").doesNotExist())
                .andExpect(jsonPath("$['components']['schemas']['ManuscriptConsentRequest']").doesNotExist())
                .andExpect(jsonPath("$['components']['schemas']['ManuscriptConsentResponse']").doesNotExist())
                .andExpect(jsonPath("$['components']['schemas']['EpisodeUploadRequest']"
                        + "['properties']['policyVersion']").doesNotExist())
                .andExpect(jsonPath("$['components']['schemas']['EpisodeUploadRequest']"
                        + "['properties']['requiredProcessingConsent']").doesNotExist());
    }

    @Test
    @DisplayName("작품 영구 삭제 요청·상태 조회·재시도 계약과 결과 스키마를 공개한다")
    void openApiContractExposesWorkPurgeOperations() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}']['delete']['operationId']")
                        .value("deleteWork"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}']['delete']"
                        + "['requestBody']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/WorkPurgeCreateRequest"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}']['delete']"
                        + "['responses']['202']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CommonResponseWorkPurgeResponse"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/{workId}/purge-request']['get']['operationId']")
                        .value("getWorkPurgeRequestByWork"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/purge-requests/{requestId}']['get']['operationId']")
                        .value("getWorkPurgeRequest"))
                .andExpect(jsonPath("$['paths']['/api/v1/works/purge-requests/{requestId}/retry']['post']['operationId']")
                        .value("retryWorkPurgeRequest"))
                .andExpect(jsonPath("$['components']['schemas']['WorkPurgeCreateRequest']['required']")
                        .value(org.hamcrest.Matchers.hasItem("confirmation")))
                .andExpect(jsonPath("$['components']['schemas']['WorkPurgeCreateRequest']"
                        + "['properties']['confirmation']['pattern']")
                        .value("영구 삭제"))
                .andExpect(jsonPath("$['components']['schemas']['WorkPurgeResponse']['properties']['status']['enum']")
                        .value(containsInAnyOrder(
                                "REQUESTED", "PROCESSING", "COMPLETED", "PARTIAL_FAILED", "FAILED"
                        )))
                .andExpect(jsonPath("$['components']['schemas']['WorkPurgeResponse']"
                        + "['properties']['objectStorage']['$ref']")
                        .value("#/components/schemas/WorkPurgeStoreResultResponse"))
                .andExpect(jsonPath("$['components']['schemas']['WorkPurgeResponse']"
                        + "['properties']['database']['$ref']")
                        .value("#/components/schemas/WorkPurgeStoreResultResponse"));
    }

}
