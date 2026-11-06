package org.monitoring.catchholebackend.domain.worldsetting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobSourceView;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisLiveSubjectSource;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisStateSource;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingLatestSourceEpisode;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.springframework.stereotype.Component;

/** 누적 실행 S0의 세계관 capture. #387부터 자동 누적 format 2의 대상별·현재값 live 읽기와 지문도 같은 규칙으로 제공한다. */
@Component
@RequiredArgsConstructor
public class WorldSettingAnalysisStateSource implements AnalysisStateSource, AnalysisLiveSubjectSource {

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
        Map<UUID, AnalysisJobSourceView> sources = sourceJobs.findExtractionSourcesForWork(work.getId()).stream()
                .collect(Collectors.toMap(AnalysisJobSourceView::getId, Function.identity()));
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        repository.findAllByWorkIdOrderByIdAsc(work.getId()).forEach(setting -> result.set(WorldSettingAnalysisStateMapper.persistedRef(setting.getId()),
                captureSetting(setting, sources)));
        return result;
    }

    /** #387 요청한 대상만 capture와 같은 규칙으로 읽는다. 다른 대상의 엔티티·후보는 읽지 않는다. */
    @Override
    public ObjectNode captureSubjects(Work work, Collection<String> refs) {
        List<UUID> ids = new ArrayList<>();
        for (String ref : refs) persistedId(ref).ifPresent(ids::add);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        if (ids.isEmpty()) return result;
        Map<UUID, AnalysisJobSourceView> sources = new HashMap<>();
        repository.findAllById(ids).stream().filter(setting -> work.getId().equals(setting.getWork().getId()))
                .sorted(Comparator.comparing(WorldSetting::getId))
                .forEach(setting -> result.set(WorldSettingAnalysisStateMapper.persistedRef(setting.getId()), captureSetting(setting, sources)));
        return result;
    }

    /**
     * #387 claim 응답용 현재값. 출처 provenance는 claim이 읽지 않아 후보를 읽지 않고, 가장 늦은 출처 회차는 집계 한 번으로 구한다.
     * 그 밖의 값은 capture와 같다.
     */
    @Override
    public ObjectNode captureCurrentValues(Work work) {
        List<WorldSetting> settings = repository.findAllByWorkIdOrderByIdAsc(work.getId());
        Map<UUID, Integer> latest = new HashMap<>();
        if (!settings.isEmpty()) {
            for (WorldSettingLatestSourceEpisode row : candidateRepository.findLatestConfirmedSourceEpisodes(
                    settings.stream().map(WorldSetting::getId).toList())) latest.put(row.worldSettingId(), row.episodeNo());
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (WorldSetting setting : settings) {
            ObjectNode state = toCurrentValues(mapper.toState(setting));
            state.put("latestSourceEpisodeNo", latest.get(setting.getId()));
            result.set(WorldSettingAnalysisStateMapper.persistedRef(setting.getId()), state);
        }
        return result;
    }

    @Override
    public ObjectNode toCurrentValues(ObjectNode capturedState) {
        ObjectNode result = capturedState.deepCopy();
        result.remove(List.of("provenance", "provenanceByPath"));
        return result;
    }

    /**
     * #387 지문 행: 대상마다 [id, version, 확정 현재값 후보 수]. 속성 변경은 version을 올리고, 값이 같은 확정이나 이력 전용 전환처럼
     * version 없이 출처만 바뀌는 쓰기는 후보 수가 잡는다. 생성·삭제는 id 집합을 바꾼다.
     */
    @Override
    public ArrayNode fingerprintRows(UUID workId) {
        Map<UUID, Long> counts = new HashMap<>();
        candidateRepository.countConfirmedCurrentSources(workId).forEach(row -> counts.put(row.worldSettingId(), row.candidateCount()));
        ArrayNode rows = JsonNodeFactory.instance.arrayNode();
        repository.findSettingVersions(workId).stream().sorted(Comparator.comparing(row -> row.id().toString()))
                .forEach(row -> rows.addArray().add(row.id().toString()).add(row.version()).add(counts.getOrDefault(row.id(), 0L)));
        return rows;
    }

    @Override
    public List<String> subjectRefs(UUID workId, String category) {
        return repository.findIdsByWorkIdAndCategory(workId, WorldSettingCategory.valueOf(category)).stream()
                .map(WorldSettingAnalysisStateMapper::persistedRef).toList();
    }

    private ObjectNode captureSetting(WorldSetting setting, Map<UUID, AnalysisJobSourceView> sources) {
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
        return state;
    }

    private static java.util.Optional<UUID> persistedId(String ref) {
        String prefix = "world:";
        if (ref == null || !ref.startsWith(prefix)) return java.util.Optional.empty();
        try {
            UUID id = UUID.fromString(ref.substring(prefix.length()));
            return WorldSettingAnalysisStateMapper.persistedRef(id).equals(ref) ? java.util.Optional.of(id) : java.util.Optional.empty();
        } catch (IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }
}
