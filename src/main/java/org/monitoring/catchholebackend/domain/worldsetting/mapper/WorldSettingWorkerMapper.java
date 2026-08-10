package org.monitoring.catchholebackend.domain.worldsetting.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.StreamSupport;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingCandidatePayload;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingComparisonContextResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingSubjectPageResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingPropertyResponse;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.springframework.stereotype.Component;

@Component
public class WorldSettingWorkerMapper {

    // Spring MVC DTO는 Jackson 3가 역직렬화하고 Hibernate JSONB는 Jackson 2 JsonNode를 사용한다.
    // 두 버전의 객체를 Controller/Entity 밖으로 노출하지 않도록 이 Mapper에서만 변환한다.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WorldSettingCandidate toEntity(
            AnalysisJob analysisJob,
            WorkerWorldSettingCandidatePublishRequest.Candidate request
    ) {
        return WorldSettingCandidate.create(
                analysisJob.getWork(),
                analysisJob.getEpisode(),
                analysisJob,
                request.category(),
                request.subjectName(),
                request.scopeName(),
                request.settingName(),
                request.extractedValue(),
                toJsonNode(request.evidenceSpans()),
                request.extractionConfidence(),
                toJsonNode(request.rawExtractionJson())
        );
    }

    public WorkerWorldSettingCandidatePayload toResponse(WorldSettingCandidate candidate) {
        return new WorkerWorldSettingCandidatePayload(
                candidate.getId(),
                candidate.getWork().getId(),
                candidate.getSourceEpisode().getId(),
                candidate.getCategory(),
                candidate.getSubjectName(),
                candidate.getScopeName(),
                candidate.getSettingName(),
                candidate.getExtractedValue(),
                toEvidenceSpans(candidate.getEvidenceSpans()),
                candidate.getExtractionConfidence()
        );
    }

    public WorkerWorldSettingSubjectPageResponse.Subject toSubjectResponse(WorldSetting worldSetting) {
        return new WorkerWorldSettingSubjectPageResponse.Subject(
                worldSetting.getId(),
                worldSetting.getSubjectName()
        );
    }

    public WorkerWorldSettingComparisonContextResponse.Target toComparisonTargetResponse(
            WorldSetting worldSetting
    ) {
        return new WorkerWorldSettingComparisonContextResponse.Target(
                worldSetting.getId(),
                worldSetting.getSubjectName(),
                worldSetting.getProperties().stream()
                        .map(property -> new WorldSettingPropertyResponse(
                                property.scopeName(),
                                property.settingName(),
                                property.value()
                        ))
                        .toList(),
                worldSetting.getVersion()
        );
    }

    public List<WorkerWorldSettingCandidatePayload> toResponseList(
            List<WorldSettingCandidate> candidates
    ) {
        return candidates.stream().map(this::toResponse).toList();
    }

    public JsonNode toJsonNode(Object value) {
        return value == null ? null : objectMapper.valueToTree(value);
    }

    private List<WorkerWorldSettingCandidatePayload.EvidenceSpan> toEvidenceSpans(JsonNode value) {
        if (value == null || !value.isArray()) {
            return List.of();
        }
        return StreamSupport.stream(value.spliterator(), false)
                .filter(JsonNode::isObject)
                .map(span -> new WorkerWorldSettingCandidatePayload.EvidenceSpan(
                        textValue(span, "quote"),
                        integerValue(span, "startOffset"),
                        integerValue(span, "endOffset")
                ))
                .toList();
    }

    private String textValue(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Integer integerValue(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || !value.isIntegralNumber() ? null : value.asInt();
    }
}
