package org.monitoring.catchholebackend.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisLiveStateReader;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisStateSource;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * #387 자동 누적 format 2의 live 읽기. 대상별 읽기·claim 현재값 읽기가 기존 claim capture에서 같은 대상을 읽은 값과 같고,
 * 지문이 그 값을 바꾸는 쓰기마다 달라지는지 확인한다. 값 비교는 hash와 같은 canonical JSON으로 한다(JSONB 왕복의 숫자 노드 종류 차이 제외).
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.jpa.properties.hibernate.generate_statistics=true"})
@ActiveProfiles("test")
@Transactional
@DisplayName("#387 자동 누적 live 읽기와 claim capture의 동등성, 지문")
class AnalysisLiveStateReaderIntegrationTest {
    static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    @Autowired EntityManager em;
    @Autowired AnalysisLiveStateReader reader;
    @Autowired AnalysisStateJournal journal;
    @Autowired CharacterSnapshotAccessor snapshots;
    @Autowired List<AnalysisStateSource> stateSources;

    Member member; Work work; Episode prior70; Episode prior100; AnalysisJob job70; AnalysisJob job100;
    WorkCharacter yunseo; WorkCharacter kangmin; WorkCharacter archived;
    WorldSetting elves; WorldSetting capital;
    SettingCandidate yunseoDiscovery; WorldSettingCandidate elvesCurrent;

    @BeforeEach
    void fixture() {
        member = Member.register(UUID.randomUUID() + "@test.invalid", "test-only", null, "합성 작가"); em.persist(member);
        work = Work.create(member, "live 입력", WorkGenre.FANTASY, "합성"); em.persist(work);
        prior70 = Episode.create(work, null, 70, "수리", "synthetic/70.txt", "v1", "7".repeat(64), 12); em.persist(prior70);
        prior100 = Episode.create(work, null, 100, "이전", "synthetic/100.txt", "v1", "a".repeat(64), 12); em.persist(prior100);
        job70 = AnalysisJob.create(work, null, prior70, AnalysisJobType.SETTING_EXTRACTION); em.persist(job70);
        job100 = AnalysisJob.create(work, null, prior100, AnalysisJobType.SETTING_EXTRACTION); em.persist(job100);

        // 윤서: envelope snapshot, 수동 보호 slot, 대표 서사 시점, 확정 인물 발견 별칭, 근거 Fact와 이력 Fact.
        yunseo = WorkCharacter.create(work, "윤서", "주인공", null, null, null, null, null, null, null, null); em.persist(yunseo);
        var entries = new LinkedHashMap<CharacterSnapshotSlot, CharacterSnapshotEntry>();
        for (var entry : List.of(
                snapshots.entry(CharacterFactType.AGE, "age", "17", JSON.objectNode().put("value", 17)),
                snapshots.entry(CharacterFactType.PROFILE, "직업", "검사", JSON.objectNode().put("value", "검사")),
                snapshots.entry(CharacterFactType.STATUS, "부상", "왼팔 골절", JSON.objectNode().put("value", "왼팔 골절").put("active", true))))
            entries.put(entry.slot(), entry);
        snapshots.replace(yunseo, entries);
        yunseo.protectManualSlot(CharacterFactType.PROFILE, "직업");
        yunseoDiscovery = discovery("서야", yunseo);
        discovery("윤이", yunseo);
        var time = JSON.objectNode().put("description", "새벽 출정 직후");
        time.putArray("evidenceSpans").addObject().put("quote", "새벽").put("startOffset", 0).put("endOffset", 2).put("sourceChunkId", UUID.randomUUID().toString());
        time.put("sourceCandidateId", yunseoDiscovery.getId().toString());
        yunseo.updateCurrentNarrativeTime(time, true);
        var current = CharacterFact.create(yunseo, settingCandidate(yunseo, "직업", "검사"), CharacterFactType.PROFILE, "직업", "검사", "검사", null, prior70, null, job70, BigDecimal.ONE, 70);
        em.persist(current); em.persist(CharacterSnapshotSource.create(yunseo, CharacterFactType.PROFILE, "직업", current, 0));
        em.persist(CharacterFact.create(yunseo, settingCandidate(yunseo, "직업", "견습 검사"), CharacterFactType.PROFILE, "직업", "견습 검사", "견습 검사", null, prior70, null, job70, BigDecimal.ONE, 12));

        // 강민: envelope 전 raw snapshot과 근거 Fact.
        var profile = JSON.objectNode(); profile.putObject("직업").put("value", "용병");
        var statuses = JSON.objectNode(); statuses.putObject("부상").put("value", "없음");
        kangmin = WorkCharacter.create(work, "강민", "용병", 20, 5, profile, null, null, null, statuses, null); em.persist(kangmin);
        discovery("민이", kangmin);
        var job = CharacterFact.create(kangmin, settingCandidate(kangmin, "직업", "용병"), CharacterFactType.PROFILE, "직업", "용병", "용병", null, prior70, null, job70, BigDecimal.ONE, 70);
        em.persist(job); em.persist(CharacterSnapshotSource.create(kangmin, CharacterFactType.PROFILE, "직업", job, 0));

        archived = WorkCharacter.create(work, "보관 인물", null, null, null, null, null, null, null, null, null); em.persist(archived);
        archived.archive();

        // 엘프: root·scope 설정, 수동 보호 경로, 확정 현재 근거와 이력 전용·검토 대기 후보. 수도: 후보 없음.
        elves = WorldSetting.create(work, WorldSettingCategory.RACE, "엘프", "서식지", "숲"); em.persist(elves);
        elves.addProperty("외형", "귀", "뾰족함");
        elves.protectManualProperty(null, "서식지");
        elvesCurrent = worldCandidate(elves, prior70, job70, null, "서식지", "숲", false, WorldSettingReviewStatus.CONFIRMED);
        worldCandidate(elves, prior100, job100, "외형", "귀", "뾰족함", true, WorldSettingReviewStatus.CONFIRMED);
        worldCandidate(elves, prior100, job100, null, "수명", "천 년", false, WorldSettingReviewStatus.PENDING_REVIEW);
        capital = WorldSetting.create(work, WorldSettingCategory.LOCATION, "수도", "위치", "대륙 중앙"); em.persist(capital);
        em.flush();
        // capture 순서(createdAt desc)를 고정한다. 강민이 더 최근이다.
        setCreatedAt(yunseo, LocalDateTime.of(2026, 1, 1, 9, 0));
        setCreatedAt(kangmin, LocalDateTime.of(2026, 1, 1, 10, 0));
        setCreatedAt(archived, LocalDateTime.of(2026, 1, 1, 11, 0));
        em.clear();
        work = em.find(Work.class, work.getId());
    }

