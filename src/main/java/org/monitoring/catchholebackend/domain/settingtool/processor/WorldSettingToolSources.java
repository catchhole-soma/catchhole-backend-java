package org.monitoring.catchholebackend.domain.settingtool.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse.Reference;
import org.monitoring.catchholebackend.domain.settingtool.type.SettingTool;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingMapper;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WorldSettingToolSources {
    private final WorldSettingMapper worldMapper;

    public Map<Path, List<Reference>> resolve(WorldSetting world, List<WorldSettingCandidate> candidates) {
        var byId = new HashMap<UUID, WorldSettingCandidate>();
        candidates.forEach(candidate -> byId.put(candidate.getId(), candidate));
        var result = new HashMap<Path, List<Reference>>();
        // 기존 UI와 동일한 root→scope 이동 규칙과 latestEvidence 판정을 사용한다.
        for (var property : worldMapper.toDetailResponse(world, candidates).propertyEvidence()) {
            var path = new Path(property.scopeName(), property.settingName());
            var sources = new ArrayList<Reference>();
            if (property.latestEvidence() != null && !world.isManuallyEdited(path.scope(), path.key())) {
                WorldSettingCandidate base = null;
                for (var evidence : property.history()) {
                    var candidate = byId.get(evidence.candidateId());
                    if (candidate.isHistoryOnly()) continue;
                    if (base != null && !sameDecision(base, candidate)) break;
                    sources.add(new Reference(SettingTool.WORLD_EVIDENCE, candidate.getId()));
                    if (candidate.getFinalOperation() != WorldSettingOperation.MERGE && base == null) base = candidate;
                }
            }
            result.put(path, List.copyOf(sources));
        }
        return result;
    }

    private boolean sameDecision(WorldSettingCandidate left, WorldSettingCandidate right) {
        return left.getComparisonDecision() != null && right.getComparisonDecision() != null
                && left.getComparisonDecision().getId().equals(right.getComparisonDecision().getId())
                && Objects.equals(left.getAppliedWorldSettingVersion(), right.getAppliedWorldSettingVersion());
    }

    public record Path(String scope, String key) {}
}
