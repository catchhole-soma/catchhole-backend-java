package org.monitoring.catchholebackend.domain.worldsetting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateGroupConfirmRequest.Decision;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecision;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("세계관 누적 분석 사용자 확정 검증")
class WorldSettingAnalysisConfirmationTest {
    private final WorldSettingCandidateRepository repository = mock(WorldSettingCandidateRepository.class);
    private final WorldSettingAnalysisConfirmation confirmation = new WorldSettingAnalysisConfirmation(repository);
    private final Work work = Work.create(Member.register("writer@example.com", "pass", "01012345678", "작가"),
            "작품", WorkGenre.FANTASY, "설명");
    private final String subjectKey = "provisional-world:" + UUID.randomUUID();

    @Test
    @DisplayName("사용자가 저장한 수정 결정은 실제 전체 경로로 재검증해 오래된 AI 값에 막히지 않고 확정한다")
    void appliesExplicitAuthorDraftAgainstActualProperty() {
        WorldSetting actual = WorldSetting.create(work, WorldSettingCategory.RACE, "설인", "서식지", "현재 값");
        WorldSettingCandidate candidate = candidate(2, subjectKey, WorldSettingSuggestedOperation.MERGE, "북부", "AI 제안");
        when(candidate.getFinalOperation()).thenReturn(WorldSettingOperation.UPDATE);
        Decision edited = new Decision(candidate.getId(), WorldSettingOperation.UPDATE, WorldSettingCategory.RACE,
                "설인", null, "서식지", "작가가 확인한 최종 값", true, null);
        var result = confirmation.project(List.of(candidate), Map.of(candidate.getId(), edited), actual);
        assertThat(result.orElseThrow()).containsExactly(new WorldSetting.Property(null, "서식지", "작가가 확인한 최종 값"));
        assertThat(actual.getProperties()).containsExactly(new WorldSetting.Property(null, "서식지", "현재 값"));
    }

    @Test
    @DisplayName("신규 임시 대상의 ADD 이후 MERGE를 선택하면 최종 제안 문자열을 한 번에 반영한다")
    void projectsNewIdentityAcrossEpisodesWithoutCreatingFakeEntity() {
        WorldSettingCandidate first = candidate(1, subjectKey, WorldSettingSuggestedOperation.ADD, null, "북부");
        WorldSettingCandidate later = candidate(2, subjectKey, WorldSettingSuggestedOperation.MERGE, "북부", "북부의 설원");
        var result = confirmation.project(List.of(later, first), Map.of(first.getId(), selection(first),
                later.getId(), selection(later)), null);
        assertThat(result).isPresent();
        assertThat(result.orElseThrow()).containsExactly(new WorldSetting.Property(null, "서식지", "북부의 설원"));
        assertThat(first.getTargetWorldSetting()).isNull();
        assertThat(later.getTargetWorldSetting()).isNull();
    }

    @Test
    @DisplayName("선행 ADD를 제외한 MERGE와 다른 임시 주체를 이름만으로 묶는 확정은 거절한다")
    void rejectsMissingPredecessorAndNamesakeIdentity() {
        WorldSettingCandidate first = candidate(1, subjectKey, WorldSettingSuggestedOperation.ADD, null, "북부");
        WorldSettingCandidate later = candidate(2, subjectKey, WorldSettingSuggestedOperation.MERGE, "북부", "북부의 설원");
        assertThat(confirmation.project(List.of(later), Map.of(later.getId(), selection(later)), null)).isEmpty();
        WorldSettingCandidate namesake = candidate(2, "provisional-world:" + UUID.randomUUID(),
                WorldSettingSuggestedOperation.ADD, null, "남부");
        assertThat(confirmation.project(List.of(first, namesake),
                Map.of(first.getId(), selection(first), namesake.getId(), selection(namesake)), null)).isEmpty();
    }

