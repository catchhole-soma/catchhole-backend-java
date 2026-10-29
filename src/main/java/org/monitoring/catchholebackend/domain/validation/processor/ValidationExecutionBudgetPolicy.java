package org.monitoring.catchholebackend.domain.validation.processor;
import com.fasterxml.jackson.databind.node.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenUsageRepository;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenPurpose;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationJob;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationExecutionMapper;
import org.monitoring.catchholebackend.domain.validation.repository.ValidationExecutionRepository;
import org.monitoring.catchholebackend.domain.validation.type.ValidationRole;
import org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties;
import org.springframework.stereotype.Component;
import static org.monitoring.catchholebackend.domain.validation.processor.ValidationExecutionAccess.error;
import static org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode.*;

/** Only explicit retry may adopt explicitly changed caps; semantic segmentation/model policy stays frozen. */
@Component @RequiredArgsConstructor
public class ValidationExecutionBudgetPolicy {
    private final ValidationExecutionProperties properties;
    private final org.monitoring.catchholebackend.global.config.chat.ChatProperties retrieval;
    private final ValidationExecutionMapper mapper;
    private final AnalysisStateJournal journal;
    private final AiTokenUsageRepository usages;
    private final ValidationExecutionRepository results;
    public void refreshForRetry(ValidationJob job) {
        var configuration=(ObjectNode)job.getConfiguration().deepCopy();var previous=configuration.path("budgets").deepCopy();var next=(ObjectNode)previous.deepCopy();
        next.set("roles",mapper.toTree(properties.roles()));
        for(String branch:new String[]{"setting","typo"}) {
            var current=mapper.toTree(branch.equals("setting")?properties.setting():properties.typo());var existing=(ObjectNode)next.path(branch);
            for(String key:new String[]{"modelCalls","lookups","seconds","inputTokens","outputTokens"})existing.set(key,current.path(key));
        }
        var models=(ObjectNode)configuration.path("models");
        if(properties.roles().containsKey(ValidationRole.QUERY_EMBEDDING)&&!models.has("QUERY_EMBEDDING")) {
            if(usages.findAllByValidationJobId(job.getId()).stream().anyMatch(u->u.getPurpose()==AiTokenPurpose.VALIDATION_QUERY_EMBEDDING)||results.hasSuccessfulVectorReceipt(job.getReport().getId()))throw error(VALIDATION_PUBLICATION_CONFLICT);
            models.putObject("QUERY_EMBEDDING").put("model",properties.queryEmbeddingModel()).put("version",retrieval.retrievalEmbeddingVersion()).put("dimensions",1536);
        } else if(models.has("QUERY_EMBEDDING")) {
            if(properties.queryEmbeddingModel()!=null&&!properties.queryEmbeddingModel().equals(models.path("QUERY_EMBEDDING").path("model").asText()))throw error(VALIDATION_PUBLICATION_CONFLICT);
            if(!retrieval.retrievalEmbeddingVersion().equals(models.path("QUERY_EMBEDDING").path("version").asText()))throw error(VALIDATION_PUBLICATION_CONFLICT);
            // Removing a runtime role does not silently replace its previously frozen model.
        }
        if(journal.hash(previous).equals(journal.hash(next)))return;
        int revision=configuration.path("budgetRevision").asInt(1)+1;var history=configuration.withArray("budgetHistory");var change=history.addObject().put("revision",revision).put("generation",job.getGeneration()+1);
        change.set("before",previous);change.set("after",next.deepCopy());configuration.put("budgetRevision",revision).set("budgets",next);job.reviseBudgetSnapshot(configuration);
    }
}
