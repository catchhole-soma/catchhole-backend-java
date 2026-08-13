package org.monitoring.catchholebackend.global.config.swagger;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import java.util.Set;
import org.monitoring.catchholebackend.global.config.security.SecurityConstant;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    private static final String BEARER_SECURITY_SCHEME_NAME = "bearerAuth";
    private static final String INTERNAL_API_KEY_SECURITY_SCHEME_NAME = "internalApiKey";
    private static final String JSON_NODE_SCHEMA_REF = "#/components/schemas/JsonNode";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("CatchHole API")
                        .version("v1")
                        .description("CatchHole Backend API 문서"))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(BEARER_SECURITY_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT"))
                        .addSecuritySchemes(INTERNAL_API_KEY_SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(SecurityConstant.INTERNAL_API_KEY_HEADER)
                                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)));
    }

    /**
     * OpenAPI 3.1에서 {@code implementation = JsonNode.class, nullable = true}를 함께 쓰면
     * springdoc가 {@code $ref: JsonNode}와 {@code type: null}을 같은 schema에 기록한다.
     * 두 조건은 교집합으로 해석되므로 생성 SDK가 값을 null 전용으로 축소할 수 있다.
     * 구조가 자유로운 nullable JSON 필드는 명시적인 {@code JsonNode OR null} union으로 교체한다.
     */
    @Bean
    public OpenApiCustomizer nullableJsonSchemaCustomizer() {
        return openApi -> {
            replaceWithNullableJsonUnion(openApi, "SettingCandidateResponse", "valueJson");
            replaceWithNullableJsonUnion(openApi, "SettingCandidateResponse", "proposedValueJson");
            replaceWithNullableJsonUnion(openApi, "SettingCandidateResponse", "evidenceSpans");
            replaceWithNullableJsonUnion(openApi, "SettingCandidateResponse", "rawAiResultJson");
            replaceWithNullableJsonUnion(openApi, "SettingCandidateSnapshotChangeResponse", "beforeValueJson");
            replaceWithNullableJsonUnion(openApi, "SettingCandidateSnapshotChangeResponse", "proposedValueJson");
            replaceWithNullableJsonUnion(openApi, "WorkerCharacterFactComparisonCompleteRequest", "proposedValueJson");
            replaceWithNullableJsonUnion(openApi, "WorkerCharacterFactComparisonCandidatePayload", "valueJson");
            replaceWithNullableJsonUnion(openApi, "WorkerCharacterCurrentSnapshotEntry", "valueJson");
            replaceWithNullableEnumUnion(openApi, "SettingCandidateResponse", "valueType");
            replaceWithNullableEnumUnion(openApi, "SettingCandidateResponse", "suggestedOperation");
            replaceWithNullableEnumUnion(openApi, "SettingCandidateResponse", "temporalScope");
            replaceWithNullableEnumUnion(openApi, "SettingCandidateResponse", "comparisonTargetFactType");
        };
    }

    private static void replaceWithNullableJsonUnion(
            OpenAPI openApi,
            String ownerSchemaName,
            String propertyName
    ) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        Schema<?> ownerSchema = openApi.getComponents().getSchemas().get(ownerSchemaName);
        if (ownerSchema == null || ownerSchema.getProperties() == null) {
            return;
        }
        Schema<?> originalSchema = ownerSchema.getProperties().get(propertyName);
        if (originalSchema == null) {
            return;
        }

        JsonSchema jsonValueSchema = new JsonSchema();
        jsonValueSchema.set$ref(JSON_NODE_SCHEMA_REF);
        JsonSchema nullSchema = new JsonSchema();
        nullSchema.setTypes(Set.of("null"));
        JsonSchema nullableJsonUnion = new JsonSchema();
        nullableJsonUnion.setAnyOf(List.of(jsonValueSchema, nullSchema));
        nullableJsonUnion.setDescription(originalSchema.getDescription());
        ownerSchema.addProperty(propertyName, nullableJsonUnion);
    }

    private static void replaceWithNullableEnumUnion(
            OpenAPI openApi,
            String ownerSchemaName,
            String propertyName
    ) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        Schema<?> ownerSchema = openApi.getComponents().getSchemas().get(ownerSchemaName);
        if (ownerSchema == null || ownerSchema.getProperties() == null) {
            return;
        }
        Schema<?> originalSchema = ownerSchema.getProperties().get(propertyName);
        if (originalSchema == null || originalSchema.getEnum() == null) {
            return;
        }

        StringSchema enumSchema = new StringSchema();
        enumSchema.setEnum(originalSchema.getEnum().stream()
                .map(Object::toString)
                .toList());
        JsonSchema nullSchema = new JsonSchema();
        nullSchema.setTypes(Set.of("null"));
        JsonSchema nullableEnumUnion = new JsonSchema();
        nullableEnumUnion.setAnyOf(List.of(enumSchema, nullSchema));
        nullableEnumUnion.setDescription(originalSchema.getDescription());
        ownerSchema.addProperty(propertyName, nullableEnumUnion);
    }
}
