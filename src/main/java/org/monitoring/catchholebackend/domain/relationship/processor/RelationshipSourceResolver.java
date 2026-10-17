package org.monitoring.catchholebackend.domain.relationship.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.processor.CharacterFactSourceResolver;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.springframework.stereotype.Component;

/**
 * 관계의 출처는 실제 현재 프로필과 provenance 양쪽에 존재해야 한다. 호출자는 작품 권한과
 * 트랜잭션을 소유하며, 관계 변경 시 기존 설정 변경과 같은 작품 잠금 아래에서 재검증한다.
 */
@Component
@RequiredArgsConstructor
public class RelationshipSourceResolver {

    private final CharacterSnapshotSourceRepository sourceRepository;
    private final WorkCharacterRepository characterRepository;
    private final CharacterSnapshotAccessor snapshotAccessor;
    private final CharacterFactSourceResolver factSourceResolver;

    public Map<UUID, RelationshipSourceContext> resolve(UUID workId, Collection<UUID> factIds) {
        Set<UUID> requestedIds = new LinkedHashSet<>(factIds);
        if (requestedIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> characterIds = sourceRepository.findAllBySourceFactIdIn(requestedIds).stream()
                .filter(source -> source.getFactType() == CharacterFactType.PROFILE)
                .map(source -> source.getWorkCharacter().getId())
                .distinct()
                .toList();
        if (characterIds.isEmpty()) {
            return Map.of();
        }
        List<WorkCharacter> characters = characterRepository.findAllById(characterIds).stream()
                .filter(character -> isEligibleCharacter(character, workId))
                .toList();
        Map<UUID, RelationshipSourceContext> result = new LinkedHashMap<>();
        for (RelationshipSourceContext context : resolveCurrentSources(characters)) {
            if (requestedIds.contains(context.factId())) {
                result.put(context.factId(), context);
            }
        }
        return result;
    }

    public List<RelationshipSourceContext> findCurrentSources(UUID workId, UUID characterId) {
        return characterRepository.findByIdAndWorkIdAndStatus(characterId, workId, CharacterStatus.ACTIVE)
                .filter(character -> isEligibleCharacter(character, workId))
                .map(character -> resolveCurrentSources(List.of(character)))
                .orElseGet(List::of);
    }

    private boolean isEligibleCharacter(WorkCharacter character, UUID workId) {
        return character.getStatus() == CharacterStatus.ACTIVE
                && character.getWork() != null
                && Objects.equals(character.getWork().getId(), workId);
    }

    private List<RelationshipSourceContext> resolveCurrentSources(List<WorkCharacter> characters) {
        if (characters.isEmpty()) {
            return List.of();
        }
        Map<UUID, WorkCharacter> charactersById = new LinkedHashMap<>();
        characters.stream().sorted(Comparator.comparing(character -> character.getId().toString()))
                .forEach(character -> charactersById.put(character.getId(), character));
        Map<UUID, Map<CharacterSnapshotSlot, List<CharacterFact>>> sourcesByCharacter = new LinkedHashMap<>();
        sourceRepository.findAllByCharacterIdsAndFactType(charactersById.keySet(), CharacterFactType.PROFILE)
                .stream()
                .sorted(Comparator.comparingInt(CharacterSnapshotSource::getSourceOrder))
                .filter(source -> isConsistentProfileSource(source, charactersById))
                .forEach(source -> sourcesByCharacter
                        .computeIfAbsent(source.getWorkCharacter().getId(), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(new CharacterSnapshotSlot(source.getFactType(), source.getFactKey()),
                                ignored -> new ArrayList<>())
                        .add(source.getSourceFact()));

        List<RelationshipSourceContext> result = new ArrayList<>();
        for (WorkCharacter character : charactersById.values()) {
            Map<CharacterSnapshotSlot, List<CharacterFact>> sources = sourcesByCharacter
                    .getOrDefault(character.getId(), Map.of());
            Map<CharacterSnapshotSlot, CharacterSnapshotEntry> current = snapshotAccessor.read(character, sources);
            sources.entrySet().stream()
                    .filter(entry -> current.containsKey(entry.getKey()))
                    .sorted(Comparator.comparing(entry -> entry.getKey().factKey()))
                    .forEach(entry -> appendContexts(result, character, current.get(entry.getKey()), entry.getValue()));
        }
        return List.copyOf(result);
    }

    /** FK만으로 표현되지 않는 캐릭터·유형·key 일치도 읽기 경계에서 확인한다. */
    private boolean isConsistentProfileSource(
            CharacterSnapshotSource source,
            Map<UUID, WorkCharacter> charactersById
    ) {
        CharacterFact fact = source.getSourceFact();
        UUID characterId = source.getWorkCharacter().getId();
        return charactersById.containsKey(characterId)
                && source.getFactType() == CharacterFactType.PROFILE
                && fact.getFactType() == CharacterFactType.PROFILE
                && Objects.equals(fact.getWorkCharacter().getId(), characterId)
                && Objects.equals(fact.getFactKey(), source.getFactKey());
    }

    private void appendContexts(
            List<RelationshipSourceContext> result,
            WorkCharacter character,
            CharacterSnapshotEntry entry,
            List<CharacterFact> facts
    ) {
        ObjectNode currentValue = JsonNodeFactory.instance.objectNode();
        currentValue.put("factValue", entry.factValue());
        currentValue.set("valueJson", entry.valueJson() == null
                ? JsonNodeFactory.instance.nullNode() : entry.valueJson().deepCopy());
        String contextToken = contextToken(currentValue, facts);
        boolean manuallyEdited = character.isManuallyEditedSlot(entry.slot().factType(), entry.slot().factKey());
        for (CharacterFact fact : facts) {
            result.add(new RelationshipSourceContext(
                    fact.getId(), character.getId(), entry.slot().factKey(), currentValue.deepCopy(), contextToken,
                    factSourceResolver.hasEvidence(fact), manuallyEdited
            ));
        }
    }

    private String contextToken(JsonNode currentValue, List<CharacterFact> facts) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.set("currentValue", currentValue);
        ArrayNode sourceIds = input.putArray("sourceFactIds");
        facts.stream().map(fact -> fact.getId().toString()).distinct().sorted().forEach(sourceIds::add);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalize(input).toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode canonical = JsonNodeFactory.instance.objectNode();
            value.properties().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> canonical.set(entry.getKey(), canonicalize(entry.getValue())));
            return canonical;
        }
        if (value.isArray()) {
            ArrayNode canonical = JsonNodeFactory.instance.arrayNode();
            value.forEach(element -> canonical.add(canonicalize(element)));
            return canonical;
        }
        return value;
    }
}