    SettingCandidate discovery(String name, WorkCharacter owner) {
        var candidate = SettingCandidate.createCharacterDiscovery(work, prior70, null, job70, name, name, owner.getId(),
                SettingCandidateMatchStatus.MATCHED, JSON.arrayNode(), BigDecimal.ONE, null);
        em.persist(candidate); ReflectionTestUtils.setField(candidate, "reviewStatus", SettingCandidateReviewStatus.CONFIRMED);
        return candidate;
    }

    SettingCandidate settingCandidate(WorkCharacter owner, String attribute, String value) {
        var candidate = SettingCandidate.create(work, prior70, null, job70, SettingEntityType.CHARACTER, owner.getName(), owner.getName(), owner.getId(),
                SettingCandidateMatchStatus.MATCHED, attribute, value, SettingValueType.STRING, null, JSON.arrayNode(), BigDecimal.ONE, null);
        em.persist(candidate); ReflectionTestUtils.setField(candidate, "reviewStatus", SettingCandidateReviewStatus.CONFIRMED);
        return candidate;
    }

    WorldSettingCandidate worldCandidate(WorldSetting setting, Episode episode, AnalysisJob job, String scope, String name, String value,
            boolean historyOnly, WorldSettingReviewStatus status) {
        var candidate = WorldSettingCandidate.create(work, episode, job, setting.getCategory(), setting.getSubjectName(), name, value,
                JSON.arrayNode().add(JSON.objectNode().put("quote", value)), BigDecimal.ONE, null);
        ReflectionTestUtils.setField(candidate, "targetWorldSetting", setting);
        ReflectionTestUtils.setField(candidate, "reviewStatus", status);
        ReflectionTestUtils.setField(candidate, "finalCategory", setting.getCategory());
        ReflectionTestUtils.setField(candidate, "finalSubjectName", setting.getSubjectName());
        ReflectionTestUtils.setField(candidate, "finalScopeName", scope);
        ReflectionTestUtils.setField(candidate, "finalSettingName", name);
        ReflectionTestUtils.setField(candidate, "finalValue", value);
        ReflectionTestUtils.setField(candidate, "finalOperation", WorldSettingOperation.ADD);
        if (status == WorldSettingReviewStatus.CONFIRMED) ReflectionTestUtils.setField(candidate, "reviewedAt", LocalDateTime.now());
        if (historyOnly) candidate.markHistoryOnly();
        em.persist(candidate);
        return candidate;
    }

