package org.monitoring.catchholebackend.global.config.swagger;

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
@DisplayName("OpenAPI 인증 계약")
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
                        .value(20));
    }
}
