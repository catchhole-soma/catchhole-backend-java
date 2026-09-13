package org.monitoring.catchholebackend.domain.analysis.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:automatic-openapi;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "springdoc.paths-to-match=/api/v1/**,/api/internal/**"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "GH180_OPENAPI_OUTPUT", matches = "/tmp/gh180-upload-openapi.json")
@DisplayName("격리 DB에서 자동 반영 API 계약을 생성한다")
class AutomaticAnalysisOpenApiSnapshotTest {
    @Autowired MockMvc mvc;

    @Test
    @DisplayName("실제 MVC OpenAPI 응답을 프론트 SDK 생성용 파일로 저장한다")
    void exportCurrentOpenApi() throws Exception {
        byte[] schema = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        Files.write(Path.of(System.getenv("GH180_OPENAPI_OUTPUT")), schema);
    }
}
