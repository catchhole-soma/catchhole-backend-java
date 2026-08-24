package org.monitoring.catchholebackend.domain.legal.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.legal.entity.LegalDocument;
import org.monitoring.catchholebackend.domain.legal.repository.LegalDocumentRepository;
import org.monitoring.catchholebackend.domain.legal.type.LegalDocumentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("공개 법률 문서 API 통합")
class LegalDocumentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LegalDocumentRepository legalDocumentRepository;

    @Test
    @DisplayName("인증 없이 현재 이용약관과 개인정보처리방침 Markdown을 조회한다")
    void getCurrentDocumentsWithoutAuthentication() throws Exception {
        LegalDocument terms = legalDocumentRepository.save(document(LegalDocumentType.TERMS_OF_SERVICE));
        LegalDocument privacy = legalDocumentRepository.save(document(LegalDocumentType.PRIVACY_POLICY));

        mockMvc.perform(get("/api/v1/legal-documents/current").queryParam("locale", "ko-KR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.locale").value("ko-KR"))
                .andExpect(jsonPath("$.data.termsOfService.id").value(terms.getId()))
                .andExpect(jsonPath("$.data.termsOfService.contentMarkdown").value("# 이용약관"))
                .andExpect(jsonPath("$.data.privacyPolicy.id").value(privacy.getId()))
                .andExpect(jsonPath("$.data.privacyPolicy.contentMarkdown").value("# 개인정보처리방침"));
    }

    @Test
    @DisplayName("DRAFT 문서는 식별자를 알아도 공개하지 않는다")
    void getDocumentDoesNotExposeDraft() throws Exception {
        LegalDocument draft = legalDocumentRepository.save(LegalDocument.draft(
                LegalDocumentType.TERMS_OF_SERVICE,
                "ko-KR",
                "2026-08-24.2",
                "검토 중 이용약관",
                "# 아직 공개하지 않음",
                "c".repeat(64)
        ));

        mockMvc.perform(get("/api/v1/legal-documents/{documentId}", draft.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LEGAL_DOCUMENT_NOT_FOUND"));
    }

    private LegalDocument document(LegalDocumentType type) {
        return LegalDocument.published(
                type,
                "ko-KR",
                "2026-08-24",
                type == LegalDocumentType.TERMS_OF_SERVICE ? "CatchHole 이용약관" : "CatchHole 개인정보처리방침",
                type == LegalDocumentType.TERMS_OF_SERVICE ? "# 이용약관" : "# 개인정보처리방침",
                type == LegalDocumentType.TERMS_OF_SERVICE ? "a".repeat(64) : "b".repeat(64),
                LocalDate.of(2026, 8, 24),
                LocalDateTime.of(2026, 8, 24, 18, 0)
        );
    }
}
