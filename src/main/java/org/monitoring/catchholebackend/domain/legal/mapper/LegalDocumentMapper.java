package org.monitoring.catchholebackend.domain.legal.mapper;

import org.monitoring.catchholebackend.domain.legal.dto.response.LegalDocumentResponse;
import org.monitoring.catchholebackend.domain.legal.entity.LegalDocument;
import org.springframework.stereotype.Component;

@Component
public class LegalDocumentMapper {

    public LegalDocumentResponse toResponse(LegalDocument document) {
        return new LegalDocumentResponse(
                document.getId(),
                document.getDocumentType(),
                document.getLocale(),
                document.getDocumentVersion(),
                document.getTitle(),
                document.getContentMarkdown(),
                document.getContentHash(),
                document.getStatus(),
                document.getEffectiveDate(),
                document.getPublishedAt()
        );
    }
}
