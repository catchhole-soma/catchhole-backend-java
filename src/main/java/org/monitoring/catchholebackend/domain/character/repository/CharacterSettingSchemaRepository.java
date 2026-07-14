package org.monitoring.catchholebackend.domain.character.repository;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CharacterSettingSchemaRepository extends JpaRepository<CharacterSettingSchema, UUID> {

    @Query("""
            SELECT settingSchema
            FROM CharacterSettingSchema settingSchema
            WHERE settingSchema.enabled = true
              AND (settingSchema.work IS NULL OR settingSchema.work.id = :workId)
            ORDER BY settingSchema.schemaKey ASC
            """)
    List<CharacterSettingSchema> findAllActiveForWork(@Param("workId") UUID workId);
}
