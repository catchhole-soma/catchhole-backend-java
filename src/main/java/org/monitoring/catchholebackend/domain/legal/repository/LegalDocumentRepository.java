package org.monitoring.catchholebackend.domain.legal.repository;

import java.util.List;
import java.util.Optional;
import org.monitoring.catchholebackend.domain.legal.entity.LegalDocument;
import org.monitoring.catchholebackend.domain.legal.type.LegalDocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LegalDocumentRepository extends JpaRepository<LegalDocument, Long> {

    List<LegalDocument> findAllByLocaleAndStatus(String locale, LegalDocumentStatus status);

    Optional<LegalDocument> findByIdAndStatusNot(Long id, LegalDocumentStatus status);
}
