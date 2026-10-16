package org.monitoring.catchholebackend.domain.settingtool.service;

import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSettingDisplayNameResolver;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactConfirmApplicationMode;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolQuery;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse.EvidenceStatus;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse.Item;
import org.monitoring.catchholebackend.domain.settingtool.mapper.SettingToolMapper;
import org.monitoring.catchholebackend.domain.settingtool.processor.SettingEvidenceReader;
import org.monitoring.catchholebackend.domain.settingtool.processor.SettingToolScalarValue;
import org.monitoring.catchholebackend.domain.settingtool.processor.SettingToolReadToken;
import org.monitoring.catchholebackend.domain.settingtool.processor.WorldSettingToolSources;
import org.monitoring.catchholebackend.domain.settingtool.processor.WorldSettingToolSources.Path;
import org.monitoring.catchholebackend.domain.settingtool.repository.SettingToolRepository;
import org.monitoring.catchholebackend.domain.settingtool.type.SettingTool;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.exception.CommonErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettingToolServiceImpl implements SettingToolService {
    private final WorkRepository works;
    private final MemberRepository members;
    private final WorkCharacterRepository characters;
    private final WorldSettingRepository worlds;
    private final CharacterSnapshotSourceRepository snapshotSourceRepository;
    private final CharacterSettingSchemaRepository schemas;
    private final CharacterSnapshotAccessor snapshots;
    private final CharacterSettingDisplayNameResolver displayNames;
    private final SettingToolRepository repository;
    private final SettingToolMapper mapper;
    private final WorldSettingToolSources worldSources;
    private final SettingEvidenceReader evidenceReader;
    private final Validator validator;

    @Override
    public SettingToolResponse querySettings(Long memberId, UUID workId, SettingToolQuery request) {
        validate(request);
        if (memberId == null || !members.findById(memberId).map(member -> member.isActive()).orElse(false)) {
            throw new AppException(CommonErrorCode.AUTH_UNAUTHORIZED);
        }
        works.getOwnedWork(workId, memberId).requireActive();
        // 현재 설정과 source를 여러 SQL로 읽는 동안 version이 달라지면 혼합 응답 대신 재조회를 요구한다.
        WorkCharacter character = switch (request.tool()) {
            case CHARACTER_CURRENT, CHARACTER_HISTORY -> character(workId, request.targetId());
            case CHARACTER_FIELDS -> request.targetId() == null ? null : character(workId, request.targetId());
            case CHARACTER_EVIDENCE -> repository.fact(workId, request.targetId()).orElseThrow(this::notFound).getWorkCharacter();
            default -> null;
        };
        WorldSetting world = switch (request.tool()) {
            case WORLD_CURRENT, WORLD_HISTORY -> world(workId, request.targetId());
            case WORLD_FIELDS -> request.targetId() == null ? null : world(workId, request.targetId());
            case WORLD_EVIDENCE -> repository.worldEvidence(workId, request.targetId()).orElseThrow(this::notFound).getTargetWorldSetting();
            default -> null;
        };
        var worldSourceState = world == null ? List.<Object[]>of()
                : repository.worldSourceState(workId, world.getId(), null);
        SettingToolResponse result = switch (request.tool()) {
            case FIND_CHARACTERS -> findCharacters(workId, request);
            case FIND_WORLD_SETTINGS -> response(request, repository.worlds(workId, request).map(mapper::toWorldResponse), List.of());
            case CHARACTER_CURRENT -> characterCurrent(workId, request);
            case CHARACTER_HISTORY -> characterHistory(workId, request);
            case CHARACTER_FIELDS -> characterFields(workId, request);
            case WORLD_CURRENT -> worldCurrent(workId, request);
            case WORLD_HISTORY -> worldHistory(workId, request);
            case WORLD_FIELDS -> worldFields(workId, request);
            case CHARACTER_CURRENT_COLLECTION -> characterCollection(workId, request);
            case WORLD_CURRENT_COLLECTION -> worldCollection(workId, request);
            case CHARACTER_EVIDENCE -> characterEvidence(workId, request);
            case WORLD_EVIDENCE -> worldEvidence(workId, request);
        };
        if (character != null) repository.assertCharacterUnchanged(character);
        if (world != null) repository.assertWorldUnchanged(world, worldSourceState);
        return result;
    }

    private SettingToolResponse findCharacters(UUID workId, SettingToolQuery request) {
        if (request.valueEquals() == null) {
            return response(request, repository.characters(workId, request).map(mapper::toCharacterResponse), List.of());
        }
        var candidates = repository.charactersForCurrentLookup(workId, request.query());
        var slot = new CharacterSnapshotSlot(request.factType(), request.settingKey());
        var matches = candidates.stream().filter(character ->
                SettingToolScalarValue.matches(snapshots.read(character).get(slot), request.valueEquals())).toList();
        var result = response(request, page(slice(matches, request).stream().map(mapper::toCharacterResponse).toList(),
                matches.size(), request), List.of());
        repository.assertCharactersUnchanged(candidates);
        return result;
    }

    private SettingToolResponse characterFields(UUID workId, SettingToolQuery request) {
        boolean workWide = request.targetId() == null;
        var subjects = workWide ? repository.charactersForCurrentLookup(workId, null)
                : List.of(character(workId, request.targetId()));
        var slots = new HashSet<CharacterSnapshotSlot>();
        if (request.episodeFrom() == null && request.episodeTo() == null) {
            subjects.forEach(subject -> slots.addAll(snapshots.read(subject).keySet()));
        }
        if (!workWide) slots.addAll(repository.characterHistoryFields(workId, request));
        var activeSchemas = schemas.findAllActiveForWork(workId);
        var selected = slots.stream().filter(slot -> request.factType() == null || request.factType() == slot.factType())
                .sorted(Comparator.comparing((CharacterSnapshotSlot slot) -> slot.factType().name())
                        .thenComparing(CharacterSnapshotSlot::factKey)).toList();
        UUID subjectId = workWide ? workId : subjects.getFirst().getId();
        String subjectName = workWide ? "작품의 인물 설정" : subjects.getFirst().getName();
        Long version = workWide ? null : subjects.getFirst().getSnapshotVersion();
        var items = slice(selected, request).stream().map(slot -> mapper.toFieldResponse(subjectId, subjectName,
                slot.factType().name(), null, slot.factKey(), displayNames.resolve(slot.factType(), slot.factKey(), null, activeSchemas),
                version)).toList();
        if (workWide) repository.assertCharactersUnchanged(subjects);
        return response(request, page(items, selected.size(), request), List.of());
    }

    private SettingToolResponse worldFields(UUID workId, SettingToolQuery request) {
        if (request.targetId() == null) return workWorldFields(workId, request);
        var world = world(workId, request.targetId());
        var paths = new HashSet<>(repository.worldHistoryFields(workId, request));
        if (request.episodeFrom() == null && request.episodeTo() == null) {
            world.getProperties().forEach(property -> paths.add(new Path(property.scopeName(), property.settingName())));
        }
        var selected = paths.stream().sorted(Comparator.comparing(Path::scope, Comparator.nullsFirst(String::compareTo))
                .thenComparing(Path::key)).toList();
        var items = slice(selected, request).stream().map(path -> mapper.toFieldResponse(world.getId(), world.getSubjectName(),
                world.getCategory().name(), path.scope(), path.key(), path.key(), world.getVersion())).toList();
        return response(request, page(items, selected.size(), request), List.of());
    }

    private record WorldField(String category, Path path) {}

    private SettingToolResponse workWorldFields(UUID workId, SettingToolQuery request) {
        var before = collectionToken(workId, request, false);
        var paths = new HashSet<WorldField>();
        for (var world : repository.worldsForCurrentLookup(workId, request.worldCategory())) {
            world.getProperties().forEach(property -> paths.add(new WorldField(world.getCategory().name(),
                    new Path(property.scopeName(), property.settingName()))));
        }
        var selected = paths.stream().sorted(Comparator.comparing(WorldField::category)
                .thenComparing(field -> field.path().scope(), Comparator.nullsFirst(String::compareTo))
                .thenComparing(field -> field.path().key())).toList();
        var items = slice(selected, request).stream().map(field -> mapper.toFieldResponse(workId, "작품의 세계관 설정",
                field.category(), field.path().scope(), field.path().key(), field.path().key(), null)).toList();
        assertCollectionUnchanged(workId, request, false, before);
        return response(request, page(items, selected.size(), request), List.of());
    }

    private record CharacterSelection(WorkCharacter subject, CharacterSnapshotSlot slot) {}
    private record WorldSelection(WorldSetting subject, WorldSetting.Property property) {}

    private SettingToolResponse characterCollection(UUID workId, SettingToolQuery request) {
        var before = collectionToken(workId, request, true);
        assertRequestedToken(request, before);
        var subjects = repository.charactersForCurrentLookup(workId, null);
        var selected = new ArrayList<CharacterSelection>();
        var withValues = new HashSet<UUID>();
        for (var subject : subjects) {
            for (var slot : snapshots.read(subject).keySet()) {
                if (request.factType() != null && request.factType() != slot.factType()
                        || request.settingKey() != null && !request.settingKey().equals(slot.factKey())) continue;
                selected.add(new CharacterSelection(subject, slot));
                withValues.add(subject.getId());
            }
        }
        selected.sort(Comparator.comparing((CharacterSelection row) -> row.subject().getId().toString())
                .thenComparing(row -> row.slot().factType().name()).thenComparing(row -> row.slot().factKey()));
        var pageSubjects = new LinkedHashMap<WorkCharacter, List<CharacterSnapshotSlot>>();
        for (var row : slice(selected, request)) {
            pageSubjects.computeIfAbsent(row.subject(), ignored -> new ArrayList<>()).add(row.slot());
        }
        var activeSchemas = schemas.findAllActiveForWork(workId);
        var items = new ArrayList<Item>();
        pageSubjects.forEach((subject, slots) -> items.addAll(characterCurrentItems(subject, slots, activeSchemas)));
        assertCollectionUnchanged(workId, request, true, before);
        return collectionResponse(request, items, "ACTIVE_REGISTERED_CHARACTERS", subjects.size(), withValues.size(), selected.size(), before, List.of());
    }

    private SettingToolResponse worldCollection(UUID workId, SettingToolQuery request) {
        var before = collectionToken(workId, request, false);
        assertRequestedToken(request, before);
        var subjects = repository.worldsForCurrentLookup(workId, request.worldCategory());
        var selected = new ArrayList<WorldSelection>();
        var withValues = new HashSet<UUID>();
        for (var subject : subjects) {
            for (var property : subject.getProperties()) {
                if (request.settingKey() != null && (!request.settingKey().equals(property.settingName())
                        || !Objects.equals(request.scopeName(), property.scopeName()))) continue;
                selected.add(new WorldSelection(subject, property));
                withValues.add(subject.getId());
            }
        }
        selected.sort(Comparator.comparing((WorldSelection row) -> row.subject().getId().toString())
                .thenComparing(row -> row.property().scopeName(), Comparator.nullsFirst(String::compareTo))
                .thenComparing(row -> row.property().settingName()));
        var pageSubjects = new LinkedHashMap<WorldSetting, List<WorldSetting.Property>>();
        for (var row : slice(selected, request)) {
            pageSubjects.computeIfAbsent(row.subject(), ignored -> new ArrayList<>()).add(row.property());
        }
        var items = new ArrayList<Item>();
        boolean truncated = false;
        for (var entry : pageSubjects.entrySet()) {
            var current = worldCurrentItems(workId, entry.getKey(), entry.getValue());
            items.addAll(current.items());
            truncated |= current.truncated();
        }
        assertCollectionUnchanged(workId, request, false, before);
        return collectionResponse(request, items, "REGISTERED_WORLD_SETTINGS", subjects.size(), withValues.size(), selected.size(), before,
                truncated ? List.of("WORLD_SOURCE_SCAN_LIMIT") : List.of());
    }

    private String collectionToken(UUID workId, SettingToolQuery request, boolean character) {
        return SettingToolReadToken.create(workId, request,
                character ? repository.characterCollectionState(workId) : repository.worldCollectionState(workId, request.worldCategory()),
                character ? repository.characterSchemaState(workId)
                        : repository.worldSourceState(workId, null, request.worldCategory()));
    }

    private void assertRequestedToken(SettingToolQuery request, String current) {
        if (request.readToken() != null && !request.readToken().equals(current)) throw new AppException(CommonErrorCode.CONFLICT);
    }

    private void assertCollectionUnchanged(UUID workId, SettingToolQuery request, boolean character, String before) {
        repository.assertWorkActive(workId);
        if (!before.equals(collectionToken(workId, request, character))) throw new AppException(CommonErrorCode.CONFLICT);
    }

    private SettingToolResponse collectionResponse(SettingToolQuery request, List<Item> items, String population,
                                                    int subjects, int withValues, int records, String token, List<String> extra) {
        var result = response(request, page(items, records, request), extra);
        return new SettingToolResponse(result.schemaVersion(), result.tool(), result.items(), result.partial(), result.limitations(),
                new SettingToolResponse.CollectionMetadata(population, subjects, withValues, records, token));
    }

    private SettingToolResponse characterCurrent(UUID workId, SettingToolQuery request) {
        var character = character(workId, request.targetId());
        var selected = snapshots.read(character).keySet().stream()
                .filter(slot -> request.factType() == null || request.factType() == slot.factType())
                .filter(slot -> request.settingKey() == null || request.settingKey().equals(slot.factKey()))
                .sorted(Comparator.comparing((CharacterSnapshotSlot slot) -> slot.factType().name()).thenComparing(CharacterSnapshotSlot::factKey)).toList();
        var pageSlots = slice(selected, request);
        var items = characterCurrentItems(character, pageSlots, schemas.findAllActiveForWork(workId));
        return response(request, page(items, selected.size(), request), List.of());
    }

    private List<Item> characterCurrentItems(WorkCharacter character, List<CharacterSnapshotSlot> pageSlots,
                                              List<CharacterSettingSchema> activeSchemas) {
        var sources = new HashMap<CharacterSnapshotSlot, List<CharacterFact>>();
        for (var source : repository.snapshotSources(character.getId(), pageSlots)) {
            var slot = new CharacterSnapshotSlot(source.getFactType(), source.getFactKey());
            var fact = source.getSourceFact();
            if (fact.getFactType() == slot.factType() && fact.getFactKey().equals(slot.factKey())
                    && validSource(character, fact) && !historyOnly(fact)) {
                sources.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(fact);
            }
        }
        var current = snapshots.read(character, sources);
        return pageSlots.stream().map(slot -> mapper.toCharacterCurrentResponse(character, current.get(slot),
                displayNames.resolve(slot.factType(), slot.factKey(), current.get(slot).valueJson(), activeSchemas),
                sources.getOrDefault(slot, List.of()))).toList();
    }

    private SettingToolResponse characterHistory(UUID workId, SettingToolQuery request) {
        var character = character(workId, request.targetId());
        var facts = repository.facts(workId, request);
        var current = currentFactIds(character, facts.getContent());
        var activeSchemas = schemas.findAllActiveForWork(workId);
        return response(request, facts.map(fact -> mapper.toFactResponse(fact,
                displayNames.resolve(fact.getFactType(), fact.getFactKey(), fact.getValueJson(), activeSchemas), current.contains(fact.getId()), null)),
                List.of("HISTORY_IS_NOT_A_STATE_SNAPSHOT"));
    }

    private SettingToolResponse characterEvidence(UUID workId, SettingToolQuery request) {
        var fact = repository.fact(workId, request.targetId()).orElseThrow(this::notFound);
        if (!validSource(fact.getWorkCharacter(), fact)) throw notFound();
        var candidate = fact.getSettingCandidate();
        var episode = fact.getSourceEpisode() != null ? fact.getSourceEpisode() : candidate == null ? null : candidate.getEpisode();
        var job = candidate == null ? fact.getExtractedByJob() : candidate.getAnalysisJob();
        if (fact.getExtractedByJob() != null && job != null && !fact.getExtractedByJob().getId().equals(job.getId())) throw notFound();
        if (candidate != null && candidate.getSourceChunkId() != null && fact.getSourceChunkId() != null
                && !candidate.getSourceChunkId().equals(fact.getSourceChunkId())) throw notFound();
        var evidence = evidenceReader.read(workId, candidate == null ? null : candidate.getId(),
                candidate != null && candidate.getSourceChunkId() != null ? candidate.getSourceChunkId() : fact.getSourceChunkId(), episode, job,
                candidate == null ? null : candidate.getSourceContentS3Key(), candidate == null ? null : candidate.getEvidenceSpans(), request);
        var item = mapper.toFactResponse(fact, displayNames.resolve(fact.getFactType(), fact.getFactKey(), fact.getValueJson(), schemas.findAllActiveForWork(workId)),
                currentFactIds(fact.getWorkCharacter(), List.of(fact)).contains(fact.getId()), evidence);
        return response(request, page(List.of(item), 1, request), List.of());
    }

    private SettingToolResponse worldCurrent(UUID workId, SettingToolQuery request) {
        var world = world(workId, request.targetId());
        var properties = world.getProperties().stream()
                .filter(property -> request.settingKey() == null || request.settingKey().equals(property.settingName())
                        && Objects.equals(request.scopeName(), property.scopeName()))
                .sorted(Comparator.comparing(WorldSetting.Property::scopeName, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(WorldSetting.Property::settingName)).toList();
        var current = worldCurrentItems(workId, world, slice(properties, request));
        return response(request, page(current.items(), properties.size(), request), current.truncated() ? List.of("WORLD_SOURCE_SCAN_LIMIT") : List.of());
    }

    private record WorldCurrentItems(List<Item> items, boolean truncated) {}

    private WorldCurrentItems worldCurrentItems(UUID workId, WorldSetting world, List<WorldSetting.Property> properties) {
        var candidates = repository.worldSources(workId, world.getId());
        boolean truncated = candidates.size() > 500;
        var sources = worldSources.resolve(world, candidates.stream().limit(500).toList());
        return new WorldCurrentItems(properties.stream().map(property -> mapper.toWorldCurrentResponse(world, property,
                sources.getOrDefault(new Path(property.scopeName(), property.settingName()), List.of()), truncated)).toList(), truncated);
    }

    private SettingToolResponse worldHistory(UUID workId, SettingToolQuery request) {
        var world = world(workId, request.targetId());
        var candidates = repository.worldSources(workId, world.getId());
        var currentIds = worldCurrentIds(world, candidates);
        return response(request, repository.worldHistory(workId, request).map(candidate -> mapper.toWorldHistoryResponse(candidate,
                currentIds.contains(candidate.getId()) ? true : candidates.size() > 500 && !candidate.isHistoryOnly() ? null : false, null)), candidates.size() > 500
                ? List.of("HISTORY_IS_NOT_A_STATE_SNAPSHOT", "WORLD_SOURCE_SCAN_LIMIT") : List.of("HISTORY_IS_NOT_A_STATE_SNAPSHOT"));
    }

    private SettingToolResponse worldEvidence(UUID workId, SettingToolQuery request) {
        var candidate = repository.worldEvidence(workId, request.targetId()).orElseThrow(this::notFound);
        var evidence = evidenceReader.read(workId, candidate.getId(), null, candidate.getSourceEpisode(), candidate.getAnalysisJob(),
                null, candidate.getEvidenceSpans(), request);
        var world = candidate.getTargetWorldSetting();
        var candidates = repository.worldSources(workId, world.getId());
        var item = mapper.toWorldHistoryResponse(candidate, worldCurrentIds(world, candidates).contains(candidate.getId()) ? true
                : candidates.size() > 500 && !candidate.isHistoryOnly() ? null : false, evidence);
        return response(request, page(List.of(item), 1, request), candidates.size() > 500
                ? List.of("WORLD_SOURCE_SCAN_LIMIT") : List.of());
    }

    private Set<UUID> worldCurrentIds(WorldSetting world, List<WorldSettingCandidate> candidates) {
        var ids = new HashSet<UUID>();
        worldSources.resolve(world, candidates.stream().limit(500).toList()).values()
                .forEach(references -> references.forEach(reference -> ids.add(reference.targetId())));
        return ids;
    }

    private Set<UUID> currentFactIds(WorkCharacter character, List<CharacterFact> facts) {
        if (facts.isEmpty()) return Set.of();
        var slots = snapshots.read(character).keySet();
        var byId = new HashMap<UUID, CharacterFact>();
        facts.forEach(fact -> byId.put(fact.getId(), fact));
        var result = new HashSet<UUID>();
        for (var source : snapshotSourceRepository.findAllBySourceFactIdIn(byId.keySet())) {
            var fact = byId.get(source.getSourceFact().getId());
            if (source.getWorkCharacter().getId().equals(character.getId()) && validSource(character, fact) && !historyOnly(fact)
                    && source.getFactType() == fact.getFactType() && source.getFactKey().equals(fact.getFactKey())
                    && slots.contains(new CharacterSnapshotSlot(source.getFactType(), source.getFactKey()))) result.add(fact.getId());
        }
        return result;
    }

    private boolean validSource(WorkCharacter character, CharacterFact fact) {
        if (!character.getId().equals(fact.getWorkCharacter().getId())) return false;
        UUID workId = character.getWork().getId();
        var candidate = fact.getSettingCandidate();
        var episode = fact.getSourceEpisode() != null ? fact.getSourceEpisode() : candidate == null ? null : candidate.getEpisode();
        var factJob = fact.getExtractedByJob();
        var candidateJob = candidate == null ? null : candidate.getAnalysisJob();
        if (!sameSourceJob(workId, episode, factJob) || !sameSourceJob(workId, episode, candidateJob)
                || factJob != null && candidateJob != null && !factJob.getId().equals(candidateJob.getId())) return false;
        return (fact.getSourceEpisode() == null || workId.equals(fact.getSourceEpisode().getWork().getId()))
                && (candidate == null || workId.equals(candidate.getWork().getId())
                    && candidate.getReviewStatus() == SettingCandidateReviewStatus.CONFIRMED
                    && candidate.getProvisionalSubjectKey() == null
                    && (candidate.getMatchedCharacterId() == null || character.getId().equals(candidate.getMatchedCharacterId()))
                    && (candidate.getEpisode() == null || workId.equals(candidate.getEpisode().getWork().getId()))
                    && (candidate.getEpisode() == null || fact.getSourceEpisode() == null
                        || candidate.getEpisode().getId().equals(fact.getSourceEpisode().getId())));
    }

    private boolean sameSourceJob(UUID workId, org.monitoring.catchholebackend.domain.episode.entity.Episode episode,
                                  org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob job) {
        return job == null || workId.equals(job.getWork().getId())
                && (job.getEpisode() == null || episode != null && job.getEpisode().getId().equals(episode.getId()));
    }

    private boolean historyOnly(CharacterFact fact) {
        return fact.getSettingCandidate() != null && fact.getSettingCandidate().getConfirmedApplicationMode() == CharacterFactConfirmApplicationMode.HISTORY_ONLY;
    }
    private WorkCharacter character(UUID workId, UUID id) { return characters.findByIdAndWorkIdAndStatus(id, workId, CharacterStatus.ACTIVE).orElseThrow(this::notFound); }
    private WorldSetting world(UUID workId, UUID id) { return worlds.findByIdAndWorkId(id, workId).orElseThrow(this::notFound); }
    private AppException notFound() { return new AppException(CommonErrorCode.RESOURCE_NOT_FOUND); }

    private void validate(SettingToolQuery request) {
        if (request == null || !validator.validate(request).isEmpty()) throw new AppException(CommonErrorCode.REQUEST_VALIDATION_FAILED);
        boolean find = request.tool() == SettingTool.FIND_CHARACTERS || request.tool() == SettingTool.FIND_WORLD_SETTINGS;
        boolean evidence = request.tool() == SettingTool.CHARACTER_EVIDENCE || request.tool() == SettingTool.WORLD_EVIDENCE;
        boolean current = request.tool() == SettingTool.CHARACTER_CURRENT || request.tool() == SettingTool.WORLD_CURRENT;
        boolean characterCollection = request.tool() == SettingTool.CHARACTER_CURRENT_COLLECTION;
        boolean worldCollection = request.tool() == SettingTool.WORLD_CURRENT_COLLECTION;
        boolean collection = characterCollection || worldCollection;
        boolean world = request.tool() == SettingTool.WORLD_CURRENT || request.tool() == SettingTool.WORLD_HISTORY || worldCollection;
        boolean characterFields = request.tool() == SettingTool.CHARACTER_FIELDS;
        boolean fields = characterFields || request.tool() == SettingTool.WORLD_FIELDS;
        boolean workFields = fields && request.targetId() == null;
        boolean condition = request.tool() == SettingTool.FIND_CHARACTERS && request.valueEquals() != null;
        if ((find || collection) && request.targetId() != null
                || !find && !collection && !fields && request.targetId() == null
                || !find && request.query() != null
                || ((find && !condition) || evidence || fields) && request.settingKey() != null
                || request.scopeName() != null && (!world || request.settingKey() == null)
                || (current || find || collection || workFields)
                    && (request.episodeFrom() != null || request.episodeTo() != null)
                || request.episodeFrom() != null && request.episodeTo() != null && request.episodeFrom() > request.episodeTo()
                || evidence && request.page() != 0
                || request.settingKey() != null && request.settingKey().isBlank()
                || request.scopeName() != null && request.scopeName().isBlank()
                || request.valueEquals() != null && (!condition || request.valueEquals().isBlank()
                    || request.settingKey() == null || request.factType() == null)
                || request.readToken() != null && !collection
                || collection && request.page() > 0 && request.readToken() == null
                || request.worldCategory() != null && request.tool() != SettingTool.FIND_WORLD_SETTINGS
                    && !worldCollection && !(workFields && !characterFields)) {
            throw new AppException(CommonErrorCode.REQUEST_INVALID_ARGUMENT);
        }
        if (request.factType() != null && request.tool() != SettingTool.CHARACTER_CURRENT
                && request.tool() != SettingTool.CHARACTER_HISTORY && !characterFields && !characterCollection && !condition) {
            throw new AppException(CommonErrorCode.REQUEST_INVALID_ARGUMENT);
        }
    }

    private <T> List<T> slice(List<T> items, SettingToolQuery request) {
        int start = Math.min(items.size(), request.page() * request.size());
        return items.subList(start, Math.min(items.size(), start + request.size()));
    }
    private Page<Item> page(List<Item> items, int total, SettingToolQuery request) { return new PageImpl<>(items, PageRequest.of(request.page(), request.size()), total); }
    private SettingToolResponse response(SettingToolQuery request, Page<Item> page, List<String> extra) {
        var limitations = new LinkedHashSet<String>();
        limitations.add("SETTING_BOOK_UNSUPPORTED");
        limitations.addAll(extra);
        boolean partial = extra.contains("WORLD_SOURCE_SCAN_LIMIT");
        for (var item : page.getContent()) {
            if (item.valueTruncated()) { limitations.add("VALUE_LIMIT"); partial = true; }
            if (item.sourcesTruncated()) { limitations.add("SOURCE_REFERENCE_LIMIT"); partial = true; }
            if (item.evidence() != null && (item.evidence().truncated() || item.evidence().status() != EvidenceStatus.AVAILABLE)) partial = true;
        }
        return new SettingToolResponse(1, request.tool(), PageResponse.from(page, page.getContent()), partial, List.copyOf(limitations));
    }
}
