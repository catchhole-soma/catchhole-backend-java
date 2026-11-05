package org.monitoring.catchholebackend.domain.validation.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode;
import org.monitoring.catchholebackend.domain.validation.repository.ValidationCorrectionRepository;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingLatestSourceEpisode;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;

/**
 * #381 검수가 읽는 현재 확정 설정. 접수 때 설정을 복사하지 않으며, 검수가 진행되는 동안의 일관성은 {@link ValidationSettingsLock}이 보장한다.
 *
 * <p>주체 색인은 scalar 조회 세 번(인물·인물 발견 별칭·세계관)이고 전체 상태는 요청한 주체(최대 {@value #MAX_FULL_SUBJECTS}개)만 id로 읽는다.
 * SettingCandidate·WorldSettingCandidate·CharacterFact·AnalysisJob 엔티티는 읽지 않는다. 행은 legacy baseline_entries와 같은
 * {@code {domain, subjectRef, state}} 모양이고, AI 모델 투영(compact_state) 뒤의 값은 legacy seed와 같다(ValidationLiveSettingsIntegrationTest).
 * 주체 하나에 행 하나라서 B 번호와 S 번호가 같다. worldHistory 행은 없다(SETTINGS로 나간 적이 없다).
 */
@Component
@RequiredArgsConstructor
public class ValidationLiveSettings {
    public static final int MAX_FULL_SUBJECTS = 100;
    public static final String CHARACTERS = "characters";
    public static final String WORLD_SETTINGS = "worldSettings";

    private final WorkCharacterRepository characters;
    private final CharacterSnapshotSourceRepository snapshotSources;
    private final SettingCandidateRepository characterCandidates;
    private final WorldSettingRepository worldSettings;
    private final WorldSettingCandidateRepository worldCandidates;
    private final ValidationCorrectionRepository corrections;
    private final CharacterSnapshotAccessor snapshots;
    private final CharacterAnalysisStateMapper characterStates;
    private final WorldSettingAnalysisStateMapper worldStates;
    private final AnalysisStateJournal journal;

    /** 색인의 주체 하나. number는 1부터이며 B·S 참조가 같은 번호를 쓴다. version은 인물 snapshotVersion 또는 세계관 version이다. */
    public record Subject(int number, String domain, UUID id, String name, List<String> aliases, long version) {
        public String entryRef() { return "B" + number; }
        public String subjectRef() { return "S" + number; }
        /** legacy 기준 항목과 같은 subjectRef(character:/world: 접두사). */
        public String stateRef() {
            return CHARACTERS.equals(domain) ? CharacterAnalysisStateMapper.persistedRef(id) : WorldSettingAnalysisStateMapper.persistedRef(id);
        }
    }

    /** 작품의 현재 주체 색인. 순서는 ACTIVE 인물(생성 역순, 같은 시각이면 id) 다음 세계관(id)이다. */
    public record Index(UUID workId, List<Subject> subjects) {
        /** "S<n>" 또는 "B<n>" 참조의 주체. 형식이 틀리거나 범위 밖이면 비어 있다. */
        public Optional<Subject> find(String ref) {
            if (ref == null || !ref.matches("[SB][1-9][0-9]{0,8}")) return Optional.empty();
            int number = Integer.parseInt(ref.substring(1));
            return number <= subjects.size() ? Optional.of(subjects.get(number - 1)) : Optional.empty();
        }
    }

    public Index index(UUID workId) {
        Map<UUID, List<String>> rawAliases = new HashMap<>();
        for (var alias : characterCandidates.findConfirmedDiscoveryAliases(workId)) {
            rawAliases.computeIfAbsent(alias.characterId(), ignored -> new ArrayList<>()).add(alias.entityName());
        }
        List<Subject> subjects = new ArrayList<>();
        for (var row : characters.findSettingVersions(workId, CharacterStatus.ACTIVE)) {
            subjects.add(new Subject(subjects.size() + 1, CHARACTERS, row.id(), row.name(),
                    aliases(row.name(), rawAliases.getOrDefault(row.id(), List.of())), row.snapshotVersion()));
        }
        for (var row : worldSettings.findSettingVersions(workId)) {
            subjects.add(new Subject(subjects.size() + 1, WORLD_SETTINGS, row.id(), row.subjectName(), List.of(), row.version()));
        }
        return new Index(workId, List.copyOf(subjects));
    }

    /** 확정 인물 발견 이름의 trim·공백 제외·대표 이름 제외·중복 제거(CharacterAnalysisStateMapper.addConfirmedIdentity와 같은 규칙·순서). */
    private static List<String> aliases(String name, Collection<String> entityNames) {
        return entityNames.stream().filter(Objects::nonNull).map(String::trim)
                .filter(alias -> !alias.isBlank() && !alias.equals(name)).distinct().toList();
    }

