package org.monitoring.catchholebackend.domain.legal.service;

import org.monitoring.catchholebackend.domain.legal.entity.LegalDocument;

public record SignupLegalDocuments(
        LegalDocument termsOfService,
        LegalDocument privacyPolicy
) {
}
