package org.monitoring.catchholebackend.domain.member.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum LegalDocumentType {
    TERMS_OF_SERVICE("2026-08-23", LegalRecordAction.AGREED),
    PRIVACY_POLICY("2026-08-23", LegalRecordAction.ACKNOWLEDGED);

    private final String currentVersion;
    private final LegalRecordAction recordAction;
}
