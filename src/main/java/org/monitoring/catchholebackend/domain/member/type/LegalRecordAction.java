package org.monitoring.catchholebackend.domain.member.type;

import org.monitoring.catchholebackend.domain.legal.type.LegalDocumentType;

public enum LegalRecordAction {
    AGREED,
    ACKNOWLEDGED;

    public static LegalRecordAction forDocumentType(LegalDocumentType documentType) {
        return switch (documentType) {
            case TERMS_OF_SERVICE -> AGREED;
            case PRIVACY_POLICY -> ACKNOWLEDGED;
        };
    }
}
