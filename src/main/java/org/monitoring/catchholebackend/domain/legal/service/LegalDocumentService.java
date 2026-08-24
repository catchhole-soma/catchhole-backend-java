package org.monitoring.catchholebackend.domain.legal.service;

import org.monitoring.catchholebackend.domain.legal.dto.response.LegalDocumentBundleResponse;
import org.monitoring.catchholebackend.domain.legal.dto.response.LegalDocumentResponse;

public interface LegalDocumentService {

    LegalDocumentBundleResponse getCurrentDocuments(String locale);

    LegalDocumentResponse getPublicDocument(Long documentId);

    SignupLegalDocuments requireCurrentSignupDocuments(Long termsDocumentId, Long privacyPolicyDocumentId);
}
