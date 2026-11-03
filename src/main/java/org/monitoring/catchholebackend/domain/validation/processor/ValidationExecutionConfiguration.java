package org.monitoring.catchholebackend.domain.validation.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.monitoring.catchholebackend.domain.validation.type.ValidationRole;
import org.monitoring.catchholebackend.global.config.chat.ChatProperties;
import org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties;
import org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.Limits;

/**
 * 검수 Job configuration v2(#349 grid v4)를 쓰는 유일한 곳. 접수(queuePrepared)와 legacy 설정의 retry 전환이 이 모양만 저장한다.
 * <pre>{configurationVersion:2, leaseMillis, maxLeaseRecoveries,
 *  budgets:{setting:{modelCalls,lookups,seconds,inputTokens,segmentChars,contextChars,searchLimit}, typo:{...},
 *           roles:{PLANNER|JUDGE|TYPO:{maxCalls,maxReservedTokens,outputTokens}, QUERY_EMBEDDING|INDEX_EMBEDDING:{maxCalls,maxReservedTokens}}},
 *  budgetRevision:1, budgetHistory:[],
 *  models:{PLANNER|JUDGE|TYPO:{model,reasoningEffort}, QUERY_EMBEDDING|INDEX_EMBEDDING:{model,version,dimensions}}}</pre>
 * INDEX_EMBEDDING(선택, 2026-11-02)은 접수 때 그 작품의 빈 RAG 색인을 채우는 역할이며 QUERY_EMBEDDING과 같은 색인 identity를 쓴다.
 * 역할 순서는 enum 순서로 고정해 저장 JSON이 설정 Map 순서에 흔들리지 않게 한다. 운영 수치는 api.env의 설정 JSON에만 둔다.
 */
public final class ValidationExecutionConfiguration {
    public static final int CONFIGURATION_VERSION=2;
    public static final int QUERY_EMBEDDING_DIMENSIONS=1536;
    private ValidationExecutionConfiguration() {}

    public static ObjectNode build(ValidationExecutionProperties properties,ChatProperties retrieval) {
        var configuration=JsonNodeFactory.instance.objectNode().put("configurationVersion",CONFIGURATION_VERSION)
                .put("leaseMillis",properties.leaseDuration().toMillis()).put("maxLeaseRecoveries",properties.maxLeaseRecoveries());
        configuration.set("budgets",budgets(properties));
        configuration.put("budgetRevision",1).putArray("budgetHistory");
        configuration.set("models",models(properties,retrieval));
        return configuration;
    }
    public static ObjectNode budgets(ValidationExecutionProperties properties) {
        var budgets=JsonNodeFactory.instance.objectNode();
        budgets.set("setting",limits(properties.setting()));budgets.set("typo",limits(properties.typo()));budgets.set("roles",roleCaps(properties));
        return budgets;
    }
    public static ObjectNode limits(Limits limits) {
        return JsonNodeFactory.instance.objectNode().put("modelCalls",limits.modelCalls()).put("lookups",limits.lookups()).put("seconds",limits.seconds())
                .put("inputTokens",limits.inputTokens()).put("segmentChars",limits.segmentChars()).put("contextChars",limits.contextChars()).put("searchLimit",limits.searchLimit());
    }
    /** 역할별 cap. 생성 역할은 출력 상한(outputTokens)도 가진다. 퇴역 역할은 쓰지 않는다. */
    public static ObjectNode roleCaps(ValidationExecutionProperties properties) {
        var roles=JsonNodeFactory.instance.objectNode();
        for(var role:ValidationRole.values()) {
            var budget=properties.roles().get(role);if(budget==null||role.retired())continue;
            var cap=roles.putObject(role.name()).put("maxCalls",budget.maxCalls()).put("maxReservedTokens",budget.maxReservedTokens());
            if(role.generation())cap.put("outputTokens",budget.outputTokens());
        }
        return roles;
    }
    public static ObjectNode models(ValidationExecutionProperties properties,ChatProperties retrieval) {
        var models=JsonNodeFactory.instance.objectNode();
        for(var role:ValidationRole.values()) {
            var budget=properties.roles().get(role);if(budget==null||role.retired())continue;
            if(role.generation())models.set(role.name(),generationModel(budget));
            else models.set(role.name(),queryEmbeddingModel(properties,retrieval));
        }
        return models;
    }
    public static ObjectNode generationModel(ValidationExecutionProperties.RoleBudget budget) {
        return JsonNodeFactory.instance.objectNode().put("model",budget.model()).put("reasoningEffort",budget.reasoningEffort());
    }
    public static ObjectNode queryEmbeddingModel(ValidationExecutionProperties properties,ChatProperties retrieval) {
        return JsonNodeFactory.instance.objectNode().put("model",properties.queryEmbeddingModel()).put("version",retrieval.retrievalEmbeddingVersion()).put("dimensions",QUERY_EMBEDDING_DIMENSIONS);
    }
    /** configuration v2인지. 이 값이 없는 저장 Job은 legacy 설정이며 retry가 v2로 전환한다. */
    public static boolean isCurrent(JsonNode configuration) {
        return configuration!=null&&configuration.path("configurationVersion").isIntegralNumber()&&configuration.path("configurationVersion").asInt()==CONFIGURATION_VERSION;
    }
}
