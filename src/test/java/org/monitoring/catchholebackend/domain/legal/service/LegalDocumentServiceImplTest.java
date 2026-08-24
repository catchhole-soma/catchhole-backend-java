package org.monitoring.catchholebackend.domain.legal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.monitoring.catchholebackend.domain.legal.entity.LegalDocument;
import org.monitoring.catchholebackend.domain.legal.exception.LegalDocumentErrorCode;
import org.monitoring.catchholebackend.domain.legal.mapper.LegalDocumentMapper;
import org.monitoring.catchholebackend.domain.legal.repository.LegalDocumentRepository;
import org.monitoring.catchholebackend.domain.legal.type.LegalDocumentStatus;
import org.monitoring.catchholebackend.domain.legal.type.LegalDocumentType;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("법률 문서 서비스")
class LegalDocumentServiceImplTest {

    @Mock
    private LegalDocumentRepository legalDocumentRepository;

    private LegalDocumentServiceImpl legalDocumentService;
    private LegalDocument terms;
    private LegalDocument privacy;

    @BeforeEach
    void setUp() {
        legalDocumentService = new LegalDocumentServiceImpl(
                legalDocumentRepository,
                new LegalDocumentMapper()
        );
        terms = document(3L, LegalDocumentType.TERMS_OF_SERVICE, "# 이용약관");
        privacy = document(4L, LegalDocumentType.PRIVACY_POLICY, "# 개인정보처리방침");
    }

    @Test
    @DisplayName("현재 locale의 두 PUBLISHED Markdown 원문을 한 묶음으로 반환한다")
    void getCurrentDocumentsReturnsPublishedBundle() {
        when(legalDocumentRepository.findAllByLocaleAndStatus("ko-KR", LegalDocumentStatus.PUBLISHED))
                .thenReturn(List.of(privacy, terms));

        var response = legalDocumentService.getCurrentDocuments("ko-KR");

        assertThat(response.locale()).isEqualTo("ko-KR");
        assertThat(response.termsOfService().id()).isEqualTo(3L);
        assertThat(response.termsOfService().contentMarkdown()).isEqualTo("# 이용약관");
        assertThat(response.privacyPolicy().id()).isEqualTo(4L);
        assertThat(response.privacyPolicy().contentMarkdown()).isEqualTo("# 개인정보처리방침");
    }

    @Test
    @DisplayName("가입 요청 문서 ID가 현재 문서와 다르면 최신 문서 ID를 포함한 충돌을 반환한다")
    void requireCurrentSignupDocumentsRejectsStaleIds() {
        when(legalDocumentRepository.findAllByLocaleAndStatusForSignup(
                "ko-KR",
                LegalDocumentStatus.PUBLISHED
        ))
                .thenReturn(List.of(terms, privacy));

        assertThatThrownBy(() -> legalDocumentService.requireCurrentSignupDocuments(1L, 4L))
                .isInstanceOfSatisfying(AppException.class, exception -> {
                    assertThat(exception.getResultCode())
                            .isEqualTo(LegalDocumentErrorCode.LEGAL_DOCUMENT_NOT_CURRENT);
                    assertThat(exception.getErrorContext())
                            .containsEntry("currentTermsDocumentId", 3L)
                            .containsEntry("currentPrivacyPolicyDocumentId", 4L);
                });
    }

    @Test
    @DisplayName("현재 문서 종류 중 하나라도 없으면 불완전한 묶음을 공개하지 않는다")
    void getCurrentDocumentsRejectsIncompleteBundle() {
        when(legalDocumentRepository.findAllByLocaleAndStatus("ko-KR", LegalDocumentStatus.PUBLISHED))
                .thenReturn(List.of(terms));

        assertThatThrownBy(() -> legalDocumentService.getCurrentDocuments("ko-KR"))
                .isInstanceOfSatisfying(AppException.class, exception -> assertThat(exception.getResultCode())
                        .isEqualTo(LegalDocumentErrorCode.LEGAL_DOCUMENTS_UNAVAILABLE));
    }

    private LegalDocument document(Long id, LegalDocumentType type, String markdown) {
        LegalDocument document = LegalDocument.published(
                type,
                "ko-KR",
                "2026-08-24",
                type == LegalDocumentType.TERMS_OF_SERVICE ? "CatchHole 이용약관" : "CatchHole 개인정보처리방침",
                markdown,
                type == LegalDocumentType.TERMS_OF_SERVICE ? "a".repeat(64) : "b".repeat(64),
                LocalDate.of(2026, 8, 24),
                LocalDateTime.of(2026, 8, 24, 18, 0)
        );
        ReflectionTestUtils.setField(document, "id", id);
        return document;
    }
}