    void setCreatedAt(WorkCharacter character, LocalDateTime at) {
        em.createNativeQuery("update characters set created_at = :at where id = :id").setParameter("at", at).setParameter("id", character.getId()).executeUpdate();
    }

    ObjectNode capture(String domain) {
        return (ObjectNode) stateSources.stream().filter(source -> domain.equals(source.domain())).findFirst().orElseThrow().capture(work);
    }

    String canonical(JsonNode node) {
        return journal.canonicalJson(node);
    }

    Statistics statistics() {
        return em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    String characterRef(WorkCharacter character) { return CharacterAnalysisStateMapper.persistedRef(character.getId()); }
    String worldRef(WorldSetting setting) { return WorldSettingAnalysisStateMapper.persistedRef(setting.getId()); }

    @Test
    @DisplayName("대상별 live 읽기는 claim capture에서 같은 대상을 잘라낸 값과 같고 없는·보관·임시·형식이 틀린 ref는 담지 않는다")
    void subjectReadsMatchWholeCapture() {
        for (String domain : List.of("characters", "worldSettings")) {
            ObjectNode full = capture(domain);
            em.clear(); work = em.find(Work.class, work.getId());
            assertThat(full.size()).as(domain).isEqualTo(2);
            List<String> refs = new ArrayList<>();
            full.fieldNames().forEachRemaining(refs::add);
            List<String> noise = "characters".equals(domain)
                    ? List.of(characterRef(archived), "character:" + UUID.randomUUID(), "character:not-a-uuid",
                            CharacterAnalysisStateMapper.provisionalRef(UUID.randomUUID()), worldRef(elves))
                    : List.of("world:" + UUID.randomUUID(), "world:not-a-uuid", WorldSettingAnalysisStateMapper.provisionalRef(UUID.randomUUID()),
                            characterRef(yunseo));
            List<String> requested = new ArrayList<>(refs); requested.addAll(noise);
            assertThat(canonical(reader.subjects(work, domain, requested))).as(domain).isEqualTo(canonical(full));
            for (String ref : refs) {
                ObjectNode one = reader.subjects(work, domain, List.of(ref));
                assertThat(one.size()).isEqualTo(1);
                assertThat(canonical(one.get(ref))).as("%s/%s", domain, ref).isEqualTo(canonical(full.get(ref)));
            }
            assertThat(reader.subjects(work, domain, noise).isEmpty()).isTrue();
        }
        // 의미 있는 fixture: 대상별 읽기가 이력·근거 사본·식별 근거·서사 시점을 모두 담는다.
        JsonNode yunseoState = reader.subjects(work, "characters", List.of(characterRef(yunseo))).get(characterRef(yunseo));
        assertThat(yunseoState.path("historyFacts").toString()).contains("견습 검사");
        assertThat(yunseoState.path("slots").path("PROFILE:직업").has("supportingSources")).isTrue();
        assertThat(yunseoState.path("aliases").toString()).isEqualTo("[\"서야\",\"윤이\"]");
        assertThat(yunseoState.path("currentNarrativeTime").path("description").asText()).isEqualTo("새벽 출정 직후");
        assertThat(reader.subjects(work, "worldSettings", List.of(worldRef(elves))).get(worldRef(elves)).path("provenanceByPath").toString())
                .contains(elvesCurrent.getId().toString());
    }

    @Test
    @DisplayName("claim 현재값 읽기는 capture에서 이력·근거 사본만 뺀 값과 같고 대상 순서도 capture와 같다")
    void currentValuesMatchCaptureWithoutHistory() {
        ObjectNode characters = capture("characters");
        ObjectNode worlds = capture("worldSettings");
        em.clear(); work = em.find(Work.class, work.getId());
        ObjectNode current = reader.currentSubjects(work);
        assertThat(current.properties()).extracting(java.util.Map.Entry::getKey).containsExactly("characters", "worldSettings");
        for (var entry : List.of(java.util.Map.entry("characters", characters), java.util.Map.entry("worldSettings", worlds))) {
            ObjectNode expected = JSON.objectNode();
            entry.getValue().properties().forEach(subject -> expected.set(subject.getKey(),
                    reader.toCurrentValues(entry.getKey(), (ObjectNode) subject.getValue())));
            assertThat(canonical(current.get(entry.getKey()))).as(entry.getKey()).isEqualTo(canonical(expected));
            List<String> expectedOrder = new ArrayList<>(); entry.getValue().fieldNames().forEachRemaining(expectedOrder::add);
            List<String> actualOrder = new ArrayList<>(); current.get(entry.getKey()).fieldNames().forEachRemaining(actualOrder::add);
            assertThat(actualOrder).as("claim 응답 순서").isEqualTo(expectedOrder);
        }
        assertThat(current.path("characters").fieldNames()).toIterable().containsExactly(characterRef(kangmin), characterRef(yunseo));
        // 빼는 것은 이력·근거 사본뿐이다. claim이 읽는 값(별칭·식별 근거·출처·서사 시점·STATUS)은 남는다.
        JsonNode yunseoState = current.path("characters").path(characterRef(yunseo));
        assertThat(yunseoState.has("historyFacts")).isFalse();
        assertThat(yunseoState.path("slots").path("PROFILE:직업").has("supportingSources")).isFalse();
        assertThat(yunseoState.path("slots").path("PROFILE:직업").path("provenance").path("currentSourceTimes").isArray()).isTrue();
        assertThat(yunseoState.has("identityEvidence")).isTrue();
        assertThat(yunseoState.path("provenance").path("sourceCandidateIds").size()).isEqualTo(2);
        assertThat(yunseoState.path("currentNarrativeTime").has("supportingSource")).isTrue();
        assertThat(yunseoState.path("slots").path("STATUS:부상").path("factValue").asText()).isEqualTo("왼팔 골절");
        JsonNode elvesState = current.path("worldSettings").path(worldRef(elves));
        assertThat(elvesState.has("provenance")).isFalse();
        assertThat(elvesState.has("provenanceByPath")).isFalse();
        assertThat(elvesState.path("propertiesJson").toString()).contains("서식지", "뾰족함");
        assertThat(elvesState.path("latestSourceEpisodeNo").asInt()).isEqualTo(70);
        assertThat(current.path("worldSettings").path(worldRef(capital)).path("latestSourceEpisodeNo").isNull()).isTrue();
    }

    @Test
    @DisplayName("분류별 세계관 ref는 그 분류의 지금 대상만 id 순으로 준다")
    void worldSubjectRefsByCategory() {
        assertThat(reader.worldSubjectRefs(work.getId(), "RACE")).containsExactly(worldRef(elves));
        assertThat(reader.worldSubjectRefs(work.getId(), "LOCATION")).containsExactly(worldRef(capital));
        assertThat(reader.worldSubjectRefs(work.getId(), "POWER_SYSTEM")).isEmpty();
    }

    @Test
    @DisplayName("한 대상 읽기는 다른 인물 엔티티를 읽지 않고, 지문은 엔티티를 하나도 읽지 않는다")
    void subjectReadAndFingerprintStayBounded() {
        Statistics stats = statistics();
        stats.clear();
        reader.fingerprint(work.getId());
        assertThat(stats.getEntityLoadCount()).as("fingerprint는 scalar 조회만 한다").isZero();
        em.clear(); work = em.find(Work.class, work.getId());
        stats.clear();
        reader.subjects(work, "characters", List.of(characterRef(kangmin)));
        assertThat(stats.getEntityStatistics(WorkCharacter.class.getName()).getLoadCount()).as("요청한 인물만 읽는다").isEqualTo(1);
        assertThat(stats.getEntityStatistics(CharacterFact.class.getName()).getLoadCount()).as("강민의 Fact만 읽는다").isEqualTo(1);
    }

    @Test
    @DisplayName("지문은 쓰기가 없으면 같고, 대상별 읽기가 보지 않는 쓰기(검토 대기 후보, 보관 인물의 Fact)로는 바뀌지 않는다")
    void fingerprintIsStableWithoutReadAffectingWrites() {
        String before = reader.fingerprint(work.getId());
        assertThat(reader.fingerprint(work.getId())).isEqualTo(before);
        var pending = SettingCandidate.createCharacterDiscovery(work, prior70, null, job70, "가짜", "가짜", yunseo.getId(),
                SettingCandidateMatchStatus.MATCHED, JSON.arrayNode(), BigDecimal.ONE, null);
        em.persist(pending);
        WorkCharacter stored = em.find(WorkCharacter.class, archived.getId());
        em.persist(CharacterFact.createManual(stored, CharacterFactType.STAT, "힘", "열", JSON.objectNode().put("value", 10)));
        flushAndClear();
        assertThat(reader.fingerprint(work.getId())).isEqualTo(before);
    }

    // ---- 쓰기 종류마다: 대상별(또는 현재값) 읽기가 실제로 바뀌고 지문도 바뀐다 ----

    @Test @DisplayName("인물 이름만 바꾸는 updateBasicInfo(snapshotVersion 그대로)는 지문을 바꾼다")
    void renameChangesFingerprint() {
        assertChanges(() -> characterRead(yunseo), () -> {
            WorkCharacter character = em.find(WorkCharacter.class, yunseo.getId());
            long version = character.getSnapshotVersion();
            character.updateBasicInfo("윤서아", "주인공");
            assertThat(character.getSnapshotVersion()).isEqualTo(version);
        });
    }

    @Test @DisplayName("현재값 변경(snapshotVersion 증가)은 지문을 바꾼다")
    void snapshotChangeChangesFingerprint() {
        assertChanges(() -> characterRead(kangmin), () -> {
            WorkCharacter character = em.find(WorkCharacter.class, kangmin.getId());
            var entries = new LinkedHashMap<>(snapshots.read(character));
            var entry = snapshots.entry(CharacterFactType.PROFILE, "직업", "용병단장", JSON.objectNode().put("value", "용병단장"));
            entries.put(entry.slot(), entry);
            snapshots.replace(character, entries);
        });
    }

    @Test @DisplayName("현재값을 바꾸지 않는 이력 Fact 추가는 지문을 바꾼다")
    void historyOnlyFactChangesFingerprint() {
        assertChanges(() -> characterRead(kangmin), () -> {
            WorkCharacter character = em.find(WorkCharacter.class, kangmin.getId());
            long version = character.getSnapshotVersion();
            em.persist(CharacterFact.createManual(character, CharacterFactType.PROFILE, "직업", "견습 용병", JSON.objectNode().put("value", "견습 용병")));
            assertThat(character.getSnapshotVersion()).isEqualTo(version);
        });
    }

    @Test @DisplayName("새 별칭을 남기는 인물 발견 확정은 지문을 바꾼다")
    void aliasDiscoveryChangesFingerprint() {
        assertChanges(() -> characterRead(kangmin), () -> {
            work = em.find(Work.class, work.getId());
            prior70 = em.find(Episode.class, prior70.getId()); job70 = em.find(AnalysisJob.class, job70.getId());
            discovery("강 대장", em.find(WorkCharacter.class, kangmin.getId()));
        });
    }

    @Test @DisplayName("별칭이 같아도 식별 근거·출처를 바꾸는 인물 발견 확정은 지문을 바꾼다")
    void sameNameDiscoveryChangesFingerprint() {
        String aliasesBefore = characterRead(yunseo).path("aliases").toString();
        assertChanges(() -> characterRead(yunseo), () -> {
            work = em.find(Work.class, work.getId());
            prior70 = em.find(Episode.class, prior70.getId()); job70 = em.find(AnalysisJob.class, job70.getId());
            discovery("서야", em.find(WorkCharacter.class, yunseo.getId()));
        });
        assertThat(characterRead(yunseo).path("aliases").toString()).isEqualTo(aliasesBefore);
    }

    @Test @DisplayName("인물 보관·복구·생성은 지문을 바꾼다")
    void characterLifecycleChangesFingerprint() {
        assertChanges(() -> reader.currentSubjects(work).path("characters"), () -> em.find(WorkCharacter.class, kangmin.getId()).archive());
        assertChanges(() -> reader.currentSubjects(work).path("characters"), () -> em.find(WorkCharacter.class, kangmin.getId()).restore());
        assertChanges(() -> reader.currentSubjects(work).path("characters"),
                () -> em.persist(WorkCharacter.create(em.find(Work.class, work.getId()), "새 인물", null, null, null, null, null, null, null, null, null)));
    }

    @Test @DisplayName("세계관 속성 변경(version 증가)은 지문을 바꾼다")
    void worldPropertyChangesFingerprint() {
        assertChanges(() -> worldRead(capital), () -> em.find(WorldSetting.class, capital.getId()).addProperty("기후", "온화함"));
    }

    @Test @DisplayName("세계관 대상 생성·삭제는 지문을 바꾼다")
    void worldLifecycleChangesFingerprint() {
        assertChanges(() -> reader.currentSubjects(work).path("worldSettings"),
                () -> em.persist(WorldSetting.create(em.find(Work.class, work.getId()), WorldSettingCategory.LOCATION, "항구", "위치", "남쪽")));
        assertChanges(() -> reader.currentSubjects(work).path("worldSettings"), () -> em.remove(em.find(WorldSetting.class, capital.getId())));
    }

    @Test @DisplayName("version을 올리지 않는 세계관 확정(같은 값)은 출처가 바뀌므로 지문을 바꾼다")
    void sameValueWorldConfirmationChangesFingerprint() {
        assertChanges(() -> worldRead(elves), () -> {
            WorldSetting setting = em.find(WorldSetting.class, elves.getId());
            long version = setting.getVersion();
            work = em.find(Work.class, work.getId());
            worldCandidate(setting, em.find(Episode.class, prior100.getId()), em.find(AnalysisJob.class, job100.getId()), null, "서식지", "숲",
                    false, WorldSettingReviewStatus.CONFIRMED);
            assertThat(setting.getVersion()).isEqualTo(version);
        });
    }

    @Test @DisplayName("확정 세계관 후보의 이력 전용 전환은 지문을 바꾼다")
    void worldHistoryOnlyTransitionChangesFingerprint() {
        assertChanges(() -> worldRead(elves), () -> em.find(WorldSettingCandidate.class, elvesCurrent.getId()).markHistoryOnly());
    }

    JsonNode characterRead(WorkCharacter character) {
        return reader.subjects(em.find(Work.class, work.getId()), "characters", List.of(characterRef(character))).path(characterRef(character));
    }

    JsonNode worldRead(WorldSetting setting) {
        return reader.subjects(em.find(Work.class, work.getId()), "worldSettings", List.of(worldRef(setting))).path(worldRef(setting));
    }

    void flushAndClear() {
        em.flush(); em.clear(); work = em.find(Work.class, work.getId());
    }

    /** 쓰기 전후로 read가 실제로 바뀌고(이 쓰기가 대상별 읽기에 보인다) 지문도 바뀌는지 확인한다. */
    void assertChanges(Supplier<JsonNode> read, Runnable write) {
        flushAndClear();
        String fingerprint = reader.fingerprint(work.getId());
        String value = canonical(read.get());
        flushAndClear();
        write.run();
        flushAndClear();
        assertThat(canonical(read.get())).as("이 쓰기는 live 읽기 값을 바꿔야 한다").isNotEqualTo(value);
        assertThat(reader.fingerprint(work.getId())).as("live 읽기 값을 바꾸는 쓰기는 지문을 바꿔야 한다").isNotEqualTo(fingerprint);
    }
}
