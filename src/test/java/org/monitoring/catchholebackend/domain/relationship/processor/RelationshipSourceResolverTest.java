package org.monitoring.catchholebackend.domain.relationship.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.processor.CharacterFactSourceResolver;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("관계 출처의 현재 프로필 검증")
class RelationshipSourceResolverTest {

    private static final String FAMILY_KEY = "profile.family";
    private final CharacterSnapshotSourceRepository sourceRepository = mock(CharacterSnapshotSourceRepository.class);
    private final WorkCharacterRepository characterRepository = mock(WorkCharacterRepository.class);
    private final CharacterSnapshotAccessor snapshotAccessor = new CharacterSnapshotAccessor();
    private final RelationshipSourceResolver resolver = new RelationshipSourceResolver(
            sourceRepository, characterRepository, snapshotAccessor, new CharacterFactSourceResolver());
    private final Map<UUID, WorkCharacter> characters = new LinkedHashMap<>();
    private final List<CharacterSnapshotSource> sources = new ArrayList<>();
    private Work work;
    private WorkCharacter character;

    @BeforeEach
    void setUp() {
        work = work();
        character = character(work);
        when(sourceRepository.findAllBySourceFactIdIn(any())).thenAnswer(invocation -> {
            Collection<UUID> ids = invocation.getArgument(0);
            return sources.stream().filter(source -> ids.contains(source.getSourceFact().getId())).toList();
        });
        when(characterRepository.findAllById(any())).thenAnswer(invocation -> {
            List<WorkCharacter> found = new ArrayList<>();
            Iterable<UUID> ids = invocation.getArgument(0);
            ids.forEach(id -> {
                if (characters.containsKey(id)) {
                    found.add(characters.get(id));
                }
            });
            return found;
        });
        when(sourceRepository.findAllByCharacterIdsAndFactType(any(), eq(CharacterFactType.PROFILE)))
                .thenAnswer(invocation -> {
                    Collection<UUID> ids = invocation.getArgument(0);
                    return sources.stream()
                            .filter(source -> ids.contains(source.getWorkCharacter().getId()))
                            .filter(source -> source.getFactType() == CharacterFactType.PROFILE)
                            .toList();
                });
        when(characterRepository.findByIdAndWorkIdAndStatus(any(), any(), eq(CharacterStatus.ACTIVE)))
                .thenAnswer(invocation -> Optional.ofNullable(characters.get(invocation.<UUID>getArgument(0)))
                        .filter(found -> found.getWork().getId().equals(invocation.getArgument(1)))
                        .filter(found -> found.getStatus() == CharacterStatus.ACTIVE));
    }

