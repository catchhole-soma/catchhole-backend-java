package org.monitoring.catchholebackend.domain.worldsetting.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.exception.WorldSettingErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;

/** 확정 Entity와 분석용 JSON이 공유하는 세계관 전체 경로 연산이다. */
public final class WorldSettingPropertyView {

    private final ObjectNode properties;

    public WorldSettingPropertyView(JsonNode properties) {
        if (properties == null || !properties.isObject()) {
            throw invalid();
        }
        this.properties = properties.deepCopy();
        for (JsonNode value : this.properties) {
            if (value.isObject()) {
                value.forEach(leaf -> {
                    if (!leaf.isTextual()) {
                        throw invalid();
                    }
                });
            } else if (!value.isTextual()) {
                throw invalid();
            }
        }
    }

    public JsonNode toJson() {
        return properties.deepCopy();
    }

    public WorldSetting.StoredPropertyPath path(String scope, String name) {
        return findStoredPath(properties, scope, name);
    }

    public String value(String scope, String name) {
        WorldSetting.StoredPropertyPath path = path(scope, name);
        return path == null ? null : path.scopeName() == null
                ? properties.get(path.settingName()).asText()
                : properties.get(path.scopeName()).get(path.settingName()).asText();
    }

    public boolean conflicts(String scope, String name) {
        String key = findStoredFieldName(properties, scope == null ? name : scope);
        return key != null && (scope == null ? properties.get(key).isObject() : properties.get(key).isTextual());
    }

    public List<WorldSetting.Property> properties() {
        List<WorldSetting.Property> result = new ArrayList<>();
        properties.properties().forEach(entry -> {
            if (entry.getValue().isTextual()) {
                result.add(new WorldSetting.Property(null, entry.getKey(), entry.getValue().asText()));
            } else {
                entry.getValue().properties().forEach(child -> result.add(new WorldSetting.Property(
                        entry.getKey(), child.getKey(), child.getValue().asText())));
            }
        });
        return List.copyOf(result);
    }

    public void upsert(String scope, String name, String value) {
        if (name == null || name.isBlank() || value == null || value.isBlank() || conflicts(scope, name)) {
            throw invalid();
        }
        WorldSetting.StoredPropertyPath stored = path(scope, name);
        String actualName = stored == null ? name.trim() : stored.settingName();
        if (scope == null) {
            properties.put(actualName, value.trim());
        } else {
            String storedScope = findStoredFieldName(properties, scope);
            String actualScope = storedScope == null ? scope.trim() : storedScope;
            ObjectNode nested = properties.has(actualScope) ? (ObjectNode) properties.get(actualScope)
                    : properties.putObject(actualScope);
            nested.put(actualName, value.trim());
        }
    }

    public void moveRoot(String rootName, String scope, String expectedValue) {
        WorldSetting.StoredPropertyPath stored = path(null, rootName);
        if (stored == null || scope == null || scope.isBlank()
                || !java.util.Objects.equals(value(null, rootName), expectedValue)
                || path(scope, rootName) != null || conflicts(scope, rootName)) {
            throw invalid();
        }
        properties.remove(stored.settingName());
        upsert(scope, stored.settingName(), expectedValue);
    }

    public static WorldSetting.StoredPropertyPath findStoredPath(JsonNode properties, String scope, String name) {
        if (properties == null || !properties.isObject()) {
            return null;
        }
        if (scope == null) {
            String storedName = findStoredFieldName(properties, name);
            return storedName != null && properties.get(storedName).isTextual()
                    ? new WorldSetting.StoredPropertyPath(null, storedName) : null;
        }
        String storedScope = findStoredFieldName(properties, scope);
        if (storedScope == null || !properties.get(storedScope).isObject()) {
            return null;
        }
        String storedName = findStoredFieldName(properties.get(storedScope), name);
        return storedName != null && properties.get(storedScope).get(storedName).isTextual()
                ? new WorldSetting.StoredPropertyPath(storedScope, storedName) : null;
    }

    public static String findStoredFieldName(JsonNode object, String name) {
        if (name == null) {
            return null;
        }
        String duplicateKey = WorldSettingNameNormalizer.duplicateKey(name);
        Iterator<String> fields = object.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (WorldSettingNameNormalizer.duplicateKey(field).equals(duplicateKey)) {
                return field;
            }
        }
        return null;
    }

    private static AppException invalid() {
        return new AppException(WorldSettingErrorCode.WORLD_SETTING_PROPERTIES_INVALID);
    }
}
