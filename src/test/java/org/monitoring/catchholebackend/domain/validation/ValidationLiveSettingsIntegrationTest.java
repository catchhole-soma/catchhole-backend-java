package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisStateSource;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
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
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationCorrection;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationLiveSettings;
import org.monitoring.catchholebackend.domain.validation.service.ValidationBaselineService;
import org.monitoring.catchholebackend.domain.validation.type.ValidationPreparationStatus;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * #381 live 설정 읽기의 모델 동등성 기록. Worker 판정 모델이 실제로 읽는 SETTINGS 행(AI {@code projection.compact_setting_row} 뒤)이
 * 접수 때 복사하던 legacy seed의 행과 바이트 단위로 같고, 주체 번호(S·B)도 같음을 남긴다. AI 투영 규칙은 AI {@code origin/main:app/validation/projection.py}를 옮겼다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.jpa.properties.hibernate.generate_statistics=true"})
@ActiveProfiles("test")
@Transactional
@DisplayName("#381 검수 live 설정 읽기와 legacy 복사본의 모델 입력 동등성")
class ValidationLiveSettingsIntegrationTest {
    @Autowired EntityManager em;
    @Autowired ValidationLiveSettings live;
    @Autowired ValidationBaselineService baselines;
    @Autowired ValidationDataMapper mapper;
    @Autowired AnalysisStateJournal journal;
    @Autowired CharacterSnapshotAccessor snapshots;
    @Autowired List<AnalysisStateSource> stateSources;

    Member member; Work work; UploadBatch batch; Episode prior70; Episode prior100; Episode target;
    AnalysisJob job70; AnalysisJob job100;
    WorkCharacter yunseo; WorkCharacter kangmin; WorkCharacter archived;
    WorldSetting elves; WorldSetting capital;
    ValidationReport report;

