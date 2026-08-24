package org.monitoring.catchholebackend.domain.legal.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.legal.dto.response.LegalDocumentBundleResponse;
import org.monitoring.catchholebackend.domain.legal.dto.response.LegalDocumentResponse;
import org.monitoring.catchholebackend.domain.legal.entity.LegalDocument;
import org.monitoring.catchholebackend.domain.legal.exception.LegalDocumentErrorCode;
import org.monitoring.catchholebackend.domain.legal.mapper.LegalDocumentMapper;
import org.monitoring.catchholebackend.domain.legal.repository.LegalDocumentRepository;
import org.monitoring.catchholebackend.domain.legal.type.LegalDocumentStatus;
import org.monitoring.catchholebackend.domain.legal.type.LegalDocumentType;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LegalDocumentServiceImpl implements LegalDocumentService {

    public static final String DEFAULT_LOCALE = "ko-KR";

    private final LegalDocumentRepository legalDocumentRepository;
    private final LegalDocumentMapper legalDocumentMapper;

    @Override
    public LegalDocumentBundleResponse getCurrentDocuments(String locale) {
        SignupLegalDocuments documents = findCurrentDocuments(locale);
        return new LegalDocumentBundleResponse(
                locale,
                legalDocumentMapper.toResponse(documents.termsOfService()),
                legalDocumentMapper.toResponse(documents.privacyPolicy())
        );
    }

    @Override
    public LegalDocumentResponse getPublicDocument(Long documentId) {
        LegalDocument document = legalDocumentRepository
                .findByIdAndStatusNot(documentId, LegalDocumentStatus.DRAFT)
                .orElseThrow(() -> new AppException(LegalDocumentErrorCode.LEGAL_DOCUMENT_NOT_FOUND));
        return legalDocumentMapper.toResponse(document);
    }

    @Override
    @Transactional
    public SignupLegalDocuments requireCurrentSignupDocuments(
            Long termsDocumentId,
            Long privacyPolicyDocumentId
    ) {
        SignupLegalDocuments current = findCurrentDocumentsForSignup(DEFAULT_LOCALE);
        if (!current.termsOfService().getId().equals(termsDocumentId)
                || !current.privacyPolicy().getId().equals(privacyPolicyDocumentId)) {
            throw new AppException(
                    LegalDocumentErrorCode.LEGAL_DOCUMENT_NOT_CURRENT,
                    Map.of(
                            "currentTermsDocumentId", current.termsOfService().getId(),
                            "currentPrivacyPolicyDocumentId", current.privacyPolicy().getId()
                    )
            );
        }
        return current;
    }

    private SignupLegalDocuments findCurrentDocuments(String locale) {
        List<LegalDocument> publishedDocuments = legalDocumentRepository
                .findAllByLocaleAndStatus(locale, LegalDocumentStatus.PUBLISHED);
        return toSignupLegalDocuments(publishedDocuments);
    }

    private SignupLegalDocuments findCurrentDocumentsForSignup(String locale) {
        List<LegalDocument> publishedDocuments = legalDocumentRepository
                .findAllByLocaleAndStatusForSignup(locale, LegalDocumentStatus.PUBLISHED);
        return toSignupLegalDocuments(publishedDocuments);
    }

    private SignupLegalDocuments toSignupLegalDocuments(List<LegalDocument> publishedDocuments) {
        Map<LegalDocumentType, LegalDocument> documentsByType = new EnumMap<>(LegalDocumentType.class);
        publishedDocuments.forEach(document -> documentsByType.put(document.getDocumentType(), document));

        LegalDocument terms = documentsByType.get(LegalDocumentType.TERMS_OF_SERVICE);
        LegalDocument privacy = documentsByType.get(LegalDocumentType.PRIVACY_POLICY);
        if (terms == null || privacy == null) {
            throw new AppException(LegalDocumentErrorCode.LEGAL_DOCUMENTS_UNAVAILABLE);
        }
        return new SignupLegalDocuments(terms, privacy);
    }
}
