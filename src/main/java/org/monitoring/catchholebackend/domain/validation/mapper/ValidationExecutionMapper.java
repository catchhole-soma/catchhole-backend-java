package org.monitoring.catchholebackend.domain.validation.mapper;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.monitoring.catchholebackend.domain.validation.dto.ValidationExecutionContract.Scope;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.springframework.stereotype.Component;
@Component
public class ValidationExecutionMapper {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    public JsonNode toTree(Object value) {return json.valueToTree(value);}
    public Map<String,Object> toTransport(Object value) {return json.convertValue(value,new com.fasterxml.jackson.core.type.TypeReference<>(){});}
    public Scope toScope(ValidationJob j) {return new Scope(1,j.getReport().getId(),j.getGeneration(),j.getInputDigest());}
    public Map<String,Object> toCheckResponse(ValidationCheck c) {
        var m=new LinkedHashMap<String,Object>();m.put("checkId",c.getId());m.put("localRef",c.getLocalRef());m.put("originalRefs",toTransportValue(c.getOriginalRefs()));
        m.put("revision",c.getRevision());m.put("checkInputDigest",c.getInputDigest());m.put("state",toTransportValue(c.getState()));m.put("outcome",c.getOutcome());m.put("decision",toTransportValue(c.getDecision()));m.put("changeReason",c.getChangeReason());return m;
    }
    public org.monitoring.catchholebackend.domain.validation.dto.response.ValidationStagingResponse toStagingResponse(JsonNode response) {return json.convertValue(response,org.monitoring.catchholebackend.domain.validation.dto.response.ValidationStagingResponse.class);}
    public Object toTransportValue(JsonNode n) {return n==null?null:json.convertValue(n,Object.class);}
    public Map<String,Object> toCheckpointResponse(ValidationJob j,List<ValidationStage> stages,List<ValidationCheck> checks,Map<ValidationBranch,ValidationCheckpoint> checkpoints) {
        var m=new LinkedHashMap<String,Object>();m.put("scope",toScope(j));m.put("gridReady",j.isGridReady());m.put("requiredGrid",toTransportValue(j.getRequiredGrid()));
        m.put("stages",stages.stream().map(s->Map.of("branch",s.getBranch(),"stage",s.getStage(),"ref",s.getRef(),"payload",toTransportValue(s.getPayload()),"successful",s.isSuccessful())).toList());
        m.put("checks",checks.stream().map(this::toCheckResponse).toList());
        var branches=new LinkedHashMap<String,Object>();for(var entry:checkpoints.entrySet())branches.put(entry.getKey().name(),Map.of("version",entry.getValue().getVersion(),"payload",toTransportValue(entry.getValue().getPayload())));m.put("branches",branches);return m;
    }
    public Map<String,Object> toClaimResponse(ValidationJob j,Map<String,Object> checkpoint) {
        var r=j.getReport();var m=new LinkedHashMap<String,Object>();m.put("contractVersion",1);m.put("projectionVersion",1);m.put("clueVersion",1);m.put("jobId",j.getId());m.put("scope",toScope(j));
        m.put("memberId",r.getMember().getId());m.put("workId",r.getWork().getId());m.put("episodeId",r.getEpisode().getId());m.put("reportRevision",r.getRevision());
        m.put("baselineRevision",r.getBaselineRevision());m.put("targetRevision",r.getSourceContentRevision());m.put("mode",r.getMode());m.put("baselineHash",r.getBaselineHash());m.put("targetHash",r.getSourceContentHash());m.put("manifestHash",j.getManifestHash());
        m.put("budgetRevision",j.getConfiguration().path("budgetRevision").asInt(1));m.put("attempt",j.getAttempt());m.put("leaseToken",j.getLeaseToken());m.put("leaseExpiresAt",j.getLeaseExpiresAt().toString());m.put("models",toTransportValue(j.getConfiguration().path("models")));m.put("budgets",toTransportValue(j.getConfiguration().path("budgets")));m.put("checkpoint",checkpoint);return m;
    }
}