    @BeforeEach
    void fixture() {
        member = Member.register(UUID.randomUUID() + "@test.invalid", "test-only", null, "합성 작가"); em.persist(member);
        work = Work.create(member, "live 설정", WorkGenre.FANTASY, "합성"); em.persist(work);
        batch = UploadBatch.create(work, member, UploadType.SINGLE_EPISODE, UploadSourceType.FILE); em.persist(batch);
        prior70 = Episode.create(work, null, 70, "수리", "synthetic/70.txt", "v1", "7".repeat(64), 12); em.persist(prior70);
        prior100 = Episode.create(work, null, 100, "이전", "synthetic/100.txt", "v1", "a".repeat(64), 12); em.persist(prior100);
        target = Episode.create(work, null, 101, "대상", "synthetic/101.txt", "v1", "b".repeat(64), 12); em.persist(target);
        job70 = AnalysisJob.create(work, null, prior70, AnalysisJobType.SETTING_EXTRACTION); em.persist(job70);
        job100 = AnalysisJob.create(work, null, prior100, AnalysisJobType.SETTING_EXTRACTION); em.persist(job100);

        // 윤서: 내부 envelope snapshot, 수동 보호 slot, 대표 서사 시점, 확정 인물 발견 별칭, 근거 Fact와 과거 이력 Fact.
        yunseo = WorkCharacter.create(work, "윤서", "주인공", null, null, null, null, null, null, null, null); em.persist(yunseo);
        var entries = new java.util.LinkedHashMap<CharacterSnapshotSlot, CharacterSnapshotEntry>();
        for (var entry : List.of(
                snapshots.entry(CharacterFactType.AGE, "age", "17", JsonNodeFactory.instance.objectNode().put("value", 17)),
                snapshots.entry(CharacterFactType.PROFILE, "직업", "검사", JsonNodeFactory.instance.objectNode().put("value", "검사")),
                snapshots.entry(CharacterFactType.STATUS, "부상", "왼팔 골절", JsonNodeFactory.instance.objectNode().put("value", "왼팔 골절").put("active", true))))
            entries.put(entry.slot(), entry);
        snapshots.replace(yunseo, entries);
        yunseo.protectManualSlot(CharacterFactType.PROFILE, "직업");
        var discovery = discovery("서야", yunseo);
        discovery(" 윤서 ", yunseo); discovery("서야 ", yunseo); discovery("윤이", yunseo);
        var pending = SettingCandidate.createCharacterDiscovery(work, prior70, null, job70, "가짜", "가짜", yunseo.getId(),
                SettingCandidateMatchStatus.MATCHED, JsonNodeFactory.instance.arrayNode(), BigDecimal.ONE, null);
        em.persist(pending);
        var time = JsonNodeFactory.instance.objectNode().put("description", "새벽 출정 직후");
        time.putArray("evidenceSpans").addObject().put("quote", "새벽").put("startOffset", 0).put("endOffset", 2).put("sourceChunkId", UUID.randomUUID().toString());
        time.put("sourceCandidateId", discovery.getId().toString());
        yunseo.updateCurrentNarrativeTime(time, true);
        var currentCandidate = settingCandidate(yunseo, "직업", "검사");
        var current = CharacterFact.create(yunseo, currentCandidate, CharacterFactType.PROFILE, "직업", "검사", "검사", null, prior70, null, job70, BigDecimal.ONE, 70);
        em.persist(current); em.persist(CharacterSnapshotSource.create(yunseo, CharacterFactType.PROFILE, "직업", current, 0));
        var past = settingCandidate(yunseo, "직업", "견습 검사");
        em.persist(CharacterFact.create(yunseo, past, CharacterFactType.PROFILE, "직업", "견습 검사", "견습 검사", null, prior70, null, job70, BigDecimal.ONE, 12));

        // 강민: envelope 전 raw snapshot. 표시값은 slot 마지막 근거 Fact(후보 출처·수동 Fact)에서, 근거 없는 slot은 raw 값에서 온다.
        var profile = JsonNodeFactory.instance.objectNode();
        profile.putObject("직업").put("value", "용병");
        profile.put("고향", "북부");
        var stats = JsonNodeFactory.instance.objectNode(); stats.putObject("힘").put("value", 12);
        var skills = JsonNodeFactory.instance.objectNode(); skills.putObject("검술").put("name", "검술");
        var statuses = JsonNodeFactory.instance.objectNode(); statuses.putObject("부상").put("value", "없음");
        kangmin = WorkCharacter.create(work, "강민", "용병", 20, 5, profile, stats, skills, null, statuses, null); em.persist(kangmin);
        discovery("민이", kangmin);
        var first = CharacterFact.create(kangmin, settingCandidate(kangmin, "직업", "용병"), CharacterFactType.PROFILE, "직업", "용병", "용병", null, prior70, null, job70, BigDecimal.ONE, 70);
        var last = CharacterFact.create(kangmin, settingCandidate(kangmin, "직업", "용병단장"), CharacterFactType.PROFILE, "직업", "용병단장", "용병단장", null, prior70, null, job70, BigDecimal.ONE, 70);
        var manual = CharacterFact.createManual(kangmin, CharacterFactType.STAT, "힘", "열둘", JsonNodeFactory.instance.objectNode().put("value", 12));
        em.persist(first); em.persist(last); em.persist(manual);
        em.persist(CharacterSnapshotSource.create(kangmin, CharacterFactType.PROFILE, "직업", first, 0));
        em.persist(CharacterSnapshotSource.create(kangmin, CharacterFactType.PROFILE, "직업", last, 1));
        em.persist(CharacterSnapshotSource.create(kangmin, CharacterFactType.STAT, "힘", manual, 0));

        archived = WorkCharacter.create(work, "보관 인물", null, null, null, null, null, null, null, null, null); em.persist(archived);
        archived.archive();

        // 엘프: root·scope 설정, 수동 보호 경로, 확정 현재 근거(70화)와 더 늦은 이력 전용·검토 대기 후보. 수도: 후보 없음.
        elves = WorldSetting.create(work, WorldSettingCategory.RACE, "엘프", "서식지", "숲"); em.persist(elves);
        elves.addProperty("외형", "귀", "뾰족함");
        elves.protectManualProperty(null, "서식지");
        worldCandidate(elves, prior70, job70, null, "서식지", "숲", false, WorldSettingReviewStatus.CONFIRMED);
        worldCandidate(elves, prior100, job100, "외형", "귀", "뾰족함", true, WorldSettingReviewStatus.CONFIRMED);
        worldCandidate(elves, prior100, job100, null, "수명", "천 년", false, WorldSettingReviewStatus.PENDING_REVIEW);
        capital = WorldSetting.create(work, WorldSettingCategory.LOCATION, "수도", "위치", "대륙 중앙"); em.persist(capital);
        // 대상이 없는 확정 이력 행: legacy seed에서는 worldHistory 전용 주체가 되었고 SETTINGS로 나간 적이 없다. live 행에는 나오지 않는다.
        worldCandidate(null, prior70, job70, null, "소멸", "옛 왕국", false, WorldSettingReviewStatus.CONFIRMED);

        report = ValidationReport.create(work, target, batch, "c".repeat(64)); em.persist(report);
        var scope = JsonNodeFactory.instance.objectNode().put("domain", "characters").put("targetId", yunseo.getId().toString())
                .put("factType", "PROFILE").put("factKey", "직업").put("beforeValue", "검객").put("afterValue", "검사");
        scope.putArray("priorCorrections").addObject().put("correctionId", "prior-correction").put("domain", "characters")
                .put("targetId", yunseo.getId().toString());
        em.persist(ValidationCorrection.create(report, "correction-1", "d".repeat(64), "B2", "e".repeat(64), 1, "f".repeat(64), 1, scope));
        em.flush();
        // legacy 조회(createdAt desc)에는 동률 기준이 없으므로 생성 시각을 분리한다. 강민이 더 최근이다.
        setCreatedAt(yunseo, LocalDateTime.of(2026, 1, 1, 9, 0));
        setCreatedAt(kangmin, LocalDateTime.of(2026, 1, 1, 10, 0));
        setCreatedAt(archived, LocalDateTime.of(2026, 1, 1, 11, 0));
        em.clear();
    }

