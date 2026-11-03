package org.monitoring.catchholebackend.domain.validation.processor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenUsageRepository;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenPurpose;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationJob;
import org.monitoring.catchholebackend.domain.validation.repository.ValidationExecutionRepository;
import org.monitoring.catchholebackend.domain.validation.type.ValidationRole;
import org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties;
import org.springframework.stereotype.Component;
import static org.monitoring.catchholebackend.domain.validation.processor.ValidationExecutionAccess.error;
import static org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode.*;

/**
 * Only explicit retry may adopt explicitly changed caps; semantic segmentation/model policy stays frozen.
 * A current (v2) configuration refreshes role caps/output caps and the branch call/lookup/time/input limits; models, segmentChars,
 * contextChars and searchLimit stay frozen. A legacy configuration (no configurationVersion) is upgraded to v2 here (#349 grid v4).
 */
@Component @RequiredArgsConstructor
public class ValidationExecutionBudgetPolicy {
    private static final String[] REFRESHED_LIMITS={"modelCalls","lookups","seconds","inputTokens"};
    private final ValidationExecutionProperties properties;
    private final org.monitoring.catchholebackend.global.config.chat.ChatProperties retrieval;
    private final AnalysisStateJournal journal;
    private final AiTokenUsageRepository usages;
    private final ValidationExecutionRepository results;
    public void refreshForRetry(ValidationJob job) {
        var configuration=(ObjectNode)job.getConfiguration().deepCopy();boolean legacy=!ValidationExecutionConfiguration.isCurrent(configuration);
        var previous=configuration.path("budgets").deepCopy();var next=legacy?legacyBudgets(previous):currentBudgets(previous);
        var modelsBefore=configuration.path("models").deepCopy();
        var models=legacy?legacyModels(modelsBefore):(ObjectNode)modelsBefore.deepCopy();
        if(properties.roles().containsKey(ValidationRole.QUERY_EMBEDDING)&&!models.has("QUERY_EMBEDDING")) {
            if(usages.findAllByValidationJobId(job.getId()).stream().anyMatch(u->u.getPurpose()==AiTokenPurpose.VALIDATION_QUERY_EMBEDDING)||results.hasSuccessfulVectorReceipt(job.getReport().getId()))throw error(VALIDATION_PUBLICATION_CONFLICT);
            models.set("QUERY_EMBEDDING",ValidationExecutionConfiguration.queryEmbeddingModel(properties,retrieval));
        } else if(models.has("QUERY_EMBEDDING")) {
            if(properties.queryEmbeddingModel()!=null&&!properties.queryEmbeddingModel().equals(models.path("QUERY_EMBEDDING").path("model").asText()))throw error(VALIDATION_PUBLICATION_CONFLICT);
            if(!retrieval.retrievalEmbeddingVersion().equals(models.path("QUERY_EMBEDDING").path("version").asText()))throw error(VALIDATION_PUBLICATION_CONFLICT);
            // Removing a runtime role does not silently replace its previously frozen model.
        }
        configuration.set("models",models);
        if(!legacy&&journal.hash(previous).equals(journal.hash(next))&&journal.hash(modelsBefore).equals(journal.hash(models)))return;
        int revision=configuration.path("budgetRevision").asInt(1)+1;var history=configuration.withArray("budgetHistory");var change=history.addObject().put("revision",revision).put("generation",job.getGeneration()+1);
        change.set("before",previous);change.set("after",next.deepCopy());
        if(legacy) {change.put("configurationVersion",ValidationExecutionConfiguration.CONFIGURATION_VERSION);change.set("modelsBefore",modelsBefore);change.set("modelsAfter",models.deepCopy());}
        configuration.put("configurationVersion",ValidationExecutionConfiguration.CONFIGURATION_VERSION).put("budgetRevision",revision).set("budgets",next);job.reviseBudgetSnapshot(configuration);
    }
    /** v2: role caps and the branch call/lookup/time/input limits follow the explicit configuration; segmentation/search width stay frozen. */
    private ObjectNode currentBudgets(JsonNode previous) {
        var next=(ObjectNode)previous.deepCopy();next.set("roles",ValidationExecutionConfiguration.roleCaps(properties));
        for(String branch:new String[]{"setting","typo"}) {
            var current=ValidationExecutionConfiguration.limits(branch.equals("setting")?properties.setting():properties.typo());var existing=(ObjectNode)next.path(branch);
            for(String key:REFRESHED_LIMITS)existing.set(key,current.path(key));
        }
        return next;
    }
    /**
     * legacy → v2: SETTING limits come wholly from the configuration (its grid is discarded and rebuilt). TYPO keeps its stored segmentChars/contextChars
     * because the TYPO grid is kept and compared strictly; output caps move to the generation roles and retired roles are dropped.
     */
    private ObjectNode legacyBudgets(JsonNode previous) {
        var next=ValidationExecutionConfiguration.budgets(properties);var typo=(ObjectNode)next.path("typo");
        for(String key:new String[]{"segmentChars","contextChars"})if(previous.path("typo").path(key).isIntegralNumber())typo.put(key,previous.path("typo").path(key).asInt());
        return next;
    }
    /** legacy → v2: PLANNER and JUDGE follow the configuration; a stored TYPO model (whose segments are kept) stays; retired roles are dropped. */
    private ObjectNode legacyModels(JsonNode before) {
        var models=JsonNodeFactory.instance.objectNode();
        for(var role:ValidationRole.values()) {
            if(!role.generation())continue;
            var stored=before.path(role.name());var budget=properties.roles().get(role);
            if(role==ValidationRole.TYPO&&stored.path("model").isTextual()&&ValidationExecutionProperties.REASONING_EFFORTS.contains(stored.path("reasoningEffort").asText()))
                models.set(role.name(),JsonNodeFactory.instance.objectNode().put("model",stored.path("model").asText()).put("reasoningEffort",stored.path("reasoningEffort").asText()));
            else models.set(role.name(),ValidationExecutionConfiguration.generationModel(budget));
        }
        if(before.has("QUERY_EMBEDDING"))models.set("QUERY_EMBEDDING",before.path("QUERY_EMBEDDING").deepCopy());
        return models;
    }
}
