package org.monitoring.catchholebackend.domain.validation.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationBaselineSource;
import org.monitoring.catchholebackend.domain.validation.repository.ValidationBaselineSourceRepository;
import org.springframework.stereotype.Component;

/**
 * #381 접수가 저장한 원문 출처 manifest(validation_baseline_sources)를 R 번호 순서로 읽는다. 순서는 접수 때와 같다:
 * 앞 회차는 저장된 회차 번호 내림차순, 그다음 설정집 revision은 (설정집 id, revisionNo) DB 순서. 각 행은 legacy seed.sources 원소의 사본이라
 * 같은 순서의 배열 hash(manifestHash)는 legacy seed로 계산한 값과 같다.
 */
@Component
@RequiredArgsConstructor
public class ValidationSourceManifests {
    private final ValidationBaselineSourceRepository sources;
    private final AnalysisStateJournal journal;

    public ArrayNode manifests(UUID reportId) {
        List<ValidationBaselineSource> rows = sources.findAllByReportIdInSettingBookOrder(reportId);
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        rows.stream().map(ValidationBaselineSource::getSourceManifest).filter(manifest -> "EPISODE".equals(manifest.path("sourceKind").asText()))
                .sorted(Comparator.comparingInt((JsonNode manifest) -> manifest.path("episodeNo").asInt()).reversed())
                .forEach(manifest -> result.add(manifest.deepCopy()));
        rows.stream().map(ValidationBaselineSource::getSourceManifest).filter(manifest -> !"EPISODE".equals(manifest.path("sourceKind").asText()))
                .forEach(manifest -> result.add(manifest.deepCopy()));
        return result;
    }

    public String manifestHash(ArrayNode manifests) {
        return journal.hash(manifests);
    }
}