    SettingCandidate discovery(String name, WorkCharacter owner) {
        var candidate = SettingCandidate.createCharacterDiscovery(work, prior70, null, job70, name, name, owner.getId(),
                SettingCandidateMatchStatus.MATCHED, JsonNodeFactory.instance.arrayNode(), BigDecimal.ONE, null);
        em.persist(candidate); ReflectionTestUtils.setField(candidate, "reviewStatus", SettingCandidateReviewStatus.CONFIRMED);
        return candidate;
    }

    SettingCandidate settingCandidate(WorkCharacter owner, String attribute, String value) {
        var candidate = SettingCandidate.create(work, prior70, null, job70, SettingEntityType.CHARACTER, owner.getName(), owner.getName(), owner.getId(),
                SettingCandidateMatchStatus.MATCHED, attribute, value, SettingValueType.STRING, null, JsonNodeFactory.instance.arrayNode(), BigDecimal.ONE, null);
        em.persist(candidate); ReflectionTestUtils.setField(candidate, "reviewStatus", SettingCandidateReviewStatus.CONFIRMED);
        return candidate;
    }

    void worldCandidate(WorldSetting setting, Episode episode, AnalysisJob job, String scope, String name, String value,
            boolean historyOnly, WorldSettingReviewStatus status) {
        var category = setting == null ? WorldSettingCategory.WORLD_RULE_HISTORY : setting.getCategory();
        var subject = setting == null ? "옛 왕국" : setting.getSubjectName();
        var candidate = WorldSettingCandidate.create(work, episode, job, category, subject, name, value,
                JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("quote", value)), BigDecimal.ONE, null);
        ReflectionTestUtils.setField(candidate, "targetWorldSetting", setting);
        ReflectionTestUtils.setField(candidate, "reviewStatus", status);
        ReflectionTestUtils.setField(candidate, "finalCategory", category);
        ReflectionTestUtils.setField(candidate, "finalSubjectName", subject);
        ReflectionTestUtils.setField(candidate, "finalScopeName", scope);
        ReflectionTestUtils.setField(candidate, "finalSettingName", name);
        ReflectionTestUtils.setField(candidate, "finalValue", value);
        ReflectionTestUtils.setField(candidate, "finalOperation", WorldSettingOperation.ADD);
        if (historyOnly) candidate.markHistoryOnly();
        em.persist(candidate);
    }

    void setCreatedAt(WorkCharacter character, LocalDateTime at) {
        em.createNativeQuery("update characters set created_at = :at where id = :id").setParameter("at", at).setParameter("id", character.getId()).executeUpdate();
    }

    Statistics statistics() {
        return em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    long loads(Class<?> type) {
        return statistics().getEntityStatistics(type.getName()).getLoadCount();
    }

    List<String> allSubjectRefs(ValidationLiveSettings.Index index) {
        return index.subjects().stream().map(ValidationLiveSettings.Subject::subjectRef).toList();
    }

    /** SETTINGS 응답 행(toValidationCurrentSettings)을 판정 모델이 읽는 compact 행 문자열로 바꾼다. 문자열 비교라 key 순서까지 같아야 한다. */
    List<String> modelRows(JsonNode entries, Map<String, ObjectNode> sources, List<String> subjectRefs) {
        return mapper.toValidationCurrentSettings(entries, sources, subjectRefs).stream().map(row -> compactSettingRow(row).toString()).toList();
    }

    @Test
    @DisplayName("live SETTINGS 행은 기존 확정 상태 capture로 만든 legacy 기준 항목과 판정 모델 입력이 바이트 단위로 같고 S·B 번호도 같다")
    void liveSettingsMatchLegacySeedAfterModelProjection() {
        work = em.find(Work.class, work.getId());
        report = em.find(ValidationReport.class, report.getId());
        // legacy: 접수가 쓰던 같은 AnalysisStateSource capture(인물·세계관)와 작가 정정을 seed로 저장하고 실제 normalize(materialize)로 기준 항목을 만든다.
        var seed = JsonNodeFactory.instance.objectNode().put("formatVersion", 1).put("workId", work.getId().toString()).put("memberId", member.getId());
        seed.putObject("target").put("episodeId", target.getId().toString()).put("episodeNo", 101).put("contentRevision", report.getSourceContentRevision())
                .put("contentHash", report.getSourceContentHash()).put("contentS3Key", report.getSourceContentS3Key()).put("contentS3Version", report.getSourceContentS3Version());
        for (var source : stateSources) if (ValidationDataMapper.CURRENT_DOMAINS.contains(source.domain())) seed.set(source.domain(), source.capture(work));
        seed.putArray("worldHistory");
        seed.set("explicitAuthorCorrections", live.correctionScopes(work.getId()));
        seed.putArray("sources");
        report.captureSeed(seed, journal.hash(seed), journal.hash(seed));
        em.flush();
        baselines.materialize(report.getId());
        report = em.find(ValidationReport.class, report.getId());
        assertThat(report.getBaselineStatus()).as(report.getHoldReason()).isEqualTo(ValidationPreparationStatus.READY);
        var legacyEntries = report.getBaselineEntries();
        var sources = mapper.toSourceReferences(report);
        em.flush(); em.clear();

        var index = live.index(work.getId());
        var liveEntries = live.entries(index, allSubjectRefs(index));

        assertParity(legacyEntries, liveEntries, sources, index);
        // SUBJECTS(색인만 읽는 행)도 순서·이름·별칭까지 같다.
        assertThat(mapper.toValidationSubjectCatalogue(live.entries(index, Set.of()), sources).toString())
                .isEqualTo(mapper.toValidationSubjectCatalogue(legacyEntries, sources).toString());
    }

    void assertParity(JsonNode legacyEntries, JsonNode liveEntries, Map<String, ObjectNode> sources, ValidationLiveSettings.Index index) {
        var refs = allSubjectRefs(index);
        var legacy = modelRows(legacyEntries, sources, refs);
        var actual = modelRows(liveEntries, sources, refs);
        assertThat(actual).isEqualTo(legacy);
        // 두 쪽이 모두 빈 값이라 같은 것이 아님을 확인한다: 순서(강민 → 윤서 → 세계관 id 순)와 실제 모델 값.
        assertThat(index.subjects()).extracting(ValidationLiveSettings.Subject::name).startsWith("강민", "윤서").hasSize(4);
        assertThat(index.find("S1").orElseThrow().entryRef()).isEqualTo("B1");
        String all = String.join("\n", actual);
        assertThat(all).contains("\"entry_ref\":\"B1\",\"subject_ref\":\"S1\"", "용병단장", "열둘", "북부", "\"factValue\":\"검술\"", "\"factValue\":\"없음\"",
                "\"aliases\":[\"서야\",\"윤이\"]", "\"aliases\":[\"민이\"]", "새벽 출정 직후", "\"protectedSlots\":[{\"factType\":\"PROFILE\",\"factKey\":\"직업\"}]",
                "\"외형\":{\"귀\":\"뾰족함\"}", "\"latestSourceEpisodeNo\":70", "\"latestSourceEpisodeNo\":null", "\"protectedPaths\":[{\"scopeName\":null,\"settingName\":\"서식지\"}]");
        assertThat(all).doesNotContain("가짜", "보관 인물", "옛 왕국", "견습 검사");
        // 주체 번호: legacy 기준 항목 앞쪽의 대상 순서가 live 색인과 같다(B_n == S_n).
        var legacyRefs = new ArrayList<String>();
        legacyEntries.forEach(row -> { if (ValidationDataMapper.CURRENT_DOMAINS.contains(row.path("domain").asText())) legacyRefs.add(row.path("subjectRef").asText()); });
        assertThat(legacyRefs).isEqualTo(index.subjects().stream().map(ValidationLiveSettings.Subject::stateRef).toList());
        assertThat(ValidationDataMapper.toSubjectRefs(liveEntries).entrySet()).allMatch(entry -> entry.getKey().substring(1).equals(entry.getValue().substring(1)));
        // SETTINGS의 authorCorrectionProofs 원천(state.explicitAuthorCorrections)도 직렬화 값이 같다(JSONB 왕복은 숫자 노드 종류만 바꾼다).
        for (int i = 0; i < index.subjects().size(); i++)
            assertThat(liveEntries.get(i).path("state").path("explicitAuthorCorrections").toString())
                    .isEqualTo(legacyEntries.get(i).path("state").path("explicitAuthorCorrections").toString());
        var proofs = liveEntries.get(1).path("state").path("explicitAuthorCorrections");
        assertThat(proofs).hasSize(2);
        assertThat(proofs.findValuesAsText("correctionId")).contains("prior-correction");
    }

    @Test
    @DisplayName("live 읽기는 후보·Fact·Job·리포트 엔티티를 읽지 않고 요청한 주체만 id로 읽으며 조회 수가 작품 크기와 무관하다")
    void liveReadsUseScalarQueriesAndLoadOnlyRequestedSubjects() {
        var stats = statistics();
        stats.clear();
        var index = live.index(work.getId());
        var subjectsOnly = live.entries(index, Set.of());
        assertThat(stats.getEntityLoadCount()).isZero();
        assertThat(stats.getPrepareStatementCount()).isEqualTo(3);
        assertThat(subjectsOnly.get(0).path("state").toString()).isEqualTo("{\"name\":\"강민\",\"aliases\":[\"민이\"]}");

        em.clear(); stats.clear();
        var full = live.entries(index, allSubjectRefs(index));
        assertThat(loads(WorkCharacter.class)).isEqualTo(2);
        assertThat(loads(WorldSetting.class)).isEqualTo(2);
        for (Class<?> forbidden : List.of(SettingCandidate.class, WorldSettingCandidate.class, CharacterFact.class, CharacterSnapshotSource.class,
                AnalysisJob.class, ValidationReport.class, Episode.class, Work.class))
            assertThat(loads(forbidden)).as(forbidden.getSimpleName()).isZero();
        // 인물 id 조회 1 + raw snapshot 근거 표시값 1 + 세계관 id 조회 1 + 세계관 최신 출처 회차 1 + 작가 정정 1
        assertThat(stats.getPrepareStatementCount()).isEqualTo(5);
        assertThat(full).hasSize(4);

        em.clear(); stats.clear();
        live.entries(index, List.of("S2"));
        assertThat(loads(WorkCharacter.class)).isEqualTo(1);
        assertThat(loads(WorldSetting.class)).isZero();
        // envelope snapshot뿐이라 근거 표시값 조회가 없다: 인물 id 조회 1 + 작가 정정 1
        assertThat(stats.getPrepareStatementCount()).isEqualTo(2);

        em.clear(); stats.clear();
        String fingerprint = live.fingerprint(work.getId());
        assertThat(stats.getEntityLoadCount()).isZero();
        assertThat(stats.getPrepareStatementCount()).isEqualTo(2);
        assertThat(fingerprint).matches("[0-9a-f]{64}").isEqualTo(live.fingerprint(index));
    }

    @Test
    @DisplayName("fingerprint는 인물 snapshotVersion·세계관 version·ACTIVE 인물 집합이 바뀔 때만 달라진다")
    void fingerprintFollowsSettingVersionsAndActiveSubjects() {
        String before = live.fingerprint(work.getId());
        assertThat(live.fingerprint(work.getId())).isEqualTo(before);

        var setting = em.find(WorldSetting.class, elves.getId());
        setting.addProperty("수명", "천 년"); em.flush();
        String afterWorld = live.fingerprint(work.getId());
        assertThat(afterWorld).isNotEqualTo(before);

        var character = em.find(WorkCharacter.class, kangmin.getId());
        character.updateCurrentNarrativeTime(JsonNodeFactory.instance.objectNode().put("description", "정오"), true); em.flush();
        String afterCharacter = live.fingerprint(work.getId());
        assertThat(afterCharacter).isNotEqualTo(afterWorld);

        em.find(WorkCharacter.class, archived.getId()).restore(); em.flush();
        assertThat(live.fingerprint(work.getId())).isNotEqualTo(afterCharacter);
    }

    @Test
    @DisplayName("색인 뒤 주체가 보관되면 전체 상태 읽기는 입력 변경 409로 멈춘다")
    void entriesRejectSubjectArchivedAfterIndex() {
        var index = live.index(work.getId());
        em.find(WorkCharacter.class, kangmin.getId()).archive(); em.flush();

        assertThatThrownBy(() -> live.entries(index, List.of("S1")))
                .isInstanceOf(AppException.class).hasFieldOrPropertyWithValue("resultCode", ValidationErrorCode.VALIDATION_SOURCE_VERSION_CONFLICT);
    }

    // ---- AI origin/main:app/validation/projection.py compact_setting_row / compact_state 이식 ----
    static final Set<String> DROPPED_STATE_KEYS = Set.of("historyFacts", "supportingSources", "supportingSource", "provenance", "provenanceByPath",
            "identityEvidence", "removedSnapshotEntries", "valueJson", "evidenceSpans", "comparison", "explicitAuthorCorrections",
            "snapshotVersion", "currentSupportingEvidenceVersion", "version");
    static final Set<String> KEPT_STATE_STRUCTURES = Set.of("currentNarrativeTime", "narrativeTime", "absences", "protectedSlots", "protectedPaths");
    static final Set<String> VERBATIM_STATE_KEYS = Set.of("propertiesJson");
    static final List<String> SLOT_FIELDS = List.of("factKey", "factType", "factValue", "temporalScope", "effectiveFromEpisodeNo");
    static final List<String> ROW_FIELDS = List.of("entry_ref", "subject_ref", "domain", "name", "aliases");

    static boolean scalar(JsonNode value) {
        return value.isNull() || value.isTextual() || value.isNumber() || value.isBoolean();
    }

    static JsonNode withoutEvidence(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            value.properties().forEach(entry -> { if (!DROPPED_STATE_KEYS.contains(entry.getKey())) result.set(entry.getKey(), withoutEvidence(entry.getValue())); });
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> result.add(withoutEvidence(item)));
            return result;
        }
        return value.deepCopy();
    }

    static JsonNode compactState(JsonNode state) {
        if (state == null || state.isMissingNode()) return NullNode.instance;
        if (!state.isObject()) return state.deepCopy();
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        state.properties().forEach(entry -> {
            String key = entry.getKey(); JsonNode value = entry.getValue();
            if (DROPPED_STATE_KEYS.contains(key)) return;
            if (key.equals("slots") && value.isObject()) {
                ObjectNode slots = result.putObject(key);
                value.properties().forEach(slot -> {
                    if (!slot.getValue().isObject()) return;
                    ObjectNode compact = slots.putObject(slot.getKey());
                    for (String name : SLOT_FIELDS) if (slot.getValue().has(name) && scalar(slot.getValue().get(name))) compact.set(name, slot.getValue().get(name).deepCopy());
                });
            } else if (VERBATIM_STATE_KEYS.contains(key) || scalar(value)
                    || value.isArray() && StreamSupport.stream(value.spliterator(), false).allMatch(ValidationLiveSettingsIntegrationTest::scalar)) {
                result.set(key, value.deepCopy());
            } else if (KEPT_STATE_STRUCTURES.contains(key)) {
                result.set(key, withoutEvidence(value));
            }
        });
        return result;
    }

    static ObjectNode compactSettingRow(JsonNode row) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (String key : ROW_FIELDS) if (row.has(key)) result.set(key, row.get(key).deepCopy());
        result.set("state", compactState(row.get("state")));
        if (row.has("author_correction")) result.set("author_correction", row.get("author_correction").deepCopy());
        return result;
    }
}
