package org.monitoring.catchholebackend.domain.worldsetting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisStateSource;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WorldSettingAnalysisStateSource implements AnalysisStateSource {

    private final WorldSettingRepository repository;
    private final WorldSettingAnalysisStateMapper mapper;
    private final WorldSettingCandidateRepository candidateRepository;
    private final org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobSourceRepository sourceJobs;

    @Override
    public String domain() {
        return "worldSettings";
    }

    @Override
    public JsonNode capture(Work work) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        var sources = sourceJobs.findExtractionSourcesForWork(work.getId()).stream().collect(
                java.util.stream.Collectors.toMap(org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobSourceView::getId,
                        java.util.function.Function.identity()));
        repository.findAllByWorkIdOrderByIdAsc(work.getId()).forEach(setting -> {
            ObjectNode state = mapper.toState(setting);
            var candidates = candidateRepository
                    .findAllByTargetWorldSettingIdAndReviewStatusOrderByReviewedAtDescCreatedAtDescIdDesc(
                            setting.getId(), WorldSettingReviewStatus.CONFIRMED).stream()
                    .filter(candidate -> !candidate.isHistoryOnly()).toList();
            mapper.addConfirmedProvenance(state, candidates, job -> job == null ? null : sources.computeIfAbsent(job.getId(),
                    id -> sourceJobs.findSourceById(id).orElseThrow(() -> new IllegalStateException("세계관 근거의 분석 출처가 없습니다."))));
            Integer latestSourceEpisodeNo = candidates.stream()
                    .map(candidate -> candidate.getSourceEpisode())
                    .filter(java.util.Objects::nonNull)
                    .map(episode -> episode.getEpisodeNo())
                    .max(Integer::compareTo).orElse(null);
            state.put("latestSourceEpisodeNo", latestSourceEpisodeNo);
            result.set(WorldSettingAnalysisStateMapper.persistedRef(setting.getId()), state);
        });
        return result;
    }
}
