package org.monitoring.catchholebackend.domain.validation.repository;

import java.util.*;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationRequestKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ValidationRequestKeyRepository extends JpaRepository<ValidationRequestKey, UUID> {
    Optional<ValidationRequestKey> findByWorkIdAndRequestKey(UUID workId, String requestKey);
}
