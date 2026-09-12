package org.monitoring.catchholebackend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisHumanRejectionPolicy;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("같은 원문 발견을 반려한 뒤 새 누적 실행의 임시 인물 경계")
class CharacterDiscoveryRejectionTest {
    private final SettingCandidateRepository candidates = mock(SettingCandidateRepository.class);
    private final WorldSettingCandidateRepository worlds = mock(WorldSettingCandidateRepository.class);
    private final AnalysisStateJournal journal = new AnalysisStateJournal();
    private final AnalysisHumanRejectionPolicy policy = new AnalysisHumanRejectionPolicy(candidates, worlds, journal);
    private final AnalysisRunStateService states = mock(AnalysisRunStateService.class);
    private final AtomicReference<JsonNode> projected = new AtomicReference<>();
    private final CharacterAnalysisStateService service = new CharacterAnalysisStateService(
            states, candidates, new CharacterAnalysisStateMapper(), policy);
    private Work work;
    private Episode episode;
    private AnalysisJob priorJob;
    private AnalysisJob job;

    @BeforeEach
    void setUp() {
        work = Work.create(Member.register("discovery-rejection@example.com", "pass", "01012345678", "작가"),
                "발견 반려 작품", WorkGenre.FANTASY, "설명");
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        episode = Episode.create(work, null, 2, "2화", "source", "version", "b".repeat(64), 100);
        ReflectionTestUtils.setField(episode, "id", UUID.randomUUID());
        priorJob = job();
        job = job();
        when(states.getProjectedState(job)).thenAnswer(call -> projected.get().deepCopy());
        doAnswer(call -> {
            List<AnalysisStateChange> changes = call.getArgument(2);
            projected.set(journal.apply(projected.get(), changes));
            return null;
        }).when(states).appendValidatedChanges(eq(job), any(), any());
    }