    @Test
    @DisplayName("이미 확정한 임시 anchor의 실제 대상이 바뀌면 앞 회차 분석값으로 덮어쓰지 않는다")
    void rejectsChangedConfirmedPropertyWithoutMutatingEntity() {
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        WorldSetting actual = WorldSetting.create(work, WorldSettingCategory.RACE, "설인", "서식지", "작가가 정한 남부");
        ReflectionTestUtils.setField(actual, "id", UUID.randomUUID());
        WorldSettingCandidate anchor = mock(WorldSettingCandidate.class);
        when(anchor.getTargetWorldSetting()).thenReturn(actual);
        when(repository.findByIdAndWorkId(UUID.fromString(subjectKey.substring("provisional-world:".length())), work.getId()))
                .thenReturn(Optional.of(anchor));
        WorldSettingCandidate later = candidate(2, subjectKey, WorldSettingSuggestedOperation.MERGE, "북부", "북부의 설원");
        assertThat(confirmation.project(List.of(later), Map.of(later.getId(), selection(later)), actual)).isEmpty();
        assertThat(actual.getProperties()).containsExactly(new WorldSetting.Property(null, "서식지", "작가가 정한 남부"));
        assertThat(actual.getVersion()).isZero();
    }

    @Test
    @DisplayName("같은 이름이지만 서로 다른 신규 대상으로 판단한 보류는 값 변경과 구별한다")
    void recordsDistinctSubjectReasonWithoutChangingComparisons() {
        WorldSettingCandidate first = candidate(1, subjectKey, WorldSettingSuggestedOperation.ADD, null, "북부");
        WorldSettingCandidate other = candidate(1, "provisional-world:" + UUID.randomUUID(),
                WorldSettingSuggestedOperation.ADD, null, "남부");
        var result = confirmation.projectWithReason(List.of(first, other),
                Map.of(first.getId(), selection(first), other.getId(), selection(other)), null);
        assertThat(result.properties()).isNull();
        assertThat(result.holdReason()).isEqualTo(
                org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.SUBJECT_CONFIRMATION_REQUIRED);
        assertThat(other.getComparisonDecision().getSuggestedOperation()).isEqualTo(WorldSettingSuggestedOperation.ADD);
    }

    @Test
    @DisplayName("자동 검증은 비교 version 순서로 이동 후 변경을 복원하고 수동 검증은 기존 근거 순서를 유지한다")
    void automaticComparisonOrderDoesNotChangeManualEvidenceOrder() {
        WorldSetting actual = WorldSetting.create(work, WorldSettingCategory.RACE, "설인", "서식지", "북부");
        ReflectionTestUtils.setField(actual, "id", UUID.randomUUID());
        var moved = candidate(1, null, WorldSettingSuggestedOperation.ADD, null, "추위에 강함");
        var updated = candidate(1, null, WorldSettingSuggestedOperation.UPDATE, "북부", "남부");
        when(moved.getComparisonDecision().getTargetWorldSetting()).thenReturn(actual);
        when(updated.getComparisonDecision().getTargetWorldSetting()).thenReturn(actual);
        when(moved.getComparisonDecision().getProposedScopeName()).thenReturn("생태");
        when(moved.getComparisonDecision().getProposedSettingName()).thenReturn("특징");
        when(moved.getComparisonDecision().getExistingRootPropertyMoveSnapshots()).thenReturn(List.of(
                new WorldSettingComparisonDecision.ExistingRootPropertyMoveSnapshot("서식지", "북부")));
        when(updated.getComparisonDecision().getProposedScopeName()).thenReturn("생태");
        setComparisonOrder(moved, 0L, 30);
        setComparisonOrder(updated, 1L, 10);
        var selections = Map.of(moved.getId(), selection(moved), updated.getId(), selection(updated));

        var automatic = confirmation.projectAutomatically(List.of(updated, moved), selections, actual);
        var manual = confirmation.projectWithReason(List.of(moved, updated), selections, actual);

        assertThat(automatic.properties()).containsExactlyInAnyOrder(
                new WorldSetting.Property("생태", "서식지", "남부"),
                new WorldSetting.Property("생태", "특징", "추위에 강함"));
        assertThat(automatic.holdReason()).isNull();
        assertThat(manual.properties()).isNull();
        assertThat(manual.holdReason()).isEqualTo(AutomaticReviewHoldReason.CURRENT_SETTING_CHANGED);
        assertThat(actual.getProperties()).containsExactly(new WorldSetting.Property(null, "서식지", "북부"));
        assertThat(actual.getVersion()).isZero();
    }

