package org.monitoring.catchholebackend.domain.worldsetting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonBatch;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecision;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecisionSource;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingMapper;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingComparisonDecisionSourceRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonReviewReason;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingConsolidationStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("회차 완료 시 세계관 안전 자동 반영")
class WorldSettingAutomaticApplicationTest {
    private final WorldSettingCandidateRepository candidates = mock(WorldSettingCandidateRepository.class);
    private final WorldSettingComparisonDecisionSourceRepository sources = mock(WorldSettingComparisonDecisionSourceRepository.class);
    private final WorldSettingRepository settings = mock(WorldSettingRepository.class);
    private final AnalysisRunStateService runState = mock(AnalysisRunStateService.class);
    private final WorldSettingCandidateServiceImpl service = new WorldSettingCandidateServiceImpl(
            mock(WorkRepository.class), runState, mock(UploadBatchRepository.class),
            mock(AnalysisJobRepository.class), settings, mock(org.monitoring.catchholebackend.domain.worldimage.service.AutomaticImageService.class), candidates, sources,
            mock(WorldSettingMapper.class), mock(AiTokenService.class), new WorldSettingAnalysisConfirmation(candidates,
                    mock(org.monitoring.catchholebackend.domain.analysis.service.AnalysisCandidateSourceGuard.class)));
    private final List<WorldSettingCandidate> rows = new ArrayList<>();
    private final List<WorldSettingComparisonDecisionSource> sourceRows = new ArrayList<>();
    private final Map<String, WorldSetting> actual = new LinkedHashMap<>();
    private final JsonNodeFactory json = JsonNodeFactory.instance;
    private Work work;
    private AnalysisJob job;
    private Episode episode;