    /**
     * 설정 버전 fingerprint. ACTIVE 인물의 (id, snapshotVersion)과 세계관의 (id, version)만 scalar 두 번으로 읽어 hash한다.
     * 값이 바뀌면 version이 오르고 보관·복구·생성·삭제는 id 집합을 바꾼다. 이름만 바꾸는 updateBasicInfo는 version을 올리지 않으므로
     * 잡지 못한다(사람의 쓰기는 잠금이 막는다).
     */
    public String fingerprint(UUID workId) {
        List<Subject> subjects = new ArrayList<>();
        for (var row : characters.findSettingVersions(workId, CharacterStatus.ACTIVE)) {
            subjects.add(new Subject(0, CHARACTERS, row.id(), row.name(), List.of(), row.snapshotVersion()));
        }
        for (var row : worldSettings.findSettingVersions(workId)) {
            subjects.add(new Subject(0, WORLD_SETTINGS, row.id(), row.subjectName(), List.of(), row.version()));
        }
        return journal.hash(fingerprintState(subjects));
    }

    public String fingerprint(Index index) {
        return journal.hash(fingerprintState(index.subjects()));
    }

    /** {"fingerprintVersion":1,"characters":[[id,version]…],"worldSettings":[[id,version]…]}, 각 목록은 id 문자열 순(조회 순서와 무관). */
    static ObjectNode fingerprintState(Collection<Subject> subjects) {
        ObjectNode state = JsonNodeFactory.instance.objectNode().put("fingerprintVersion", 1);
        for (String domain : List.of(CHARACTERS, WORLD_SETTINGS)) {
            ArrayNode rows = state.putArray(domain);
            subjects.stream().filter(subject -> domain.equals(subject.domain()))
                    .sorted(Comparator.comparing(subject -> subject.id().toString()))
                    .forEach(subject -> rows.addArray().add(subject.id().toString()).add(subject.version()));
        }
        return state;
    }

    /**
     * 색인 순서의 기준 항목 행. refs(S·B 참조)가 가리키는 주체는 전체 상태, 나머지는 {@code state{name, aliases}}만 담는다.
     * 알 수 없는 참조는 무시한다(호출자가 검증한다). 전체 상태 주체가 {@value #MAX_FULL_SUBJECTS}개를 넘으면 거절한다.
     * 색인 뒤 주체가 사라지거나 보관됐으면 409 VALIDATION_SOURCE_VERSION_CONFLICT다.
     */
    public ArrayNode entries(Index index, Collection<String> refs) {
        Set<Subject> full = new LinkedHashSet<>();
        for (String ref : refs) index.find(ref).ifPresent(full::add);
        if (full.size() > MAX_FULL_SUBJECTS)
            throw new IllegalArgumentException("한 번에 읽을 수 있는 설정 주체는 " + MAX_FULL_SUBJECTS + "개까지입니다.");
        Map<UUID, ObjectNode> states = new HashMap<>();
        states.putAll(characterStates(index.workId(), full.stream().filter(subject -> CHARACTERS.equals(subject.domain())).toList()));
        states.putAll(worldStates(index.workId(), full.stream().filter(subject -> WORLD_SETTINGS.equals(subject.domain())).toList()));
        if (!full.isEmpty()) attachCorrections(index.workId(), full, states);
        ArrayNode rows = JsonNodeFactory.instance.arrayNode();
        for (Subject subject : index.subjects()) {
            ObjectNode row = rows.addObject().put("domain", subject.domain()).put("subjectRef", subject.stateRef());
            ObjectNode state = states.get(subject.id());
            if (state == null) {
                state = JsonNodeFactory.instance.objectNode().put("name", subject.name());
                ArrayNode aliases = state.putArray("aliases");
                subject.aliases().forEach(aliases::add);
            }
            row.set("state", state);
        }
        return rows;
    }

