package org.monitoring.catchholebackend.domain.legal.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.monitoring.catchholebackend.domain.legal.entity.LegalDocument;
import org.monitoring.catchholebackend.domain.legal.type.LegalDocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LegalDocumentRepository extends JpaRepository<LegalDocument, Long> {

    List<LegalDocument> findAllByLocaleAndStatus(String locale, LegalDocumentStatus status);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            SELECT document
            FROM LegalDocument document
            WHERE document.locale = :locale
              AND document.status = :status
            """)
    List<LegalDocument> findAllByLocaleAndStatusForSignup(
            @Param("locale") String locale,
            @Param("status") LegalDocumentStatus status
    );

    Optional<LegalDocument> findByIdAndStatusNot(Long id, LegalDocumentStatus status);
}