    @Test
    @DisplayName("반려 발견과 직접 연결 설정은 참고만 남고 다른 근거의 동명이인은 정상 준비한다")
    void rejectedDiscoveryDoesNotRegisterIdentityOrRedirectItsSetting() {
        SettingCandidate rejected = discovery(priorJob, "유진", "유진", 2);
        rejected.recordUserModification();
        rejected.dismiss();
        capture(rejected);
        SettingCandidate repeated = discovery(job, "유진", "유진", 2);
        SettingCandidate dependent = setting(repeated);
        SettingCandidate namesake = discovery(job, "유진", "유진", 30);
        SettingCandidate namesakeSetting = setting(namesake);
        List<SettingCandidate> current = List.of(dependent, repeated, namesake, namesakeSetting);
        when(candidates.findAllByAnalysisJobIdAndProvisionalSubjectKeyIsNotNull(job.getId())).thenReturn(current);
        when(candidates.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(job.getId())).thenReturn(current);
        JsonNode rawDiscovery = repeated.getRawAiResultJson().deepCopy();
        JsonNode rawSetting = dependent.getRawAiResultJson().deepCopy();

        service.prepareProvisionalCandidates(job);
        String firstProjection = projected.get().toString();
        service.prepareProvisionalCandidates(job);

        assertThat(projected.get().toString()).isEqualTo(firstProjection);
        assertThat(projected.get().path("characters").has(repeated.getProvisionalSubjectKey())).isFalse();
        assertThat(projected.get().path("characters").has(namesake.getProvisionalSubjectKey())).isTrue();
        assertThat(repeated.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.NOT_REQUIRED);
        assertThat(repeated.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.AMBIGUOUS);
        assertThat(dependent.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.WAITING_FOR_CHARACTER_MATCH);
        assertThat(dependent.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.AMBIGUOUS);
        assertThat(dependent.getMatchedCharacterId()).isNull();
        assertThat(dependent.getProvisionalSubjectKey()).isEqualTo(repeated.getProvisionalSubjectKey());
        assertThat(dependent.getAttributeValue()).isEqualTo("북부 출신");
        assertThat(dependent.getRawAiResultJson()).isEqualTo(rawSetting);
        assertThat(repeated.getRawAiResultJson()).isEqualTo(rawDiscovery);
        assertThat(repeated.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
        assertThat(repeated.isUserModified()).isFalse();
        assertThat(namesakeSetting.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PENDING);
        JsonNode reference = projected.get().path("references")
                .path("character-rejected-discovery-reference:" + dependent.getId());
        assertThat(reference.path("operation").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(reference.path("sourceCandidateIds").get(0).asText()).isEqualTo(dependent.getId().toString());
        assertThat(projected.get().path("references").path("human-rejection:" + repeated.getId())
                .path("rejectedCandidateId").asText()).isEqualTo(rejected.getId().toString());
    }

    @Test
    @DisplayName("새 후보 UUID는 동일 주장으로 보지만 이름 원본 근거 회차 원문이 바뀌면 반려를 확장하지 않는다")
    void exactDiscoveryFingerprintExcludesTransportIdsAndRejectsDifferentClaims() {
        SettingCandidate rejected = discovery(priorJob, "유진", "유진", 2);
        rejected.recordUserModification();
        rejected.dismiss();
        capture(rejected);
        SettingCandidate repeated = discovery(job, "유진", "유진", 2);
        assertThat(repeated.getId()).isNotEqualTo(rejected.getId());
        assertThat(policy.match(repeated, projected.get())).isPresent();
        assertThat(policy.match(discovery(job, "유진", "유진", 30), projected.get())).isEmpty();
        assertThat(policy.match(discovery(job, "서유진", "유진", 2), projected.get())).isEmpty();
        assertThat(policy.match(discovery(job, "유진", "북부의 유진", 2), projected.get())).isEmpty();
        ((ObjectNode) repeated.getRawAiResultJson()).put("entity_name", "원문 발견의 다른 이름");
        assertThat(policy.match(repeated, projected.get())).isEmpty();
        ReflectionTestUtils.setField(job, "sourceEpisodeNo", 3);
        assertThat(policy.match(discovery(job, "유진", "유진", 2), projected.get())).isEmpty();
        ReflectionTestUtils.setField(job, "sourceEpisodeNo", 2);
        ReflectionTestUtils.setField(job, "sourceContentHash", "c".repeat(64));
        assertThat(policy.match(discovery(job, "유진", "유진", 2), projected.get())).isEmpty();
        assertThat(projected.get().path("references").toString()).doesNotContain("유진", "quote", "evidence");
    }

    @Test
    @DisplayName("사용자 반려와 검증 가능한 원본이 없으면 발견 차단 정책을 만들지 않는다")
    void missingOriginalAndNonHumanDismissalAreNotInherited() {
        SettingCandidate automatic = discovery(priorJob, "유진", "유진", 2);
        automatic.dismiss();
        capture(automatic);
        assertThat(projected.get().path("references")).isEmpty();
        SettingCandidate missingRaw = discovery(priorJob, "유진", "유진", 2);
        missingRaw.recordUserModification();
        missingRaw.dismiss();
        ReflectionTestUtils.setField(missingRaw, "rawAiResultJson", null);
        capture(missingRaw);
        assertThat(projected.get().path("references")).isEmpty();
    }

    @Test
    @DisplayName("발견 반려 전이는 확정 실제 대상이나 완료된 비교 결과를 덮지 않는다")
    void rejectionHoldRejectsUnsafeStateTransitions() {
        SettingCandidate anchor = discovery(job, "유진", "유진", 2);
        SettingCandidate candidate = setting(anchor);
        ReflectionTestUtils.setField(candidate, "comparisonStatus", CharacterFactComparisonStatus.COMPLETED);
        assertThatThrownBy(() -> candidate.holdForRejectedDiscoveryAnchor(JsonNodeFactory.instance.objectNode()))
                .isInstanceOf(AppException.class);
        ReflectionTestUtils.setField(candidate, "comparisonStatus", CharacterFactComparisonStatus.PENDING);
        ReflectionTestUtils.setField(candidate, "matchedCharacterId", UUID.randomUUID());
        assertThatThrownBy(() -> candidate.holdForRejectedDiscoveryAnchor(JsonNodeFactory.instance.objectNode()))
                .isInstanceOf(AppException.class);
    }

    private void capture(SettingCandidate rejected) {
        when(candidates.findAllByWorkIdAndReviewStatusOrderByCreatedAtDesc(
                work.getId(), SettingCandidateReviewStatus.DISMISSED)).thenReturn(List.of(rejected));
        ObjectNode initial = journal.emptyState();
        initial.set("references", policy.capture(work));
        projected.set(initial);
    }

    private AnalysisJob job() {
        AnalysisJob result = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(result, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(result, "analysisMode", AnalysisMode.ORDERED_PROVISIONAL);
        ReflectionTestUtils.setField(result, "sourceEpisodeNo", 2);
        ReflectionTestUtils.setField(result, "sourceContentHash", "b".repeat(64));
        ReflectionTestUtils.setField(result, "inputStateHash", "a".repeat(64));
        return result;
    }

    private SettingCandidate discovery(AnalysisJob source, String name, String mention, int offset) {
        ObjectNode raw = JsonNodeFactory.instance.objectNode().put("candidate_kind", "CHARACTER_DISCOVERY")
                .put("entity_type", "CHARACTER").put("entity_name", name).put("raw_entity_mention", mention)
                .put("source_chunk_id", UUID.randomUUID().toString());
        SettingCandidate result = SettingCandidate.createCharacterDiscovery(work, episode, UUID.randomUUID(), source,
                name, mention, null, SettingCandidateMatchStatus.MATCHED, evidence(offset), BigDecimal.ONE, raw);
        ReflectionTestUtils.setField(result, "id", UUID.randomUUID());
        String key = "provisional-character:" + result.getId();
        ReflectionTestUtils.setField(result, "provisionalSubjectKey", key);
        raw.putObject("orderedSubjectResolution").put("provisionalSubjectKey", key);
        when(candidates.findByIdAndWorkId(result.getId(), work.getId())).thenReturn(Optional.of(result));
        return result;
    }

    private SettingCandidate setting(SettingCandidate anchor) {
        SettingCandidate result = SettingCandidate.create(work, episode, UUID.randomUUID(), job,
                SettingEntityType.CHARACTER, anchor.getEntityName(), "유진", null, SettingCandidateMatchStatus.MATCHED,
                "profile.origin", "북부 출신", SettingValueType.STRING, null, evidence(50), BigDecimal.ONE,
                JsonNodeFactory.instance.objectNode().put("candidate_kind", "SETTING"));
        ReflectionTestUtils.setField(result, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(result, "provisionalSubjectKey", anchor.getProvisionalSubjectKey());
        return result;
    }

    private JsonNode evidence(int offset) {
        return JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode()
                .put("quote", "유진이 모습을 드러냈다").put("startOffset", offset).put("endOffset", offset + 12));
    }
}
