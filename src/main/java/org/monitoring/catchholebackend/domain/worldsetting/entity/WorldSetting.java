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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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

    // 루트 문자열 설정 또는 범위 한 단계 아래의 문자열 설정만 저장한다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "properties_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode propertiesJson;

    @Column(name = "version", nullable = false)
    private long version;

    private WorldSetting(
            Work work,
            WorldSettingCategory category,
            String subjectName,
            List<Property> properties
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
        return create(work, category, subjectName, null, settingName, settingValue);
    }

    public static WorldSetting create(
            Work work,
            WorldSettingCategory category,
            String subjectName,
            String scopeName,
            String settingName,
            String settingValue
    ) {
        return new WorldSetting(
                work,
                category,
                subjectName,
                List.of(new Property(scopeName, settingName, settingValue))
        );
    }

    public static WorldSetting create(
            Work work,
            WorldSettingCategory category,
            String subjectName,
            Map<String, String> properties
    ) {
        if (properties == null) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTIES_INVALID);
        }
        return new WorldSetting(
                work,
                category,
                subjectName,
                properties.entrySet().stream()
                        .map(entry -> new Property(null, entry.getKey(), entry.getValue()))
                        .toList()
        );
    }

    public static WorldSetting create(
            Work work,
            WorldSettingCategory category,
            String subjectName,
            List<Property> properties
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
        addProperty(null, settingName, settingValue);
    }

    public void addProperty(String scopeName, String settingName, String settingValue) {
        Property property = normalizedProperty(scopeName, settingName, settingValue);
        ObjectNode updated = propertiesObjectCopy();
        if (findStoredPropertyPath(updated, property.scopeName(), property.settingName()) != null) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTY_DUPLICATED);
        }
        putNewProperty(updated, property);
        propertiesJson = updated;
        version++;
    }

    public void updateProperty(String currentSettingName, String settingName, String settingValue) {
        updateProperty(null, currentSettingName, null, settingName, settingValue);
    }

    public void updateProperty(
            String currentScopeName,
            String currentSettingName,
            String scopeName,
            String settingName,
            String settingValue
    ) {
        String normalizedCurrentScopeName = optionalName(currentScopeName);
        String normalizedCurrentSettingName = requiredName(currentSettingName);
        StoredPropertyPath storedPath = findStoredPropertyPath(
                propertiesJson,
                normalizedCurrentScopeName,
                normalizedCurrentSettingName
        );
        if (storedPath == null) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTY_NOT_FOUND);
        }

        Property target = normalizedProperty(scopeName, settingName, settingValue);
        String currentValue = getPropertyValue(storedPath.scopeName(), storedPath.settingName());
        if (samePath(storedPath.scopeName(), storedPath.settingName(), target.scopeName(), target.settingName())
                && Objects.equals(storedPath.scopeName(), target.scopeName())
                && Objects.equals(storedPath.settingName(), target.settingName())
                && Objects.equals(currentValue, target.value())) {
            return;
        }

        ObjectNode updated = propertiesObjectCopy();
        removeStoredProperty(updated, storedPath);
        if (findStoredPropertyPath(updated, target.scopeName(), target.settingName()) != null) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTY_DUPLICATED);
        }
        putNewProperty(updated, target);
        propertiesJson = updated;
        version++;
    }

    public void removeProperty(String scopeName, String settingName) {
        StoredPropertyPath storedPath = findStoredPropertyPath(
                propertiesJson,
                optionalName(scopeName),
                requiredName(settingName)
        );
        if (storedPath == null) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTY_NOT_FOUND);
        }
        if (getPropertyCount() == 1) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTIES_INVALID);
        }
        ObjectNode updated = propertiesObjectCopy();
        removeStoredProperty(updated, storedPath);
        propertiesJson = updated;
        version++;
    }

    public boolean applyProperty(String settingName, String settingValue) {
        return applyProperty(null, settingName, settingValue);
    }

    public boolean applyProperty(String scopeName, String settingName, String settingValue) {
        return applyProperties(List.of(new Property(scopeName, settingName, settingValue)));
    }

    public boolean applyProperties(Map<String, String> properties) {
        if (properties == null) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTIES_INVALID);
        }
        return applyProperties(properties.entrySet().stream()
                .map(entry -> new Property(null, entry.getKey(), entry.getValue()))
                .toList());
    }

    public boolean applyProperties(List<Property> properties) {
        if (properties == null || properties.isEmpty()) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTIES_INVALID);
        }
        List<Property> normalizedProperties = properties.stream()
                .map(property -> normalizedProperty(
                        property.scopeName(),
                        property.settingName(),
                        property.value()
                ))
                .toList();
        validateDistinctPaths(normalizedProperties);

        ObjectNode updated = propertiesObjectCopy();
        boolean changed = false;
        for (Property property : normalizedProperties) {
            changed |= upsertProperty(updated, property);
        }
        if (!changed) {
            return false;
        }
        propertiesJson = updated;
        version++;
        return true;
    }

    public boolean hasProperty(String settingName) {
        return hasProperty(null, settingName);
    }

    public boolean hasProperty(String scopeName, String settingName) {
        return findStoredPropertyPath(propertiesJson, optionalName(scopeName), requiredName(settingName)) != null;
    }

    public boolean hasPathConflict(String scopeName, String settingName) {
        String normalizedScopeName = optionalName(scopeName);
        String normalizedSettingName = requiredName(settingName);
        String topLevelName = findStoredFieldName(
                propertiesJson,
                normalizedScopeName == null ? normalizedSettingName : normalizedScopeName
        );
        if (topLevelName == null) {
            return false;
        }
        return normalizedScopeName == null
                ? propertiesJson.get(topLevelName).isObject()
                : propertiesJson.get(topLevelName).isTextual();
    }

    public String getPropertyValue(String settingName) {
        return getPropertyValue(null, settingName);
    }

    public String getPropertyValue(String scopeName, String settingName) {
        StoredPropertyPath storedPath = findStoredPropertyPath(
                propertiesJson,
                optionalName(scopeName),
                requiredName(settingName)
        );
        if (storedPath == null) {
            return null;
        }
        JsonNode value = storedPath.scopeName() == null
                ? propertiesJson.get(storedPath.settingName())
                : propertiesJson.get(storedPath.scopeName()).get(storedPath.settingName());
        return value.asText();
    }

    public String getStoredPropertyName(String settingName) {
        StoredPropertyPath path = getStoredPropertyPath(null, settingName);
        return path == null ? null : path.settingName();
    }

    public StoredPropertyPath getStoredPropertyPath(String scopeName, String settingName) {
        return findStoredPropertyPath(propertiesJson, optionalName(scopeName), requiredName(settingName));
    }

    public List<Property> getProperties() {
        validateProperties();
        List<Property> properties = new ArrayList<>();
        propertiesJson.properties().forEach(entry -> {
            if (entry.getValue().isTextual()) {
                properties.add(new Property(null, entry.getKey(), entry.getValue().asText()));
                return;
            }
            entry.getValue().properties().forEach(scopedEntry -> properties.add(
                    new Property(entry.getKey(), scopedEntry.getKey(), scopedEntry.getValue().asText())
            ));
        });
        return List.copyOf(properties);
    }

    public int getPropertyCount() {
        return getProperties().size();
    }

    private static ObjectNode toPropertiesObject(List<Property> properties) {
        if (properties == null || properties.isEmpty()) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTIES_INVALID);
        }
        List<Property> normalizedProperties = properties.stream()
                .map(property -> normalizedProperty(
                        property.scopeName(),
                        property.settingName(),
                        property.value()
                ))
                .toList();
        validateDistinctPaths(normalizedProperties);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        normalizedProperties.forEach(property -> putNewProperty(result, property));
        return result;
    }

    private static void validateDistinctPaths(List<Property> properties) {
        Set<String> requestedPaths = new HashSet<>();
        for (Property property : properties) {
            if (!requestedPaths.add(pathKey(property.scopeName(), property.settingName()))) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTY_DUPLICATED);
            }
        }
    }

    private static boolean upsertProperty(ObjectNode properties, Property property) {
        StoredPropertyPath storedPath = findStoredPropertyPath(
                properties,
                property.scopeName(),
                property.settingName()
        );
        if (storedPath == null) {
            putNewProperty(properties, property);
            return true;
        }
        JsonNode storedValue = storedPath.scopeName() == null
                ? properties.get(storedPath.settingName())
                : properties.get(storedPath.scopeName()).get(storedPath.settingName());
        if (Objects.equals(storedPath.scopeName(), property.scopeName())
                && Objects.equals(storedPath.settingName(), property.settingName())
                && storedValue.asText().equals(property.value())) {
            return false;
        }
        removeStoredProperty(properties, storedPath);
        putNewProperty(properties, property);
        return true;
    }

    private static void putNewProperty(ObjectNode properties, Property property) {
        if (property.scopeName() == null) {
            String storedTopLevelName = findStoredFieldName(properties, property.settingName());
            if (storedTopLevelName != null) {
                JsonNode storedValue = properties.get(storedTopLevelName);
                throw new AppException(storedValue.isObject()
                        ? WorldSettingErrorCode.WORLD_SETTING_PROPERTY_PATH_CONFLICT
                        : WorldSettingErrorCode.WORLD_SETTING_PROPERTY_DUPLICATED);
            }
            properties.put(property.settingName(), property.value());
            return;
        }

        String storedScopeName = findStoredFieldName(properties, property.scopeName());
        ObjectNode scope;
        if (storedScopeName == null) {
            scope = JsonNodeFactory.instance.objectNode();
            properties.set(property.scopeName(), scope);
        } else {
            JsonNode storedScope = properties.get(storedScopeName);
            if (!storedScope.isObject()) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTY_PATH_CONFLICT);
            }
            scope = (ObjectNode) storedScope;
        }
        if (findStoredFieldName(scope, property.settingName()) != null) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTY_DUPLICATED);
        }
        scope.put(property.settingName(), property.value());
    }

    private static void removeStoredProperty(ObjectNode properties, StoredPropertyPath path) {
        if (path.scopeName() == null) {
            properties.remove(path.settingName());
            return;
        }
        ObjectNode scope = (ObjectNode) properties.get(path.scopeName());
        scope.remove(path.settingName());
        if (scope.isEmpty()) {
            properties.remove(path.scopeName());
        }
    }

    private static StoredPropertyPath findStoredPropertyPath(
            JsonNode properties,
            String scopeName,
            String settingName
    ) {
        if (properties == null || !properties.isObject()) {
            return null;
        }
        if (scopeName == null) {
            String storedSettingName = findStoredFieldName(properties, settingName);
            return storedSettingName != null && properties.get(storedSettingName).isTextual()
                    ? new StoredPropertyPath(null, storedSettingName)
                    : null;
        }

        String storedScopeName = findStoredFieldName(properties, scopeName);
        if (storedScopeName == null || !properties.get(storedScopeName).isObject()) {
            return null;
        }
        JsonNode scope = properties.get(storedScopeName);
        String storedSettingName = findStoredFieldName(scope, settingName);
        return storedSettingName != null && scope.get(storedSettingName).isTextual()
                ? new StoredPropertyPath(storedScopeName, storedSettingName)
                : null;
    }

    private static String findStoredFieldName(JsonNode object, String name) {
        String duplicateKey = WorldSettingNameNormalizer.duplicateKey(name);
        Iterator<String> fieldNames = object.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (WorldSettingNameNormalizer.duplicateKey(fieldName).equals(duplicateKey)) {
                return fieldName;
            }
        }
        return null;
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
        Set<String> topLevelNames = new HashSet<>();
        for (Map.Entry<String, JsonNode> field : propertiesJson.properties()) {
            String topLevelName = requiredNameOrNull(field.getKey());
            if (topLevelName == null
                    || !topLevelNames.add(WorldSettingNameNormalizer.duplicateKey(topLevelName))
                    || field.getValue() == null) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTIES_INVALID);
            }
            if (field.getValue().isTextual()) {
                continue;
            }
            if (!field.getValue().isObject() || field.getValue().isEmpty()) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTIES_INVALID);
            }
            Set<String> scopedSettingNames = new HashSet<>();
            for (Map.Entry<String, JsonNode> scopedField : field.getValue().properties()) {
                String scopedSettingName = requiredNameOrNull(scopedField.getKey());
                if (scopedSettingName == null
                        || !scopedSettingNames.add(WorldSettingNameNormalizer.duplicateKey(scopedSettingName))
                        || scopedField.getValue() == null
                        || !scopedField.getValue().isTextual()) {
                    throw new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTIES_INVALID);
                }
            }
        }
    }

    private static Property normalizedProperty(String scopeName, String settingName, String value) {
        return new Property(optionalName(scopeName), requiredName(settingName), requiredValue(value));
    }

    private static String pathKey(String scopeName, String settingName) {
        return Objects.toString(WorldSettingNameNormalizer.duplicateKey(scopeName), "<root>")
                + "|"
                + WorldSettingNameNormalizer.duplicateKey(settingName);
    }

    private static boolean samePath(
            String leftScopeName,
            String leftSettingName,
            String rightScopeName,
            String rightSettingName
    ) {
        return pathKey(leftScopeName, leftSettingName).equals(pathKey(rightScopeName, rightSettingName));
    }

    private static String requiredName(String value) {
        String normalized = requiredNameOrNull(value);
        if (normalized == null) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_INPUT_INVALID);
        }
        return normalized;
    }

    private static String optionalName(String value) {
        return value == null ? null : requiredName(value);
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

    public record Property(String scopeName, String settingName, String value) {
    }

    public record StoredPropertyPath(String scopeName, String settingName) {
    }
}
