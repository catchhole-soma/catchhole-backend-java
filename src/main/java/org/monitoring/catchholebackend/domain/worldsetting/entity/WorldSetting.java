package org.monitoring.catchholebackend.domain.worldsetting.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.worldsetting.exception.WorldSettingErrorCode;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingNameNormalizer;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
import org.monitoring.catchholebackend.global.exception.AppException;

@Getter
@Entity
@Table(
        name = "world_settings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_world_settings_work_category_subject",
                columnNames = {"work_id", "category", "normalized_subject_name"}
        ),
        indexes = {
                @Index(
                        name = "idx_world_settings_work_category_subject",
                        columnList = "work_id,category,normalized_subject_name,id"
                ),
                @Index(name = "idx_world_settings_work_updated", columnList = "work_id,updated_at,id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorldSetting extends BaseEntity {

    private static final int NAME_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "work_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_world_settings_work")
    )
    private Work work;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 40)
    private WorldSettingCategory category;

    @Column(name = "subject_name", nullable = false, length = NAME_MAX_LENGTH)
    private String subjectName;

    @Column(name = "normalized_subject_name", nullable = false, length = NAME_MAX_LENGTH)
    private String normalizedSubjectName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "properties_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode propertiesJson;

    @Column(name = "version", nullable = false)
    private long version;

    private WorldSetting(
            Work work,
            WorldSettingCategory category,
            String subjectName,
            String settingName,
            String settingValue
    ) {
        this.work = Objects.requireNonNull(work);
        this.category = Objects.requireNonNull(category);
        this.subjectName = requiredName(subjectName);
        this.normalizedSubjectName = WorldSettingNameNormalizer.duplicateKey(this.subjectName);
        this.propertiesJson = JsonNodeFactory.instance.objectNode()
                .put(requiredName(settingName), requiredValue(settingValue));
        this.version = 0;
    }

    private WorldSetting(
            Work work,
            WorldSettingCategory category,
            String subjectName,
            Map<String, String> properties
    ) {
        this.work = Objects.requireNonNull(work);
        this.category = Objects.requireNonNull(category);
        this.subjectName = requiredName(subjectName);
        this.normalizedSubjectName = WorldSettingNameNormalizer.duplicateKey(this.subjectName);
        this.propertiesJson = toPropertiesObject(properties);
        this.version = 0;
    }

    public static WorldSetting create(
            Work work,
            WorldSettingCategory category,
            String subjectName,
            String settingName,
            String settingValue
    ) {
        return new WorldSetting(work, category, subjectName, settingName, settingValue);
    }

    public static WorldSetting create(
            Work work,
            WorldSettingCategory category,
            String subjectName,
            Map<String, String> properties
    ) {
        return new WorldSetting(work, category, subjectName, properties);
    }

    public void validateVersion(long expectedVersion) {
        if (version != expectedVersion) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_VERSION_CONFLICT);
        }
    }

    public void changeIdentity(WorldSettingCategory category, String subjectName) {
        WorldSettingCategory normalizedCategory = Objects.requireNonNull(category);
        String normalizedDisplayName = requiredName(subjectName);
        String duplicateKey = WorldSettingNameNormalizer.duplicateKey(normalizedDisplayName);
        if (this.category == normalizedCategory && this.subjectName.equals(normalizedDisplayName)) {
            return;
        }
        this.category = normalizedCategory;
        this.subjectName = normalizedDisplayName;
        this.normalizedSubjectName = duplicateKey;
        this.version++;
    }

    public void addProperty(String settingName, String settingValue) {
        String normalizedName = requiredName(settingName);
        if (findStoredPropertyName(normalizedName) != null) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTY_DUPLICATED);
        }
        ObjectNode updated = propertiesObjectCopy();
        updated.put(normalizedName, requiredValue(settingValue));
        propertiesJson = updated;
        version++;
    }

    public void updateProperty(String currentSettingName, String settingName, String settingValue) {
        String storedName = findStoredPropertyName(requiredName(currentSettingName));
        if (storedName == null) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTY_NOT_FOUND);
        }

        String normalizedName = requiredName(settingName);
        String duplicateName = findStoredPropertyName(normalizedName);
        if (duplicateName != null && !duplicateName.equals(storedName)) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTY_DUPLICATED);
        }

        String normalizedValue = requiredValue(settingValue);
        if (storedName.equals(normalizedName) && propertiesJson.get(storedName).asText().equals(normalizedValue)) {
            return;
        }

        ObjectNode updated = propertiesObjectCopy();
        updated.remove(storedName);
        updated.put(normalizedName, normalizedValue);
        propertiesJson = updated;
        version++;
    }

    public boolean applyProperty(String settingName, String settingValue) {
        return applyProperties(Map.of(settingName, settingValue));
    }

    public boolean applyProperties(Map<String, String> properties) {
        ObjectNode updated = propertiesObjectCopy();
        Set<String> requestedNames = new HashSet<>();
        boolean changed = false;
        for (Map.Entry<String, String> property : properties.entrySet()) {
            String normalizedName = requiredName(property.getKey());
            String duplicateKey = WorldSettingNameNormalizer.duplicateKey(normalizedName);
            if (!requestedNames.add(duplicateKey)) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTY_DUPLICATED);
            }
            String normalizedValue = requiredValue(property.getValue());
            String storedName = findStoredPropertyName(updated, normalizedName);
            if (storedName != null
                    && storedName.equals(normalizedName)
                    && updated.get(storedName).asText().equals(normalizedValue)) {
                continue;
            }
            if (storedName != null) {
                updated.remove(storedName);
            }
            updated.put(normalizedName, normalizedValue);
            changed = true;
        }
        if (!changed) {
            return false;
        }
        propertiesJson = updated;
        version++;
        return true;
    }

    public boolean hasProperty(String settingName) {
        return findStoredPropertyName(requiredName(settingName)) != null;
    }

    public String getPropertyValue(String settingName) {
        String storedName = findStoredPropertyName(requiredName(settingName));
        return storedName == null ? null : propertiesJson.get(storedName).asText();
    }

    public String getStoredPropertyName(String settingName) {
        return findStoredPropertyName(requiredName(settingName));
    }

    public int getPropertyCount() {
        return propertiesJson.size();
    }

    private String findStoredPropertyName(String settingName) {
        return findStoredPropertyName(propertiesJson, settingName);
    }

    private String findStoredPropertyName(JsonNode properties, String settingName) {
        if (properties == null || !properties.isObject()) {
            return null;
        }
        String duplicateKey = WorldSettingNameNormalizer.duplicateKey(settingName);
        Iterator<String> fieldNames = properties.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (WorldSettingNameNormalizer.duplicateKey(fieldName).equals(duplicateKey)) {
                return fieldName;
            }
        }
        return null;
    }

    private static ObjectNode toPropertiesObject(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTIES_INVALID);
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        Set<String> requestedNames = new HashSet<>();
        for (Map.Entry<String, String> property : properties.entrySet()) {
            String name = requiredName(property.getKey());
            if (!requestedNames.add(WorldSettingNameNormalizer.duplicateKey(name))) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTY_DUPLICATED);
            }
            result.put(name, requiredValue(property.getValue()));
        }
        return result;
    }

    private ObjectNode propertiesObjectCopy() {
        validateProperties();
        return ((ObjectNode) propertiesJson).deepCopy();
    }

    @PrePersist
    @PreUpdate
    private void validateProperties() {
        if (propertiesJson == null || !propertiesJson.isObject() || propertiesJson.isEmpty()) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTIES_INVALID);
        }
        for (Map.Entry<String, JsonNode> field : propertiesJson.properties()) {
            if (requiredNameOrNull(field.getKey()) == null || field.getValue() == null || !field.getValue().isTextual()) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTIES_INVALID);
            }
        }
    }

    private static String requiredName(String value) {
        String normalized = requiredNameOrNull(value);
        if (normalized == null) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_INPUT_INVALID);
        }
        return normalized;
    }

    private static String requiredNameOrNull(String value) {
        String normalized = WorldSettingNameNormalizer.displayName(value);
        if (normalized == null || normalized.isEmpty() || normalized.length() > NAME_MAX_LENGTH) {
            return null;
        }
        return normalized;
    }

    private static String requiredValue(String value) {
        String normalized = WorldSettingNameNormalizer.displayName(value);
        if (normalized == null || normalized.isEmpty()) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_INPUT_INVALID);
        }
        return normalized;
    }
}