    @BeforeEach
    void setUp() {
        work = Work.create(Member.register("automatic-world@example.com", "pass", "01012345678", "작가"),
                "작품", WorkGenre.FANTASY, "설명");
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        UploadBatch upload = mock(UploadBatch.class);
        UUID uploadId = UUID.randomUUID();
        when(upload.getId()).thenReturn(uploadId);
        episode = Episode.create(work, null, 11, "11화", "local/source", "v1", "a".repeat(64), 100);
        ReflectionTestUtils.setField(episode, "id", UUID.randomUUID());
        job = AnalysisJob.create(work, upload, episode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(job, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(job, "analysisMode", AnalysisMode.ORDERED_PROVISIONAL);
        job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        when(candidates.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(job.getId())).thenAnswer(invocation -> List.copyOf(rows));
        when(candidates.findAllByIdsAndBatchForUpdate(eq(work.getId()), eq(uploadId), anySet()))
                .thenAnswer(invocation -> rows.stream().filter(row -> invocation.<Set<UUID>>getArgument(2).contains(row.getId())).toList());
        when(candidates.findByIdAndWorkId(any(), eq(work.getId())))
                .thenAnswer(invocation -> rows.stream().filter(row -> row.getId().equals(invocation.getArgument(0))).findFirst());
        when(sources.findAllByComparisonDecisionIdIn(anySet()))
                .thenAnswer(invocation -> sourceRows.stream().filter(source -> invocation.<Set<UUID>>getArgument(0)
                        .contains(source.getComparisonDecision().getId())).toList());
        when(settings.findByIdentityForUpdate(eq(work.getId()), eq(WorldSettingCategory.RACE), any()))
                .thenAnswer(invocation -> Optional.ofNullable(actual.get(invocation.<String>getArgument(2))));
        when(settings.saveAndFlush(any())).thenAnswer(invocation -> {
            WorldSetting target = invocation.getArgument(0);
            ReflectionTestUtils.setField(target, "id", UUID.randomUUID());
            actual.put(target.getNormalizedSubjectName(), target);
            return target;
        });
    }

    @Test
    @DisplayName("같은 회차의 추가와 뒤 변경을 원문 순서대로 반영하고 재실행해도 중복 생성하지 않는다")
    void addsThenUpdatesChronologicallyAndIsIdempotent() {
        WorldSettingCandidate first = candidate("설인", "서식지", "북부", null, WorldSettingSuggestedOperation.ADD, null, null);
        candidate("설인", "서식지", "북부 설원", "북부", WorldSettingSuggestedOperation.MERGE, first.getProvisionalSubjectKey(), null);
        service.applyAutomatically(job);
        service.applyAutomatically(job);
        assertThat(actual).hasSize(1);
        assertThat(actual.get("설인").getPropertyValue("서식지")).isEqualTo("북부 설원");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED);
            assertThat(row.isReviewedAutomatically()).isTrue();
            assertThat(row.getReviewedBy()).isNull();
        });
        verify(settings).saveAndFlush(any());
        verifyNoInteractions(runState);
    }

    @Test
    @DisplayName("미해결 충돌 사용자 편집 실패 후보는 남기고 독립적인 정상 설정은 자동 반영한다")
    void retainsUnsafeCandidatesAndAppliesIndependentValidSetting() {
        var review = candidate("검토", "서식지", "북부", null, WorldSettingSuggestedOperation.REVIEW_REQUIRED, null, null);
        var conflict = candidate("충돌", "서식지", "북부", null, WorldSettingSuggestedOperation.ADD, null, null);
        ReflectionTestUtils.setField(conflict, "consolidationStatus", WorldSettingConsolidationStatus.CONFLICT);
        var edited = candidate("편집", "서식지", "북부", null, WorldSettingSuggestedOperation.ADD, null, null);
        edited.updateDecisionDraft(WorldSettingOperation.ADD, WorldSettingCategory.RACE, "편집", null, "서식지", "사용자 값", null);
        var ambiguous = candidate("미상", "서식지", "북부", null, WorldSettingSuggestedOperation.ADD, null, null);
        ReflectionTestUtils.setField(ambiguous, "subjectResolutionType", WorldSettingSubjectResolutionType.AMBIGUOUS);
        var failed = candidate("실패", "서식지", "북부", null, WorldSettingSuggestedOperation.ADD, null, null);
        ReflectionTestUtils.setField(failed, "comparisonStatus", WorldSettingComparisonStatus.FAILED);
        var normal = candidate("정상", "서식지", "남부", null, WorldSettingSuggestedOperation.ADD, null, null);
        service.applyAutomatically(job);
        assertThat(List.of(review, conflict, edited, ambiguous, failed))
                .allSatisfy(row -> assertThat(row.isPendingReview()).isTrue());
        assertThat(normal.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED);
        assertThat(actual).containsOnlyKeys("정상");
    }

    @Test
    @DisplayName("범위 불일치 검토는 기존 값과 원본 경로를 남기고 다른 정상 후보의 자동 저장을 막지 않는다")
    void scopeMismatchStaysPendingWhileIndependentCandidateIsApplied() {
        var target = existing("설인", "서식지", "북부");
        long initialVersion = target.getVersion();
        var review = candidate("설인", "서식지", "남부 설원", "북부",
                WorldSettingSuggestedOperation.REVIEW_REQUIRED, null, target);
        ReflectionTestUtils.setField(review, "scopeName", "외부");
        ReflectionTestUtils.setField(review.getComparisonDecision(), "proposedScopeName", "외부");
        ReflectionTestUtils.setField(review.getComparisonDecision(), "comparisonReviewReason", WorldSettingComparisonReviewReason.SCOPE_MISMATCH);
        ReflectionTestUtils.setField(review, "proposedScopeName", "외부");
        ReflectionTestUtils.setField(review, "comparisonReviewReason", WorldSettingComparisonReviewReason.SCOPE_MISMATCH);
        var normal = candidate("정상", "서식지", "남부", null, WorldSettingSuggestedOperation.ADD, null, null);

        service.applyAutomatically(job);

        assertThat(review.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.COMPLETED);
        assertThat(review.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.PENDING_REVIEW);
        assertThat(review.getComparisonReviewReason()).isEqualTo(WorldSettingComparisonReviewReason.SCOPE_MISMATCH);
        assertThat(review.getScopeName()).isEqualTo("외부");
        assertThat(review.getMatchedPropertyName()).isEqualTo("서식지");
        assertThat(review.isReviewedAutomatically()).isFalse();
        assertThat(target.getPropertyValue("서식지")).isEqualTo("북부");
        assertThat(target.getPropertyValue("외부", "서식지")).isNull();
        assertThat(target.getVersion()).isEqualTo(initialVersion);
        assertThat(normal.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED);
        assertThat(actual.get("정상").getPropertyValue("서식지")).isEqualTo("남부");
    }

    @Test
    @DisplayName("일반 검토는 현재 설정을 바꾸지 않고 보류하며 다른 설정의 자동 확정은 계속한다")
    void generalUncertaintyRemainsPendingWhileOtherCandidateIsApplied() {
        var target = existing("설인", "서식지", "북부");
        long version = target.getVersion();
        var review = candidate("설인", "서식지", "남부일 수 있다", "북부",
                WorldSettingSuggestedOperation.REVIEW_REQUIRED, null, target);
        ReflectionTestUtils.setField(review.getComparisonDecision(), "comparisonReviewReason", WorldSettingComparisonReviewReason.GENERAL_UNCERTAINTY);
        ReflectionTestUtils.setField(review, "comparisonReviewReason", WorldSettingComparisonReviewReason.GENERAL_UNCERTAINTY);
        var normal = candidate("정상", "서식지", "남부", null, WorldSettingSuggestedOperation.ADD, null, null);
        service.applyAutomatically(job);
        assertThat(review.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.PENDING_REVIEW);
        assertThat(review.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.COMPLETED);
        assertThat(review.isReviewedAutomatically()).isFalse();
        assertThat(target.getPropertyValue("서식지")).isEqualTo("북부");
        assertThat(target.getVersion()).isEqualTo(version);
        assertThat(normal.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED);
    }

    @Test
    @DisplayName("공유 결정의 일부 source가 편집되어 있으면 전체 결정을 남기고 독립 속성만 반영한다")
    void requiresEverySourceOfSharedDecisionToBeEligible() {
        var first = candidate("설인", "서식지", "북부", null, WorldSettingSuggestedOperation.ADD, null, null);
        var second = candidate("설인", "분포", "북부", null, WorldSettingSuggestedOperation.ADD, first.getProvisionalSubjectKey(), null);
        shareDecision(first, second);
        second.updateDecisionDraft(WorldSettingOperation.ADD, WorldSettingCategory.RACE, "설인", null, "서식지", "남부", null);
        var independent = candidate("설인", "신체", "강인함", null, WorldSettingSuggestedOperation.ADD, first.getProvisionalSubjectKey(), null);
        service.applyAutomatically(job);
        assertThat(first.isPendingReview()).isTrue();
        assertThat(second.isPendingReview()).isTrue();
        assertThat(independent.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED);
        assertThat(actual.get("설인").getProperties()).containsExactly(new WorldSetting.Property(null, "신체", "강인함"));
    }

    @Test
    @DisplayName("공유 결정의 모든 source가 정상일 때 속성 하나를 저장하고 전체 source에 자동 반영을 기록한다")
    void appliesCompleteSharedDecisionOnce() {
        var first = candidate("설인", "서식지", "북부", null, WorldSettingSuggestedOperation.ADD, null, null);
        var second = candidate("설인", "분포", "북부", null, WorldSettingSuggestedOperation.ADD, first.getProvisionalSubjectKey(), null);
        shareDecision(first, second);
        service.applyAutomatically(job);
        assertThat(rows).allSatisfy(row -> assertThat(row.isReviewedAutomatically()).isTrue());
        assertThat(actual.get("설인").getProperties()).hasSize(1);
    }

    @Test
    @DisplayName("기존값이 바뀐 그룹은 자동 덮어쓰지 않고 다른 정상 그룹은 계속 반영한다")
    void staleGroupCannotOverwriteCurrentFact() {
        WorldSetting target = existing("설인", "서식지", "작가가 정한 남부");
        var stale = candidate("설인", "서식지", "북부 설원", "북부", WorldSettingSuggestedOperation.UPDATE, null, target);
        var safe = candidate("엘프", "수명", "장수", null, WorldSettingSuggestedOperation.ADD, null, null);
        service.applyAutomatically(job);
        assertThat(stale.isPendingReview()).isTrue();
        assertThat(target.getPropertyValue("서식지")).isEqualTo("작가가 정한 남부");
        assertThat(safe.isReviewedAutomatically()).isTrue();
    }

    @Test
    @DisplayName("이전값이 달라진 중복 제외 판단은 보류하고 비교 대상이 없는 정상 제외는 처리한다")
    void staleDuplicateExclusionIsHeldButIndependentExclusionIsDismissed() {
        WorldSetting target = existing("설인", "서식지", "남부");
        var stale = candidate("설인", "서식지", "북부", "북부", WorldSettingSuggestedOperation.EXCLUDE, null, target);
        var excluded = candidate("일시적 사건", "관찰", "한 번 관찰됨", null, WorldSettingSuggestedOperation.EXCLUDE, null, null);
        service.applyAutomatically(job);
        assertThat(stale.isPendingReview()).isTrue();
        assertThat(excluded.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.DISMISSED);
        assertThat(excluded.isReviewedAutomatically()).isTrue();
        assertThat(excluded.getReviewedBy()).isNull();
    }

    @Test
    @DisplayName("같은 회차의 선행 변경값과 일치한 후행 제외는 현재 DB의 이전값 때문에 정상 그룹을 막지 않는다")
    void exclusionUsesValidatedPrecedingChangeInsteadOfEpisodeStartValue() {
        WorldSetting target = existing("설인", "서식지", "북부");
        var changed = candidate("설인", "서식지", "남부", "북부",
                WorldSettingSuggestedOperation.UPDATE, null, target);
        var excluded = candidate("설인", "서식지", "남부", "남부",
                WorldSettingSuggestedOperation.EXCLUDE, null, target);

        service.applyAutomatically(job);
        service.applyAutomatically(job);

        assertThat(changed.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED);
        assertThat(excluded.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.DISMISSED);
        assertThat(List.of(changed, excluded)).allSatisfy(row -> {
            assertThat(row.isReviewedAutomatically()).isTrue();
            assertThat(row.getAutomaticReviewHoldReason()).isNull();
        });
        assertThat(target.getPropertyValue("서식지")).isEqualTo("남부");
        assertThat(target.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 비교 batch의 변경과 제외는 공통 비교 snapshot을 확인한 뒤 변경을 저장한다")
    void sameBatchExclusionUsesTheSharedComparisonSnapshot() {
        WorldSetting target = existing("설인", "서식지", "북부");
        var changed = candidate("설인", "서식지", "남부", "북부",
                WorldSettingSuggestedOperation.UPDATE, null, target);
        var excluded = candidate("설인", "서식지", "북부", "북부",
                WorldSettingSuggestedOperation.EXCLUDE, null, target);
        useSameComparisonBatch(changed, excluded);

        service.applyAutomatically(job);

        assertThat(changed.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED);
        assertThat(excluded.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.DISMISSED);
        assertThat(List.of(changed, excluded)).allSatisfy(row -> {
            assertThat(row.isReviewedAutomatically()).isTrue();
            assertThat(row.getAutomaticReviewHoldReason()).isNull();
        });
        assertThat(target.getPropertyValue("서식지")).isEqualTo("남부");
        assertThat(target.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("이전 batch 변경은 복원하되 제외와 같은 batch의 다음 변경은 비교 기준에서 제외한다")
    void exclusionIncludesEarlierBatchesButNotChangesInItsOwnBatch() {
        WorldSetting target = existing("설인", "서식지", "북부");
        var earlierChange = candidate("설인", "서식지", "남부", "북부",
                WorldSettingSuggestedOperation.UPDATE, null, target);
        var sameBatchChange = candidate("설인", "서식지", "동부", "남부",
                WorldSettingSuggestedOperation.UPDATE, null, target);
        var excluded = candidate("설인", "서식지", "남부", "남부",
                WorldSettingSuggestedOperation.EXCLUDE, null, target);
        useSameComparisonBatch(sameBatchChange, excluded);

        service.applyAutomatically(job);

        assertThat(List.of(earlierChange, sameBatchChange)).allSatisfy(row ->
                assertThat(row.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED));
        assertThat(excluded.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.DISMISSED);
        assertThat(target.getPropertyValue("서식지")).isEqualTo("동부");
        assertThat(target.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 batch의 공유 변경과 공유 제외의 source가 엇갈려도 고정 snapshot을 검증한다")
    void interleavedSharedDecisionsUseTheirBatchSnapshot() {
        WorldSetting target = existing("설인", "서식지", "북부");
        var changed = candidate("설인", "서식지", "남부", "북부",
                WorldSettingSuggestedOperation.UPDATE, null, target);
        var excluded = candidate("설인", "서식지", "북부", "북부",
                WorldSettingSuggestedOperation.EXCLUDE, null, target);
        useSameComparisonBatch(changed, excluded);
        var changedAgain = candidate("설인", "서식지", "남부", "북부",
                WorldSettingSuggestedOperation.UPDATE, null, target);
        shareDecision(changed, changedAgain);
        var excludedAgain = candidate("설인", "서식지", "북부", "북부",
                WorldSettingSuggestedOperation.EXCLUDE, null, target);
        shareDecision(excluded, excludedAgain);

        service.applyAutomatically(job);

        assertThat(List.of(changed, changedAgain)).allSatisfy(row ->
                assertThat(row.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED));
        assertThat(List.of(excluded, excludedAgain)).allSatisfy(row ->
                assertThat(row.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.DISMISSED));
        assertThat(rows).allSatisfy(row -> assertThat(row.isReviewedAutomatically()).isTrue());
        assertThat(target.getPropertyValue("서식지")).isEqualTo("남부");
        assertThat(target.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 batch의 변경 제안과 제외가 있어도 실제 비교값이 바뀌었으면 모두 보류한다")
    void sameBatchExclusionStillRejectsAStaleActualValue() {
        WorldSetting target = existing("설인", "서식지", "작가가 정한 동부");
        var changed = candidate("설인", "서식지", "남부", "북부",
                WorldSettingSuggestedOperation.UPDATE, null, target);
        var excluded = candidate("설인", "서식지", "북부", "북부",
                WorldSettingSuggestedOperation.EXCLUDE, null, target);
        useSameComparisonBatch(changed, excluded);

        service.applyAutomatically(job);

        assertThat(rows).allSatisfy(row -> assertThat(row.isPendingReview()).isTrue());
        assertThat(target.getPropertyValue("서식지")).isEqualTo("작가가 정한 동부");
        assertThat(target.getVersion()).isZero();
    }

    @Test
    @DisplayName("이전 batch의 공유 변경은 source 근거가 제외보다 뒤에 있어도 후행 batch의 비교 기준에 포함된다")
    void earlierBatchSharedChangePrecedesExclusionRegardlessOfEvidenceOrder() {
        WorldSetting target = existing("설인", "서식지", "북부");
        var changed = candidate("설인", "서식지", "남부", "북부",
                WorldSettingSuggestedOperation.UPDATE, null, target);
        var changedAgain = candidate("설인", "서식지", "남부", "북부",
                WorldSettingSuggestedOperation.UPDATE, null, target);
        shareDecision(changed, changedAgain);
        var excluded = candidate("설인", "서식지", "남부", "남부",
                WorldSettingSuggestedOperation.EXCLUDE, null, target);
        setEvidenceOffset(changed, 20);
        setEvidenceOffset(changedAgain, 40);
        setEvidenceOffset(excluded, 10);

        service.applyAutomatically(job);

        assertThat(List.of(changed, changedAgain)).allSatisfy(row ->
                assertThat(row.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED));
        assertThat(excluded.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.DISMISSED);
        assertThat(target.getPropertyValue("서식지")).isEqualTo("남부");
        assertThat(target.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("공유 제외의 source보다 근거가 앞에 있어도 이후 batch 변경을 비교 기준에 섞지 않는다")
    void sharedExclusionDoesNotIncludeALaterBatchChangeWithEarlierEvidence() {
        WorldSetting target = existing("설인", "서식지", "북부");
        var excluded = candidate("설인", "서식지", "북부", "북부",
                WorldSettingSuggestedOperation.EXCLUDE, null, target);
        var excludedAgain = candidate("설인", "서식지", "북부", "북부",
                WorldSettingSuggestedOperation.EXCLUDE, null, target);
        shareDecision(excluded, excludedAgain);
        var changed = candidate("설인", "서식지", "남부", "북부",
                WorldSettingSuggestedOperation.UPDATE, null, target);
        setEvidenceOffset(excluded, 20);
        setEvidenceOffset(excludedAgain, 40);
        setEvidenceOffset(changed, 10);

        service.applyAutomatically(job);

        assertThat(List.of(excluded, excludedAgain)).allSatisfy(row ->
                assertThat(row.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.DISMISSED));
        assertThat(changed.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED);
        assertThat(target.getPropertyValue("서식지")).isEqualTo("남부");
    }

    @Test
    @DisplayName("이전 batch의 신규 대상 추가는 원문 근거가 뒤에 있어도 제외가 비교한 경로를 만든다")
    void earlierBatchNewTargetIsAvailableEvenWithLaterEvidence() {
        var added = candidate("설인", "서식지", "북부", null,
                WorldSettingSuggestedOperation.ADD, null, null);
        var excluded = candidate("설인", "서식지", "북부", "북부",
                WorldSettingSuggestedOperation.EXCLUDE, added.getProvisionalSubjectKey(), null);
        ReflectionTestUtils.setField(excluded.getComparisonDecision(), "matchedPropertyName", "서식지");
        setEvidenceOffset(added, 30);
        setEvidenceOffset(excluded, 10);

        service.applyAutomatically(job);

        assertThat(added.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED);
        assertThat(excluded.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.DISMISSED);
        assertThat(actual.get("설인").getPropertyValue("서식지")).isEqualTo("북부");
    }

    @Test
    @DisplayName("같은 batch의 신규 추가는 고정 비교 snapshot에 없던 제외 경로를 정당화하지 않는다")
    void sameBatchAddCannotSupplyAnExclusionTarget() {
        var added = candidate("설인", "서식지", "북부", null,
                WorldSettingSuggestedOperation.ADD, null, null);
        var excluded = candidate("설인", "서식지", "북부", "북부",
                WorldSettingSuggestedOperation.EXCLUDE, added.getProvisionalSubjectKey(), null);
        ReflectionTestUtils.setField(excluded.getComparisonDecision(), "matchedPropertyName", "서식지");
        useSameComparisonBatch(added, excluded);

        service.applyAutomatically(job);

        assertThat(rows).allSatisfy(row -> assertThat(row.isPendingReview()).isTrue());
        assertThat(actual).isEmpty();
        verify(settings, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("변경보다 앞선 제외는 뒤의 제안값이 아니라 제외 시점의 실제값과 비교한다")
    void exclusionBeforeChangeUsesItsOwnChronologicalState() {
        WorldSetting target = existing("설인", "서식지", "북부");
        var excluded = candidate("설인", "서식지", "북부", "북부",
                WorldSettingSuggestedOperation.EXCLUDE, null, target);
        var changed = candidate("설인", "서식지", "남부", "북부",
                WorldSettingSuggestedOperation.MERGE, null, target);

        service.applyAutomatically(job);

        assertThat(excluded.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.DISMISSED);
        assertThat(changed.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED);
        assertThat(target.getPropertyValue("서식지")).isEqualTo("남부");
    }

    @Test
    @DisplayName("선행 변경의 실제 이전값이 오래됐으면 후행 제외가 제안값과 같아도 그룹을 반영하지 않는다")
    void matchingExclusionCannotBypassStalePrecedingChange() {
        WorldSetting target = existing("설인", "서식지", "작가가 정한 동부");
        var changed = candidate("설인", "서식지", "남부", "북부",
                WorldSettingSuggestedOperation.UPDATE, null, target);
        var excluded = candidate("설인", "서식지", "남부", "남부",
                WorldSettingSuggestedOperation.EXCLUDE, null, target);

        service.applyAutomatically(job);

        assertThat(List.of(changed, excluded)).allSatisfy(row -> assertThat(row.isPendingReview()).isTrue());
        assertThat(target.getPropertyValue("서식지")).isEqualTo("작가가 정한 동부");
        assertThat(target.getVersion()).isZero();
    }

    @Test
    @DisplayName("임시 대상의 선행 추가가 만든 실제 경로와 값에 일치할 때만 후행 제외를 자동 처리한다")
    void newTargetExclusionChecksThePropertyCreatedByItsPredecessor() {
        var added = candidate("설인", "서식지", "북부", null,
                WorldSettingSuggestedOperation.ADD, null, null);
        var excluded = candidate("설인", "서식지", "북부", "북부",
                WorldSettingSuggestedOperation.EXCLUDE, added.getProvisionalSubjectKey(), null);
        ReflectionTestUtils.setField(excluded.getComparisonDecision(), "matchedPropertyName", "서식지");

        service.applyAutomatically(job);

        assertThat(added.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED);
        assertThat(excluded.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.DISMISSED);
        assertThat(actual.get("설인").getPropertyValue("서식지")).isEqualTo("북부");
    }

    @Test
    @DisplayName("같은 임시 대상의 다른 속성 추가만으로 존재하지 않는 경로의 제외를 허용하지 않는다")
    void unrelatedAddedPropertyCannotValidateAnExclusion() {
        var added = candidate("설인", "수명", "장수", null,
                WorldSettingSuggestedOperation.ADD, null, null);
        var excluded = candidate("설인", "서식지", "북부", "북부",
                WorldSettingSuggestedOperation.EXCLUDE, added.getProvisionalSubjectKey(), null);
        ReflectionTestUtils.setField(excluded.getComparisonDecision(), "matchedPropertyName", "서식지");

        service.applyAutomatically(job);

        assertThat(List.of(added, excluded)).allSatisfy(row -> assertThat(row.isPendingReview()).isTrue());
        assertThat(actual).isEmpty();
        verify(settings, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("같은 이름의 다른 임시 대상에 대한 제외를 선행 추가로 정당화하지 않는다")
    void differentProvisionalIdentityCannotBorrowAPrecedingAdd() {
        var added = candidate("설인", "서식지", "북부", null,
                WorldSettingSuggestedOperation.ADD, null, null);
        var excluded = candidate("설인", "서식지", "북부", "북부",
                WorldSettingSuggestedOperation.EXCLUDE, "provisional-world:" + UUID.randomUUID(), null);
        ReflectionTestUtils.setField(excluded.getComparisonDecision(), "matchedPropertyName", "서식지");

        service.applyAutomatically(job);

        assertThat(List.of(added, excluded)).allSatisfy(row -> assertThat(row.isPendingReview()).isTrue());
        assertThat(actual).isEmpty();
    }

    @Test
    @DisplayName("제외보다 원문상 뒤에 있는 추가는 해당 제외의 비교 대상을 소급해 만들지 않는다")
    void laterAddCannotSupplyAnEarlierExclusionTarget() {
        var excluded = candidate("설인", "서식지", "북부", "북부",
                WorldSettingSuggestedOperation.EXCLUDE, null, null);
        ReflectionTestUtils.setField(excluded.getComparisonDecision(), "matchedPropertyName", "서식지");
        var added = candidate("설인", "서식지", "북부", null,
                WorldSettingSuggestedOperation.ADD, excluded.getProvisionalSubjectKey(), null);

        service.applyAutomatically(job);

        assertThat(List.of(added, excluded)).allSatisfy(row -> assertThat(row.isPendingReview()).isTrue());
        assertThat(actual).isEmpty();
    }

    @Test
    @DisplayName("선행 루트 이동으로 생긴 하위 경로도 후행 제외 검증에 같은 projected 상태로 사용한다")
    void exclusionRecognizesAPropertyMovedEarlierInTheEpisode() {
        WorldSetting target = existing("설인", "서식지", "북부");
        var added = candidate("설인", "특징", "추위에 강함", null,
                WorldSettingSuggestedOperation.ADD, null, target);
        ReflectionTestUtils.setField(added, "proposedScopeName", "생태");
        ReflectionTestUtils.setField(added.getComparisonDecision(), "proposedScopeName", "생태");
        ReflectionTestUtils.setField(added.getComparisonDecision(), "existingRootPropertyMoveSnapshotsJson",
                json.arrayNode().add(json.objectNode().put("settingName", "서식지").put("beforeValue", "북부")));
        var excluded = candidate("설인", "서식지", "북부", "북부",
                WorldSettingSuggestedOperation.EXCLUDE, null, target);
        ReflectionTestUtils.setField(excluded, "scopeName", "생태");
        ReflectionTestUtils.setField(excluded, "matchedScopeName", "생태");
        ReflectionTestUtils.setField(excluded.getComparisonDecision(), "matchedScopeName", "생태");

        service.applyAutomatically(job);

        assertThat(added.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED);
        assertThat(excluded.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.DISMISSED);
        assertThat(target.getPropertyValue("생태", "서식지")).isEqualTo("북부");
        assertThat(target.getPropertyValue("생태", "특징")).isEqualTo("추위에 강함");
    }

    @Test
    @DisplayName("루트 이동 뒤 변경과 제외의 근거 순서가 역전되어도 실제 비교 순서로 한 번에 저장한다")
    void appliesRootMoveUpdateAndExclusionInComparisonOrder() {
        WorldSetting target = existing("설인", "서식지", "북부");
        var moved = rootMoveThenUpdate(target);
        var excluded = candidate("설인", "서식지", "남부", "남부",
                WorldSettingSuggestedOperation.EXCLUDE, null, target);
        setScope(excluded, "생태");
        setEvidenceOffset(excluded, 5);

        service.applyAutomatically(job);

        assertThat(moved).allSatisfy(row ->
                assertThat(row.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED));
        assertThat(excluded.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.DISMISSED);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.isReviewedAutomatically()).isTrue();
            assertThat(row.getAutomaticReviewHoldReason()).isNull();
        });
        assertThat(target.getPropertyValue("서식지")).isNull();
        assertThat(target.getPropertyValue("생태", "서식지")).isEqualTo("남부");
        assertThat(target.getPropertyValue("생태", "특징")).isEqualTo("추위에 강함");
        assertThat(target.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("제외가 없는 자동 최종 저장도 원문보다 비교 순서를 먼저 따라 이동 후 변경을 반영한다")
    void finalAutomaticProjectionUsesComparisonOrderWithoutExclusion() {
        WorldSetting target = existing("설인", "서식지", "북부");
        rootMoveThenUpdate(target);

        service.applyAutomatically(job);

        assertThat(rows).allSatisfy(row ->
                assertThat(row.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED));
        assertThat(target.getPropertyValue("생태", "서식지")).isEqualTo("남부");
        assertThat(target.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("비교 순서를 복원해도 이동할 실제 루트 값이 바뀌었으면 변경과 제외를 전부 보류한다")
    void comparisonOrderCannotBypassAStaleRootMove() {
        WorldSetting target = existing("설인", "서식지", "작가가 정한 동부");
        rootMoveThenUpdate(target);
        var excluded = candidate("설인", "서식지", "남부", "남부",
                WorldSettingSuggestedOperation.EXCLUDE, null, target);
        setScope(excluded, "생태");
        setEvidenceOffset(excluded, 5);

        service.applyAutomatically(job);

        assertThat(rows).allSatisfy(row -> assertThat(row.isPendingReview()).isTrue());
        assertThat(target.getPropertyValue("서식지")).isEqualTo("작가가 정한 동부");
        assertThat(target.getPropertyValue("생태", "서식지")).isNull();
        assertThat(target.getVersion()).isZero();
    }

    @Test
    @DisplayName("예상하지 못한 DB 저장 오류는 숨기지 않아 회차 완료 트랜잭션 전체가 롤백된다")
    void persistenceFailurePropagates() {
        candidate("설인", "서식지", "북부", null, WorldSettingSuggestedOperation.ADD, null, null);
        doThrow(new DataIntegrityViolationException("synthetic failure")).when(settings).saveAndFlush(any());
        assertThatThrownBy(() -> service.applyAutomatically(job)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(rows.getFirst().isPendingReview()).isTrue();
    }

    @Test
    @DisplayName("기존 루트 속성 이동과 새 하위 속성을 같은 검증과 한 번의 버전 증가로 자동 반영한다")
    void movesRootPropertyWithNewScopedPropertyAtomically() {
        WorldSetting target = existing("설인", "서식지", "북부");
        var candidate = candidate("설인", "특징", "추위에 강함", null,
                WorldSettingSuggestedOperation.ADD, null, target);
        ReflectionTestUtils.setField(candidate, "proposedScopeName", "생태");
        ReflectionTestUtils.setField(candidate.getComparisonDecision(), "proposedScopeName", "생태");
        ReflectionTestUtils.setField(candidate.getComparisonDecision(), "existingRootPropertyMoveSnapshotsJson",
                json.arrayNode().add(json.objectNode().put("settingName", "서식지").put("beforeValue", "북부")));
        service.applyAutomatically(job);
        assertThat(candidate.isReviewedAutomatically()).isTrue();
        assertThat(target.hasProperty(null, "서식지")).isFalse();
        assertThat(target.getPropertyValue("생태", "서식지")).isEqualTo("북부");
        assertThat(target.getPropertyValue("생태", "특징")).isEqualTo("추위에 강함");
        assertThat(target.getVersion()).isEqualTo(1);
        assertThat(candidate.getComparisonDecision().getRootPropertyMovesAppliedWorldSettingVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("사용자가 취소한 루트 속성 이동은 자동 반영으로 다시 실행하지 않는다")
    void neverRestoresHumanDisabledRootMove() {
        WorldSetting target = existing("설인", "서식지", "북부");
        var candidate = candidate("설인", "특징", "추위에 강함", null,
                WorldSettingSuggestedOperation.ADD, null, target);
        ReflectionTestUtils.setField(candidate.getComparisonDecision(), "existingRootPropertyMoveSnapshotsJson",
                json.arrayNode().add(json.objectNode().put("settingName", "서식지").put("beforeValue", "북부")));
        candidate.getComparisonDecision().disableRootPropertyMoves();
        service.applyAutomatically(job);
        assertThat(candidate.isPendingReview()).isTrue();
        assertThat(target.getProperties()).containsExactly(new WorldSetting.Property(null, "서식지", "북부"));
        assertThat(target.getVersion()).isZero();
    }

    @Test
    @DisplayName("직접 검토 모드는 자동 반영하지 않는다")
    void manualReviewNeverWrites() {
        ReflectionTestUtils.setField(job, "reviewMode", AnalysisReviewMode.MANUAL);
        service.applyAutomatically(job);
        verify(candidates, never()).findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(any());
        verifyNoInteractions(settings, sources, runState);
    }

    private WorldSetting existing(String name, String property, String value) {
        WorldSetting target = WorldSetting.create(work, WorldSettingCategory.RACE, name, property, value);
        ReflectionTestUtils.setField(target, "id", UUID.randomUUID());
        actual.put(target.getNormalizedSubjectName(), target);
        return target;
    }

    private WorldSettingCandidate candidate(String subject, String property, String value, String before,
            WorldSettingSuggestedOperation operation, String provisional, WorldSetting target) {
        var candidate = WorldSettingCandidate.create(work, episode, job, WorldSettingCategory.RACE,
                subject, property, value, json.arrayNode().add(json.objectNode().put("quote", value)
                        .put("startOffset", rows.size() * 10)), BigDecimal.ONE, null);
        ReflectionTestUtils.setField(candidate, "id", UUID.randomUUID());
        String key = target == null ? provisional == null ? "provisional-world:" + candidate.getId() : provisional
                : "world:" + target.getId();
        var actualIds = target == null ? json.arrayNode() : json.arrayNode().add(target.getId().toString());
        var provisionalIds = target == null ? json.arrayNode().add(key) : json.arrayNode();
        var type = target == null ? WorldSettingSubjectResolutionType.NEW : WorldSettingSubjectResolutionType.EXISTING;
        candidate.resolveOrderedSubject(type, key, subject, actualIds, provisionalIds);
        var batch = WorldSettingComparisonBatch.createOrdered(work, episode, job, WorldSettingCategory.RACE,
                null, type, key, subject, actualIds, provisionalIds, 1);
        ReflectionTestUtils.setField(batch, "id", UUID.randomUUID());
        candidate.startComparison(batch, "C1");
        var decision = WorldSettingComparisonDecision.create(batch, "D1", subject, target, null,
                target == null ? null : property, WorldSettingConsolidationStatus.SINGLE, operation, null,
                null, property, before, value, "합성 원문 근거", null);
        ReflectionTestUtils.setField(decision, "id", UUID.randomUUID());
        long priorChanges = rows.stream().filter(row -> key.equals(row.getCanonicalSubjectKey()))
                .filter(row -> Set.of(WorldSettingSuggestedOperation.ADD, WorldSettingSuggestedOperation.UPDATE,
                        WorldSettingSuggestedOperation.MERGE).contains(row.getSuggestedOperation()))
                .map(row -> row.getComparisonDecision().getId()).distinct().count();
        decision.bindOrderedTarget(target == null ? key : null, priorChanges);
        candidate.completeComparison(decision, LocalDateTime.now());
        rows.add(candidate);
        sourceRows.add(WorldSettingComparisonDecisionSource.create(batch, decision, candidate, "C1", 0));
        return candidate;
    }

    private void shareDecision(WorldSettingCandidate first, WorldSettingCandidate second) {
        sourceRows.removeIf(source -> source.getCandidate() == second);
        String sourceRef = "C" + (rows.indexOf(second) + 1);
        ReflectionTestUtils.setField(second, "comparisonBatch", first.getComparisonBatch());
        ReflectionTestUtils.setField(second, "comparisonCandidateRef", sourceRef);
        ReflectionTestUtils.setField(second, "comparisonDecision", first.getComparisonDecision());
        ReflectionTestUtils.setField(second, "proposedSettingName", first.getProposedSettingName());
        ReflectionTestUtils.setField(first.getComparisonDecision(), "consolidationStatus", WorldSettingConsolidationStatus.MERGED);
        ReflectionTestUtils.setField(first, "consolidationStatus", WorldSettingConsolidationStatus.MERGED);
        ReflectionTestUtils.setField(second, "consolidationStatus", WorldSettingConsolidationStatus.MERGED);
        sourceRows.add(WorldSettingComparisonDecisionSource.create(first.getComparisonBatch(),
                first.getComparisonDecision(), second, sourceRef, 1));
    }

    private void useSameComparisonBatch(WorldSettingCandidate first, WorldSettingCandidate second) {
        String sourceRef = "C" + (rows.indexOf(second) + 1);
        ReflectionTestUtils.setField(second, "comparisonBatch", first.getComparisonBatch());
        ReflectionTestUtils.setField(second, "comparisonCandidateRef", sourceRef);
        ReflectionTestUtils.setField(second.getComparisonDecision(), "comparisonBatch", first.getComparisonBatch());
        ReflectionTestUtils.setField(second.getComparisonDecision(), "decisionRef", "D" + (rows.indexOf(second) + 1));
        ReflectionTestUtils.setField(second.getComparisonDecision(), "baseWorldSettingVersion",
                first.getComparisonDecision().getBaseWorldSettingVersion());
        sourceRows.removeIf(source -> source.getCandidate() == second);
        sourceRows.add(WorldSettingComparisonDecisionSource.create(first.getComparisonBatch(),
                second.getComparisonDecision(), second, sourceRef, 0));
    }

    private void setEvidenceOffset(WorldSettingCandidate candidate, int offset) {
        ReflectionTestUtils.setField(candidate, "evidenceSpans",
                json.arrayNode().add(json.objectNode().put("startOffset", offset)));
    }

    private List<WorldSettingCandidate> rootMoveThenUpdate(WorldSetting target) {
        var moved = candidate("설인", "특징", "추위에 강함", null,
                WorldSettingSuggestedOperation.ADD, null, target);
        setScope(moved, "생태");
        ReflectionTestUtils.setField(moved, "matchedPropertyName", null);
        ReflectionTestUtils.setField(moved, "matchedScopeName", null);
        ReflectionTestUtils.setField(moved.getComparisonDecision(), "matchedPropertyName", null);
        ReflectionTestUtils.setField(moved.getComparisonDecision(), "matchedScopeName", null);
        ReflectionTestUtils.setField(moved.getComparisonDecision(), "existingRootPropertyMoveSnapshotsJson",
                json.arrayNode().add(json.objectNode().put("settingName", "서식지").put("beforeValue", "북부")));
        var updated = candidate("설인", "서식지", "남부", "북부",
                WorldSettingSuggestedOperation.UPDATE, null, target);
        setScope(updated, "생태");
        setEvidenceOffset(moved, 30);
        setEvidenceOffset(updated, 10);
        return List.of(moved, updated);
    }

    private void setScope(WorldSettingCandidate candidate, String scope) {
        ReflectionTestUtils.setField(candidate, "scopeName", scope);
        ReflectionTestUtils.setField(candidate, "matchedScopeName", scope);
        ReflectionTestUtils.setField(candidate, "proposedScopeName", scope);
        ReflectionTestUtils.setField(candidate.getComparisonBatch(), "rawScopeName", scope);
        ReflectionTestUtils.setField(candidate.getComparisonDecision(), "matchedScopeName", scope);
        ReflectionTestUtils.setField(candidate.getComparisonDecision(), "proposedScopeName", scope);
    }
}