    @Test
    @DisplayName("같은 비교 version 안의 자동 검증은 기존 근거 순서를 바꾸지 않는다")
    void automaticProjectionKeepsEvidenceOrderWithinTheSameVersion() {
        var added = candidate(1, subjectKey, WorldSettingSuggestedOperation.ADD, null, "북부");
        var updated = candidate(1, subjectKey, WorldSettingSuggestedOperation.UPDATE, "북부", "남부");
        setComparisonOrder(added, 0L, 30);
        setComparisonOrder(updated, 0L, 10);

        var result = confirmation.projectAutomatically(List.of(added, updated),
                Map.of(added.getId(), selection(added), updated.getId(), selection(updated)), null);

        assertThat(result.properties()).isNull();
        assertThat(result.holdReason()).isEqualTo(AutomaticReviewHoldReason.CURRENT_SETTING_CHANGED);
    }

    @Test
    @DisplayName("자동 비교 순서 검증도 실제 대상 불일치와 존재하지 않는 변경 경로를 거절한다")
    void automaticProjectionRetainsIdentityAndPathGuards() {
        WorldSetting actual = WorldSetting.create(work, WorldSettingCategory.RACE, "설인", "수명", "장수");
        ReflectionTestUtils.setField(actual, "id", UUID.randomUUID());
        var updated = candidate(1, subjectKey, WorldSettingSuggestedOperation.UPDATE, "북부", "남부");
        setComparisonOrder(updated, 1L, 10);
        var selections = Map.of(updated.getId(), selection(updated));

        var wrongTarget = confirmation.projectAutomatically(List.of(updated), selections, actual);
        assertThat(wrongTarget.properties()).isNull();
        assertThat(wrongTarget.holdReason()).isEqualTo(AutomaticReviewHoldReason.SUBJECT_CONFIRMATION_REQUIRED);

        when(updated.getComparisonDecision().getProvisionalSubjectKey()).thenReturn(null);
        when(updated.getComparisonDecision().getTargetWorldSetting()).thenReturn(actual);
        var missingPath = confirmation.projectAutomatically(List.of(updated), selections, actual);
        assertThat(missingPath.properties()).isNull();
        assertThat(missingPath.holdReason()).isEqualTo(AutomaticReviewHoldReason.CURRENT_SETTING_CHANGED);
        assertThat(actual.getProperties()).containsExactly(new WorldSetting.Property(null, "수명", "장수"));
    }

    private WorldSettingCandidate candidate(int episodeNo, String key, WorldSettingSuggestedOperation operation,
            String before, String value) {
        var candidate = mock(WorldSettingCandidate.class);
        var decision = mock(WorldSettingComparisonDecision.class);
        var episode = mock(Episode.class);
        when(candidate.getId()).thenReturn(UUID.randomUUID());
        when(candidate.getWork()).thenReturn(work);
        when(candidate.getSourceEpisode()).thenReturn(episode);
        when(episode.getEpisodeNo()).thenReturn(episodeNo);
        when(candidate.getComparisonDecision()).thenReturn(decision);
        when(decision.getId()).thenReturn(UUID.randomUUID());
        when(decision.getProvisionalSubjectKey()).thenReturn(key);
        when(decision.getSuggestedOperation()).thenReturn(operation);
        when(decision.getBeforeValue()).thenReturn(before);
        when(decision.getProposedSettingName()).thenReturn("서식지");
        when(decision.getProposedValue()).thenReturn(value);
        when(decision.getExistingRootPropertyMoveSnapshots()).thenReturn(List.of());
        return candidate;
    }

    private Decision selection(WorldSettingCandidate candidate) {
        var comparison = candidate.getComparisonDecision();
        return new Decision(candidate.getId(), WorldSettingOperation.valueOf(comparison.getSuggestedOperation().name()),
                WorldSettingCategory.RACE, "설인", comparison.getProposedScopeName(), comparison.getProposedSettingName(),
                comparison.getProposedValue(), true, null);
    }

    private void setComparisonOrder(WorldSettingCandidate candidate, long version, int evidenceOffset) {
        when(candidate.getComparisonDecision().getBaseWorldSettingVersion()).thenReturn(version);
        when(candidate.getEvidenceSpans()).thenReturn(JsonNodeFactory.instance.arrayNode()
                .add(JsonNodeFactory.instance.objectNode().put("startOffset", evidenceOffset)));
    }
}