    @Test
    @DisplayName("병합 Fact 각각에 전체 현재값과 같은 출처 토큰을 제공한다")
    void resolvesMergedSourcesUsingWholeCurrentSlot() {
        CharacterFact first = currentSource(character, FAMILY_KEY, "A의 자녀", 0);
        CharacterFact second = currentSource(character, FAMILY_KEY, "B의 형제", 1);
        JsonNode valueJson = JsonNodeFactory.instance.objectNode().put("value", "A의 자녀이며 B의 형제");
        setCurrent(character, FAMILY_KEY, "A의 자녀이며 B의 형제", valueJson);

        Map<UUID, RelationshipSourceContext> contexts = resolver.resolve(work.getId(), List.of(first.getId(), second.getId()));

        assertThat(contexts).hasSize(2);
        assertThat(contexts.get(first.getId()).currentValue().path("factValue").asText())
                .isEqualTo("A의 자녀이며 B의 형제");
        assertThat(contexts.get(first.getId()).currentValue().path("valueJson")).isEqualTo(valueJson);
        assertThat(contexts.get(first.getId()).contextToken()).isEqualTo(contexts.get(second.getId()).contextToken());
        assertThat(contexts.get(first.getId()).contextToken()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("이력 Fact와 현재 slot이 없는 출처를 되살리지 않는다")
    void rejectsHistoricalFactsAndDanglingSourceLinks() {
        CharacterFact historical = fact(character, CharacterFactType.PROFILE, FAMILY_KEY, "옛 관계", null);
        CharacterFact dangling = currentSource(character, FAMILY_KEY, "A의 자녀", 0);

        assertThat(resolver.resolve(work.getId(), List.of(historical.getId(), dangling.getId()))).isEmpty();
        assertThat(resolver.findCurrentSources(work.getId(), character.getId())).isEmpty();
    }

    @Test
    @DisplayName("현재값만 있고 출처 Fact 연결이 없으면 관계 입력으로 제공하지 않는다")
    void excludesCurrentSlotsWithoutProvenance() {
        setCurrent(character, FAMILY_KEY, "A의 자녀", JsonNodeFactory.instance.textNode("A의 자녀"));

        assertThat(resolver.findCurrentSources(work.getId(), character.getId())).isEmpty();
    }

    @Test
    @DisplayName("다른 작품과 보관 인물 및 PROFILE 외 Fact를 제외한다")
    void excludesForeignArchivedAndNonProfileFacts() {
        WorkCharacter foreign = character(work());
        WorkCharacter archived = character(work);
        archived.archive();
        CharacterFact foreignFact = currentSource(foreign, FAMILY_KEY, "A의 자녀", 0);
        CharacterFact archivedFact = currentSource(archived, FAMILY_KEY, "A의 자녀", 0);
        CharacterFact statusFact = fact(character, CharacterFactType.STATUS, "status.test", "A의 자녀", null);
        sources.add(CharacterSnapshotSource.create(character, CharacterFactType.STATUS, "status.test", statusFact, 0));
        setCurrent(foreign, FAMILY_KEY, "A의 자녀", null);
        setCurrent(archived, FAMILY_KEY, "A의 자녀", null);

        assertThat(resolver.resolve(work.getId(), List.of(foreignFact.getId(), archivedFact.getId(), statusFact.getId())))
                .isEmpty();
        assertThat(resolver.findCurrentSources(work.getId(), foreign.getId())).isEmpty();
        assertThat(resolver.findCurrentSources(work.getId(), archived.getId())).isEmpty();
    }

    @Test
    @DisplayName("JSON 객체 key 순서와 출처 반환 순서가 바뀌어도 토큰을 유지한다")
    void canonicalizesObjectKeysAndSourceIds() {
        CharacterFact first = currentSource(character, FAMILY_KEY, "A의 자녀", 0);
        currentSource(character, FAMILY_KEY, "B의 형제", 1);
        ObjectNode original = JsonNodeFactory.instance.objectNode().put("value", "가족");
        original.set("detail", JsonNodeFactory.instance.objectNode().put("parent", "A").put("sibling", "B"));
        setCurrent(character, FAMILY_KEY, "가족", original);
        String before = resolve(first).contextToken();

        java.util.Collections.reverse(sources);
        ObjectNode reordered = JsonNodeFactory.instance.objectNode();
        reordered.set("detail", JsonNodeFactory.instance.objectNode().put("sibling", "B").put("parent", "A"));
        reordered.put("value", "가족");
        setCurrent(character, FAMILY_KEY, "가족", reordered);

        assertThat(resolve(first).contextToken()).isEqualTo(before);
    }

    @Test
    @DisplayName("레거시 현재 JSON은 현재 provenance 순서로 표시값을 보완하고 구조화 값을 보존한다")
    void readsLegacyCurrentSlotThroughExistingSnapshotAccessor() {
        CharacterFact first = currentSource(character, FAMILY_KEY, "A의 자녀", 0);
        currentSource(character, FAMILY_KEY, "A의 자녀이며 B의 형제", 1);
        ObjectNode legacyValue = JsonNodeFactory.instance.objectNode().put("parent", "A").put("sibling", "B");
        character.replaceCurrentSnapshots(null, null,
                JsonNodeFactory.instance.objectNode().set(FAMILY_KEY, legacyValue), null, null, null, null);
        java.util.Collections.reverse(sources);

        RelationshipSourceContext context = resolve(first);

        assertThat(context.currentValue().path("factValue").asText()).isEqualTo("A의 자녀이며 B의 형제");
        assertThat(context.currentValue().path("valueJson")).isEqualTo(legacyValue);
    }

    @Test
    @DisplayName("무관한 설정과 snapshotVersion 변경은 관계 출처 토큰을 바꾸지 않는다")
    void ignoresUnrelatedSnapshotChanges() {
        CharacterFact fact = currentSource(character, FAMILY_KEY, "A의 자녀", 0);
        setCurrent(character, FAMILY_KEY, "A의 자녀", null);
        String before = resolve(fact).contextToken();
        long snapshotVersion = character.getSnapshotVersion();

        setCurrent(character, "profile.occupation", "기사", null);

        assertThat(character.getSnapshotVersion()).isGreaterThan(snapshotVersion);
        assertThat(resolve(fact).contextToken()).isEqualTo(before);
    }

    @Test
    @DisplayName("현재 표시값과 구조화 값 및 전체 출처 집합 변경을 각각 감지한다")
    void detectsDisplayStructuredAndSourceSetChanges() {
        CharacterFact first = currentSource(character, FAMILY_KEY, "A의 자녀", 0);
        setCurrent(character, FAMILY_KEY, "A의 자녀", JsonNodeFactory.instance.objectNode().put("type", "UNKNOWN"));
        String original = resolve(first).contextToken();

        setCurrent(character, FAMILY_KEY, "A의 친자녀", JsonNodeFactory.instance.objectNode().put("type", "UNKNOWN"));
        String displayChanged = resolve(first).contextToken();
        assertThat(displayChanged).isNotEqualTo(original);

        setCurrent(character, FAMILY_KEY, "A의 친자녀", JsonNodeFactory.instance.objectNode().put("type", "BIOLOGICAL"));
        String structuredChanged = resolve(first).contextToken();
        assertThat(structuredChanged).isNotEqualTo(displayChanged);

        currentSource(character, FAMILY_KEY, "A의 친자녀", 1);
        assertThat(resolve(first).contextToken()).isNotEqualTo(structuredChanged);
    }

    @Test
    @DisplayName("원문 근거 파기 후에도 확정된 현재 Fact와 토큰은 유지하고 근거 표지만 끈다")
    void preservesCurrentFactAfterRawEvidencePurge() {
        SettingCandidate candidate = SettingCandidate.create(
                work, null, null, null, SettingEntityType.CHARACTER, character.getName(), FAMILY_KEY, "A의 자녀",
                SettingValueType.STRING, JsonNodeFactory.instance.objectNode().put("value", "A의 자녀"),
                JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("quote", "원문 인용")),
                new BigDecimal("0.95"), JsonNodeFactory.instance.objectNode());
        CharacterFact fact = fact(character, CharacterFactType.PROFILE, FAMILY_KEY, "A의 자녀", candidate);
        sources.add(CharacterSnapshotSource.create(character, CharacterFactType.PROFILE, FAMILY_KEY, fact, 0));
        setCurrent(character, FAMILY_KEY, "A의 자녀", null);
        RelationshipSourceContext before = resolve(fact);
        assertThat(before.hasEvidence()).isTrue();

        candidate.purgeSourceEvidence();
        RelationshipSourceContext after = resolve(fact);

        assertThat(after.hasEvidence()).isFalse();
        assertThat(after.contextToken()).isEqualTo(before.contextToken());
        assertThat(after.currentValue().toString()).doesNotContain("원문 인용");
    }

    @Test
    @DisplayName("수동 수정 표지를 전달하며 반환된 JSON 수정이 실제 현재값을 바꾸지 않는다")
    void reportsManualProtectionWithoutSharingSnapshotJson() {
        CharacterFact fact = currentSource(character, FAMILY_KEY, "A의 자녀", 0);
        setCurrent(character, FAMILY_KEY, "A의 자녀", JsonNodeFactory.instance.objectNode().put("value", "A의 자녀"));
        character.protectManualSlot(CharacterFactType.PROFILE, FAMILY_KEY);

        RelationshipSourceContext context = resolver.findCurrentSources(work.getId(), character.getId()).getFirst();
        ((ObjectNode) context.currentValue().path("valueJson")).put("value", "잘못된 변경");

        assertThat(context.manuallyEdited()).isTrue();
        assertThat(context.hasEvidence()).isFalse();
        assertThat(resolve(fact).currentValue().path("valueJson").path("value").asText()).isEqualTo("A의 자녀");
    }

    @Test
    @DisplayName("현재 Fact 연결을 제거하면 같은 Fact ID의 늦은 요청을 제외한다")
    void dropsRemovedSourceEvenIfFactStillExists() {
        CharacterFact fact = currentSource(character, FAMILY_KEY, "A의 자녀", 0);
        setCurrent(character, FAMILY_KEY, "A의 자녀", null);
        assertThat(resolve(fact)).isNotNull();

        sources.clear();

        assertThat(resolver.resolve(work.getId(), List.of(fact.getId()))).isEmpty();
    }

    @Test
    @DisplayName("빈 출처 요청은 DB 조회 없이 빈 결과를 반환한다")
    void avoidsDatabaseReadsForEmptyInput() {
        assertThat(resolver.resolve(work.getId(), List.of())).isEmpty();
        verifyNoInteractions(sourceRepository, characterRepository);
    }

    private RelationshipSourceContext resolve(CharacterFact fact) {
        return resolver.resolve(work.getId(), List.of(fact.getId())).get(fact.getId());
    }

    private Work work() {
        Work created = Work.create(null, "관계 출처 테스트", WorkGenre.FANTASY, null);
        ReflectionTestUtils.setField(created, "id", UUID.randomUUID());
        return created;
    }

    private WorkCharacter character(Work ownerWork) {
        WorkCharacter created = WorkCharacter.create(ownerWork, "아리아", null, null, null,
                null, null, null, null, null, null);
        ReflectionTestUtils.setField(created, "id", UUID.randomUUID());
        characters.put(created.getId(), created);
        return created;
    }

    private CharacterFact currentSource(WorkCharacter owner, String key, String value, int order) {
        CharacterFact fact = fact(owner, CharacterFactType.PROFILE, key, value, null);
        sources.add(CharacterSnapshotSource.create(owner, CharacterFactType.PROFILE, key, fact, order));
        return fact;
    }

    private CharacterFact fact(
            WorkCharacter owner,
            CharacterFactType type,
            String key,
            String value,
            SettingCandidate candidate
    ) {
        CharacterFact fact = CharacterFact.create(owner, candidate, type, key, value, value,
                null, null, null, null, null, null);
        ReflectionTestUtils.setField(fact, "id", UUID.randomUUID());
        return fact;
    }

    private void setCurrent(WorkCharacter owner, String key, String value, JsonNode valueJson) {
        Map<CharacterSnapshotSlot, CharacterSnapshotEntry> snapshot = snapshotAccessor.read(owner);
        snapshot.put(new CharacterSnapshotSlot(CharacterFactType.PROFILE, key),
                snapshotAccessor.entry(CharacterFactType.PROFILE, key, value, valueJson));
        snapshotAccessor.replace(owner, snapshot);
    }
}
