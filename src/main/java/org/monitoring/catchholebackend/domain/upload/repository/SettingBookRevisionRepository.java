package org.monitoring.catchholebackend.domain.upload.repository;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingBookRevisionRepository extends JpaRepository<SettingBookRevision, UUID> {
    java.util.Optional<SettingBookRevision> findByIdAndSettingBookId(UUID id, UUID settingBookId);
}