    private Map<UUID, ObjectNode> characterStates(UUID workId, List<Subject> subjects) {
        if (subjects.isEmpty()) return Map.of();
        Map<UUID, WorkCharacter> loaded = characters.findAllById(subjects.stream().map(Subject::id).toList()).stream()
                .collect(Collectors.toMap(WorkCharacter::getId, Function.identity()));
        Map<UUID, Map<CharacterSnapshotSlot, CharacterSnapshotEntry>> stored = new HashMap<>();
        List<UUID> legacy = new ArrayList<>();
        for (Subject subject : subjects) {
            WorkCharacter character = loaded.get(subject.id());
            if (character == null || character.getStatus() != CharacterStatus.ACTIVE || !workId.equals(character.getWork().getId())) throw changed();
            var entries = snapshots.read(character);
            stored.put(subject.id(), entries);
            if (entries.values().stream().anyMatch(entry -> !entry.factValuePersisted())) legacy.add(subject.id());
        }
        // 내부 envelope 전 raw snapshot만 근거 Fact 표시값이 필요하다. Fact·후보 엔티티 없이 scalar로 읽는다.
        Map<UUID, Map<CharacterSnapshotSlot, String>> lastValues = new HashMap<>();
        if (!legacy.isEmpty()) {
            for (var value : snapshotSources.findSourceFactValues(legacy)) {
                lastValues.computeIfAbsent(value.characterId(), ignored -> new HashMap<>())
                        .put(new CharacterSnapshotSlot(value.factType(), value.factKey()), value.factValue());
            }
        }
        Map<UUID, ObjectNode> result = new HashMap<>();
        for (Subject subject : subjects) {
            WorkCharacter character = loaded.get(subject.id());
            var entries = lastValues.containsKey(subject.id())
                    ? snapshots.readWithLastSourceFactValues(character, lastValues.get(subject.id())) : stored.get(subject.id());
            result.put(subject.id(), characterStates.toCurrentValueState(character, subject.aliases(), entries));
        }
        return result;
    }

    private Map<UUID, ObjectNode> worldStates(UUID workId, List<Subject> subjects) {
        if (subjects.isEmpty()) return Map.of();
        List<UUID> ids = subjects.stream().map(Subject::id).toList();
        Map<UUID, WorldSetting> loaded = worldSettings.findAllById(ids).stream()
                .collect(Collectors.toMap(WorldSetting::getId, Function.identity()));
        Map<UUID, Integer> latest = new HashMap<>();
        for (WorldSettingLatestSourceEpisode row : worldCandidates.findLatestConfirmedSourceEpisodes(ids)) latest.put(row.worldSettingId(), row.episodeNo());
        Map<UUID, ObjectNode> result = new HashMap<>();
        for (Subject subject : subjects) {
            WorldSetting setting = loaded.get(subject.id());
            if (setting == null || !workId.equals(setting.getWork().getId())) throw changed();
            ObjectNode state = worldStates.toState(setting);
            // 경로별 provenance 자리표시(출처 없음)는 후보를 읽지 않는 live 행에서 사실이 아니므로 싣지 않는다. 모델 투영도 버린다.
            state.remove("provenanceByPath");
            state.put("latestSourceEpisodeNo", latest.get(subject.id()));
            result.put(subject.id(), state);
        }
        return result;
    }

    /** legacy normalize처럼 대상의 작가 정정 사실을 state.explicitAuthorCorrections로 붙인다(SETTINGS의 authorCorrectionProofs). */
    private void attachCorrections(UUID workId, Collection<Subject> subjects, Map<UUID, ObjectNode> states) {
        ArrayNode scopes = correctionScopes(workId);
        if (scopes.isEmpty()) return;
        for (Subject subject : subjects) {
            ArrayNode matched = JsonNodeFactory.instance.arrayNode();
            for (JsonNode scope : scopes)
                if (subject.domain().equals(scope.path("domain").asText()) && subject.id().toString().equals(scope.path("targetId").asText()))
                    matched.add(scope.deepCopy());
            if (!matched.isEmpty()) states.get(subject.id()).set("explicitAuthorCorrections", matched);
        }
    }

    /** 작품의 실제 작가 정정 scope(이전 정정 포함, 생성 순). ValidationBaselineService에서 옮겨 왔다. */
    public ArrayNode correctionScopes(UUID workId) {
        var scopes = new LinkedHashMap<String, JsonNode>();
        for (var correction : corrections.findAllByWorkIdOrderByCreatedAtAscIdAsc(workId)) {
            ObjectNode scope = correction.getScope().deepCopy();
            for (var prior : scope.path("priorCorrections")) scopes.putIfAbsent(prior.path("correctionId").asText(), prior.deepCopy());
            scope.remove("priorCorrections");
            scope.put("kind", "AUTHOR_CORRECTION").put("correctionId", correction.getId().toString()).put("baselineId", correction.getReport().getId().toString())
                    .put("baselineRevision", correction.getBaselineRevision()).put("entryRef", correction.getEntryRef()).put("entryHash", correction.getEntryHash());
            scopes.put(correction.getId().toString(), scope);
        }
        var result = JsonNodeFactory.instance.arrayNode(); scopes.values().forEach(result::add); return result;
    }

    private AppException changed() { return new AppException(ValidationErrorCode.VALIDATION_SOURCE_VERSION_CONFLICT); }
}
