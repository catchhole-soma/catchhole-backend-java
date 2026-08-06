package org.monitoring.catchholebackend.domain.worldsetting.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorldSettingRepository extends JpaRepository<WorldSetting, UUID> {

    long countByWorkId(UUID workId);

    Optional<WorldSetting> findByIdAndWorkId(UUID id, UUID workId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select worldSetting
            from WorldSetting worldSetting
            where worldSetting.id = :id
              and worldSetting.work.id = :workId
            """)
    Optional<WorldSetting> findByIdAndWorkIdForUpdate(
            @Param("id") UUID id,
            @Param("workId") UUID workId
    );

    Optional<WorldSetting> findByWorkIdAndCategoryAndNormalizedSubjectName(
            UUID workId,
            WorldSettingCategory category,
            String normalizedSubjectName
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select worldSetting
            from WorldSetting worldSetting
            where worldSetting.work.id = :workId
              and worldSetting.category = :category
              and worldSetting.normalizedSubjectName = :normalizedSubjectName
            """)
    Optional<WorldSetting> findByIdentityForUpdate(
            @Param("workId") UUID workId,
            @Param("category") WorldSettingCategory category,
            @Param("normalizedSubjectName") String normalizedSubjectName
    );

    boolean existsByWorkIdAndCategoryAndNormalizedSubjectNameAndIdNot(
            UUID workId,
            WorldSettingCategory category,
            String normalizedSubjectName,
            UUID id
    );

    @Query(
            value = """
                    SELECT world_setting.*
                    FROM world_settings world_setting
                    WHERE world_setting.work_id = :workId
                      AND (:category IS NULL OR world_setting.category = :category)
                      AND (
                          :query = ''
                          OR POSITION(LOWER(:query) IN LOWER(world_setting.subject_name)) > 0
                          OR POSITION(LOWER(:query) IN LOWER(CAST(world_setting.properties_json AS VARCHAR))) > 0
                      )
                    ORDER BY
                      CASE world_setting.category
                          WHEN 'RACE' THEN 1
                          WHEN 'FACTION' THEN 2
                          WHEN 'LOCATION' THEN 3
                          WHEN 'MONSTER' THEN 4
                          WHEN 'POWER_SYSTEM' THEN 5
                          WHEN 'WORLD_RULE_HISTORY' THEN 6
                          WHEN 'IMPORTANT_ITEM' THEN 7
                          ELSE 8
                      END,
                      LOWER(world_setting.subject_name),
                      world_setting.subject_name,
                      world_setting.id
                    """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM world_settings world_setting
                    WHERE world_setting.work_id = :workId
                      AND (:category IS NULL OR world_setting.category = :category)
                      AND (
                          :query = ''
                          OR POSITION(LOWER(:query) IN LOWER(world_setting.subject_name)) > 0
                          OR POSITION(LOWER(:query) IN LOWER(CAST(world_setting.properties_json AS VARCHAR))) > 0
                      )
                    """,
            nativeQuery = true
    )
    Page<WorldSetting> searchCategorySubjectAsc(
            @Param("workId") UUID workId,
            @Param("query") String query,
            @Param("category") String category,
            Pageable pageable
    );

    @Query(
            value = """
                    SELECT world_setting.*
                    FROM world_settings world_setting
                    WHERE world_setting.work_id = :workId
                      AND (:category IS NULL OR world_setting.category = :category)
                      AND (
                          :query = ''
                          OR POSITION(LOWER(:query) IN LOWER(world_setting.subject_name)) > 0
                          OR POSITION(LOWER(:query) IN LOWER(CAST(world_setting.properties_json AS VARCHAR))) > 0
                      )
                    ORDER BY world_setting.updated_at DESC, world_setting.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM world_settings world_setting
                    WHERE world_setting.work_id = :workId
                      AND (:category IS NULL OR world_setting.category = :category)
                      AND (
                          :query = ''
                          OR POSITION(LOWER(:query) IN LOWER(world_setting.subject_name)) > 0
                          OR POSITION(LOWER(:query) IN LOWER(CAST(world_setting.properties_json AS VARCHAR))) > 0
                      )
                    """,
            nativeQuery = true
    )
    Page<WorldSetting> searchUpdatedDesc(
            @Param("workId") UUID workId,
            @Param("query") String query,
            @Param("category") String category,
            Pageable pageable
    );
}
