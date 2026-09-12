package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonReviewReason;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;

@DisplayName("이전 업로드 묶음의 최신 미확정 참고 수집")
class AnalysisPendingReferenceSourceTest {
    private final SettingCandidateRepository characters = mock(SettingCandidateRepository.class);
    private final WorldSettingCandidateRepository worlds = mock(WorldSettingCandidateRepository.class);
    private final AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
    private final AnalysisPendingReferenceSource source = new AnalysisPendingReferenceSource(characters, worlds, jobs);
    private final Work work = mock(Work.class);
    private AnalysisJob current;

    @BeforeEach
    void prepareWork() {
        when(work.getId()).thenReturn(UUID.randomUUID());
        current = job(7);
    }

    @Test
    @DisplayName("같은 실행과 다른 실행의 앞 회차 대기 후보를 한 번씩 참고로 수집한다")
    void capturesPendingAcrossRunsWithoutPromotingFacts() {
        var older = job(2);
        var preceding = job(6);
        var first = character(older);
        var second = character(preceding);
        var confirmed = world(older);
        when(confirmed.getReviewStatus()).thenReturn(WorldSettingReviewStatus.CONFIRMED);
        earlier(preceding, older, older);
        var result = source.capture(current);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.path("pending-character:" + first.getId()).path("sourceEpisodeNo").asInt()).isEqualTo(2);
        assertThat(result.path("pending-character:" + second.getId()).path("sourceEpisodeNo").asInt()).isEqualTo(6);
        result.forEach(value -> assertThat(value.path("confirmationStatus").asText()).isEqualTo("UNCONFIRMED"));
    }

    @Test
    @DisplayName("일반 검토는 다음 회차에서도 원본 대상과 불확실한 내용을 미확정 참고로 유지한다")
    void generalUncertaintyKeepsOriginalReferenceAcrossEpisodes() {
        var older = job(2);
        var review = world(older);
        when(review.getComparisonReviewReason()).thenReturn(WorldSettingComparisonReviewReason.GENERAL_UNCERTAINTY);
        when(review.getComparisonReason()).thenReturn("정확한 연결을 아직 결정하지 못했습니다.");
        earlier(older);
        var reference = source.capture(current).path("pending-world:" + review.getId());
        assertThat(reference.path("subjectName").asText()).isEqualTo(review.getSubjectName());
        assertThat(reference.path("settingName").asText()).isEqualTo(review.getSettingName());
        assertThat(reference.path("value").asText()).isEqualTo(review.getExtractedValue());
        assertThat(reference.path("comparisonReviewReason").asText()).isEqualTo("GENERAL_UNCERTAINTY");
        assertThat(reference.path("confirmationStatus").asText()).isEqualTo("UNCONFIRMED");
        assertThat(reference.has("targetRef")).isFalse();
        assertThat(reference.has("matchedPropertyName")).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "1층")
    @DisplayName("정상 범위 검토는 원본 값과 두 경로를 보존하고 모델의 판단 문장은 복사하지 않는다")
    void scopeReviewRetainsBothPathsAndEvidence(String matchedScope) {
        var earlier = job(2);
        var review = world(earlier);
        when(review.getComparisonReviewReason()).thenReturn(WorldSettingComparisonReviewReason.SCOPE_MISMATCH);
        when(review.getMatchedScopeName()).thenReturn(matchedScope);
        when(review.getMatchedPropertyName()).thenReturn("광원");
        when(review.getComparisonReason()).thenReturn("개발자 표현이 포함된 이전 판단");
        earlier(earlier);
        var reference = source.capture(current).path("pending-world:" + review.getId());
        assertThat(reference.path("scopeName").asText()).isEqualTo("외부");
        assertThat(reference.path("settingName").asText()).isEqualTo("조명");
        assertThat(reference.path("value").asText()).isEqualTo("바깥의 불빛");
        assertThat(reference.path("matchedScopeName").isNull()).isEqualTo(matchedScope == null);
        if (matchedScope != null) assertThat(reference.path("matchedScopeName").asText()).isEqualTo(matchedScope);
        assertThat(reference.path("matchedPropertyName").asText()).isEqualTo("광원");
        assertThat(reference.path("reason").asText()).isEqualTo("원문과 기존 설정의 적용 범위가 달라 확인이 필요한 미확정 참고 정보입니다.");
        assertThat(reference.path("evidenceSpans")).isEqualTo(review.getEvidenceSpans()).isNotSameAs(review.getEvidenceSpans());
    }

    @Test
    @DisplayName("범위 미정 검토는 검증된 보류 종류를 자연어로 안내하고 모델 문장을 재사용하지 않는다")
    void unresolvedScopeKeepsSafeReviewExplanation() {
        var earlier = job(2);
        var review = world(earlier);
        when(review.getScopeName()).thenReturn(null);
        when(review.getComparisonReviewReason()).thenReturn(WorldSettingComparisonReviewReason.SCOPE_UNRESOLVED);
        when(review.getMatchedScopeName()).thenReturn("1층");
        when(review.getMatchedPropertyName()).thenReturn("광원");
        when(review.getComparisonReason()).thenReturn("root scope이므로 판단 보류");
        earlier(earlier);
        var reference = source.capture(current).path("pending-world:" + review.getId());
        assertThat(reference.path("reason").asText())
                .isEqualTo("원문에서 적용 범위를 알 수 없어 기존 설정과 같은 범위인지 확인이 필요한 미확정 참고 정보입니다.");
        assertThat(reference.path("scopeName").isNull()).isTrue();
        assertThat(reference.path("matchedScopeName").asText()).isEqualTo("1층");
    }

    @Test
    @DisplayName("비교 실패는 원래 추출값과 근거만 전달하고 실패한 비교 경로와 제안값은 전달하지 않는다")
    void failedComparisonDoesNotBecomeReferenceAuthority() {
        var earlier = job(2);
        var review = world(earlier);
        when(review.getComparisonStatus()).thenReturn(WorldSettingComparisonStatus.FAILED);
        when(review.getComparisonReviewReason()).thenReturn(WorldSettingComparisonReviewReason.SCOPE_MISMATCH);
        when(review.getMatchedScopeName()).thenReturn("없는 범위");
        when(review.getMatchedPropertyName()).thenReturn("없는 속성");
        when(review.getProposedValue()).thenReturn("잘못된 제안");
        when(review.getComparisonReason()).thenReturn("잘못된 판단");
        earlier(earlier);
        var reference = source.capture(current).path("pending-world:" + review.getId());
        assertThat(reference.path("value").asText()).isEqualTo("바깥의 불빛");
        assertThat(reference.toString()).doesNotContain("없는 범위", "없는 속성", "잘못된");
        assertThat(reference.path("scopeName").asText()).isEqualTo("외부");
    }

    @ParameterizedTest
    @EnumSource(value = AnalysisJobStatus.class, names = {"FAILED", "PENDING", "RUNNING", "CANCELED"})
    @DisplayName("최신 분석이 실패하거나 진행 중이어도 과거 성공 분석을 다시 참고하지 않는다")
    void latestUnusableJobSuppressesOlderResults(AnalysisJobStatus latestStatus) {
        var oldest = job(2);
        var latest = job(2);
        doReturn(oldest.getEpisode()).when(latest).getEpisode();
        when(latest.getStatus()).thenReturn(latestStatus);
        character(oldest);
        earlier(latest, oldest);
        assertThat(source.capture(current)).isEmpty();
    }

    @Test
    @DisplayName("최신 성공 분석의 현재 후보만 선택하여 재분석 전 후보를 중복 수집하지 않는다")
    void newerSuccessfulJobWins() {
        var oldest = job(2);
        var latest = job(2);
        doReturn(oldest.getEpisode()).when(latest).getEpisode();
        var oldCandidate = character(oldest);
        var latestCandidate = character(latest);
        earlier(latest, oldest);
        var references = source.capture(current);
        assertThat(references.size()).isEqualTo(1);
        assertThat(references.has("pending-character:" + oldCandidate.getId())).isFalse();
        assertThat(references.has("pending-character:" + latestCandidate.getId())).isTrue();
    }

    @Test
    @DisplayName("미래·현재 회차와 다른 작품 및 숨김 재비교 작업은 참고하지 않는다")
    void ignoresOutOfScopeJobs() {
        var future = job(8);
        var same = job(7);
        var foreign = job(1);
        var foreignWork = mock(Work.class);
        when(foreignWork.getId()).thenReturn(UUID.randomUUID());
        when(foreign.getWork()).thenReturn(foreignWork);
        var comparison = job(2);
        when(comparison.getJobType()).thenReturn(AnalysisJobType.CHARACTER_FACT_COMPARISON);
        for (var job : List.of(future, same, foreign, comparison)) character(job);
        earlier(future, same, foreign, comparison);
        assertThat(source.capture(current)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"hash", "key", "version", "episode", "archived", "missing_manifest"})
    @DisplayName("일반 단일 분석도 원문 교체·삭제·번호 변경·출처 불명을 차단한다")
    void validatesSourceManifestForLegacySingleAnalysis(String mutation) {
        var earlier = job(2);
        when(earlier.isOrderedProvisional()).thenReturn(false);
        character(earlier);
        switch (mutation) {
            case "hash" -> when(earlier.getEpisode().getContentHash()).thenReturn("b".repeat(64));
            case "key" -> when(earlier.getEpisode().getContentS3Key()).thenReturn("replaced");
            case "version" -> when(earlier.getEpisode().getContentS3Version()).thenReturn("v2");
            case "episode" -> when(earlier.getSourceEpisodeNo()).thenReturn(3);
            case "archived" -> when(earlier.getEpisode().getStatus()).thenReturn(EpisodeStatus.ARCHIVED);
            case "missing_manifest" -> when(earlier.getSourceContentHash()).thenReturn(null);
            default -> throw new AssertionError(mutation);
        }
        earlier(earlier);
        assertThat(source.capture(current)).isEmpty();
    }

    @Test
    @DisplayName("확정·제외·인용 없는 후보와 아직 비교 중인 후보는 포함하지 않는다")
    void rejectsReviewedPurgedAndInFlightCandidates() {
        var earlier = job(2);
        var confirmed = character(earlier);
        when(confirmed.getReviewStatus()).thenReturn(SettingCandidateReviewStatus.CONFIRMED);
        var dismissed = character(earlier);
        when(dismissed.getReviewStatus()).thenReturn(SettingCandidateReviewStatus.DISMISSED);
        var purged = character(earlier);
        when(purged.getEvidenceSpans()).thenReturn(JsonNodeFactory.instance.arrayNode());
        var processing = character(earlier);
        when(processing.getComparisonStatus()).thenReturn(CharacterFactComparisonStatus.PROCESSING);
        when(characters.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(earlier.getId()))
                .thenReturn(List.of(confirmed, dismissed, purged, processing));
        var world = world(earlier);
        when(world.getFinalOperation()).thenReturn(WorldSettingOperation.EXCLUDE);
        earlier(earlier);
        assertThat(source.capture(current)).isEmpty();
    }

    @Test
    @DisplayName("무효화된 분석에서는 사용자가 수정한 현재 후보만 최신값으로 참고한다")
    void invalidatedAnalysisAllowsOnlyExplicitCurrentHumanEdits() {
        var earlier = job(2);
        when(earlier.getJournalStatus()).thenReturn(AnalysisJournalStatus.INVALIDATED);
        var original = character(earlier);
        var edited = character(earlier);
        when(edited.isUserModified()).thenReturn(true);
        when(edited.getAttributeValue()).thenReturn("작가의 최신 수정");
        when(edited.getProposedFactValue()).thenReturn("과거 AI 제안");
        when(characters.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(earlier.getId())).thenReturn(List.of(original, edited));
        var world = world(earlier);
        when(world.getFinalOperation()).thenReturn(WorldSettingOperation.UPDATE);
        when(world.getFinalSubjectName()).thenReturn("수정한 미궁");
        when(world.getFinalScopeName()).thenReturn("새 범위");
        when(world.getFinalSettingName()).thenReturn("새 설정");
        when(world.getFinalValue()).thenReturn("수정값");
        when(world.getComparisonReason()).thenReturn("과거 AI 판단");
        earlier(earlier);
        var references = source.capture(current);
        assertThat(references.size()).isEqualTo(2);
        assertThat(references.path("pending-character:" + edited.getId()).path("value").asText()).isEqualTo("작가의 최신 수정");
        assertThat(references.path("pending-world:" + world.getId()).path("scopeName").asText()).isEqualTo("새 범위");
        assertThat(references.path("pending-world:" + world.getId()).path("value").asText()).isEqualTo("수정값");
        assertThat(references.toString()).doesNotContain("과거 AI", "바깥의 불빛");
        references.forEach(reference -> assertThat(reference.path("reason").asText()).contains("사용자", "인용은 수정 전 원문"));
    }

    @Test
    @DisplayName("원문이 바뀐 경우 사용자 수정 표식이 있어도 참고를 되살리지 않는다")
    void userEditCannotBypassChangedSource() {
        var earlier = job(2);
        when(earlier.getJournalStatus()).thenReturn(AnalysisJournalStatus.INVALIDATED);
        var edited = character(earlier);
        when(edited.isUserModified()).thenReturn(true);
        when(earlier.getEpisode().getContentS3Key()).thenReturn("replaced");
        earlier(earlier);
        assertThat(source.capture(current)).isEmpty();
    }

    @Test
    @DisplayName("최신 무효 분석의 원래 AI 후보와 이전 성공 후보를 모두 제외한다")
    void invalidatedLatestAnalysisCannotReviveOldAiClaims() {
        var older = job(2);
        var latest = job(2);
        doReturn(older.getEpisode()).when(latest).getEpisode();
        when(latest.getJournalStatus()).thenReturn(AnalysisJournalStatus.INVALIDATED);
        character(older);
        character(latest);
        earlier(latest, older);
        assertThat(source.capture(current)).isEmpty();
    }

    @Test
    @DisplayName("사용자가 인물 연결을 고친 후보는 새 연결 이름으로 참고하되 보관된 인물은 제외한다")
    void humanMatchUsesCurrentNameAndDoesNotRestoreArchivedCharacters() {
        var earlier = job(2);
        when(earlier.getJournalStatus()).thenReturn(AnalysisJournalStatus.INVALIDATED);
        var candidate = character(earlier);
        when(candidate.isUserModified()).thenReturn(true);
        var character = mock(WorkCharacter.class);
        when(character.getName()).thenReturn("작가가 연결한 인물");
        when(character.getStatus()).thenReturn(CharacterStatus.ACTIVE);
        when(candidate.getMatchedCharacter()).thenReturn(character);
        earlier(earlier);
        assertThat(source.capture(current).path("pending-character:" + candidate.getId()).path("subjectName").asText())
                .isEqualTo("작가가 연결한 인물");
        when(character.getStatus()).thenReturn(CharacterStatus.ARCHIVED);
        assertThat(source.capture(current)).isEmpty();
    }

    @Test
    @DisplayName("사용자가 값을 비운 후보나 불완전한 세계관 수정안은 기존 AI 값으로 복원하지 않는다")
    void emptyHumanEditDoesNotFallBackToAiValue() {
        var earlier = job(2);
        var character = character(earlier);
        when(character.isUserModified()).thenReturn(true);
        when(character.getAttributeValue()).thenReturn(null);
        when(character.getProposedFactValue()).thenReturn("과거값");
        var world = world(earlier);
        when(world.getFinalOperation()).thenReturn(WorldSettingOperation.ADD);
        when(world.getFinalValue()).thenReturn(null);
        earlier(earlier);
        assertThat(source.capture(current)).isEmpty();
    }

    private void earlier(AnalysisJob... history) {
        when(jobs.findEarlierExtractionJobsForReferences(work.getId(), 7)).thenReturn(List.of(history));
    }

    private AnalysisJob job(int number) {
        var episode = mock(Episode.class);
        when(episode.getId()).thenReturn(UUID.randomUUID());
        when(episode.getEpisodeNo()).thenReturn(number);
        when(episode.getStatus()).thenReturn(EpisodeStatus.ANALYZED);
        when(episode.getContentHash()).thenReturn("a".repeat(64));
        when(episode.getContentS3Key()).thenReturn("test/" + number);
        when(episode.getContentS3Version()).thenReturn("v1");
        var job = mock(AnalysisJob.class);
        when(job.getId()).thenReturn(UUID.randomUUID());
        when(job.getWork()).thenReturn(work);
        when(job.getEpisode()).thenReturn(episode);
        when(job.getSourceEpisodeNo()).thenReturn(number);
        when(job.getSourceContentHash()).thenReturn("a".repeat(64));
        when(job.getSourceContentS3Key()).thenReturn("test/" + number);
        when(job.getSourceContentS3Version()).thenReturn("v1");
        when(job.getJobType()).thenReturn(AnalysisJobType.SETTING_EXTRACTION);
        when(job.getStatus()).thenReturn(AnalysisJobStatus.SUCCEEDED);
        when(job.isOrderedProvisional()).thenReturn(true);
        when(job.getJournalStatus()).thenReturn(AnalysisJournalStatus.SEALED);
        return job;
    }

    private SettingCandidate character(AnalysisJob job) {
        var candidate = mock(SettingCandidate.class);
        when(candidate.getId()).thenReturn(UUID.randomUUID());
        doReturn(job.getWork()).when(candidate).getWork();
        doReturn(job.getEpisode()).when(candidate).getEpisode();
        doReturn(job.getSourceContentS3Key()).when(candidate).getSourceContentS3Key();
        when(candidate.getReviewStatus()).thenReturn(SettingCandidateReviewStatus.PENDING_REVIEW);
        when(candidate.getComparisonStatus()).thenReturn(CharacterFactComparisonStatus.FAILED);
        when(candidate.getEntityName()).thenReturn("에르웬");
        when(candidate.getAttributeName()).thenReturn("정신");
        when(candidate.getAttributeValue()).thenReturn("35");
        when(candidate.getEvidenceSpans()).thenReturn(evidence());
        when(characters.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(job.getId())).thenReturn(List.of(candidate));
        return candidate;
    }

    private WorldSettingCandidate world(AnalysisJob job) {
        var candidate = mock(WorldSettingCandidate.class);
        when(candidate.getId()).thenReturn(UUID.randomUUID());
        doReturn(job.getWork()).when(candidate).getWork();
        doReturn(job.getEpisode()).when(candidate).getSourceEpisode();
        when(candidate.getReviewStatus()).thenReturn(WorldSettingReviewStatus.PENDING_REVIEW);
        when(candidate.getComparisonStatus()).thenReturn(WorldSettingComparisonStatus.COMPLETED);
        when(candidate.getSubjectName()).thenReturn("미궁");
        when(candidate.getScopeName()).thenReturn("외부");
        when(candidate.getSettingName()).thenReturn("조명");
        when(candidate.getExtractedValue()).thenReturn("바깥의 불빛");
        when(candidate.getEvidenceSpans()).thenReturn(evidence());
        when(worlds.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(job.getId())).thenReturn(List.of(candidate));
        return candidate;
    }

    private ArrayNode evidence() {
        return JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("quote", "이전 회차의 원문 근거"));
    }
}
