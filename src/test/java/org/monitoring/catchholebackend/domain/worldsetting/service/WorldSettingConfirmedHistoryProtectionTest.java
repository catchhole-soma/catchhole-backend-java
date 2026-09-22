package org.monitoring.catchholebackend.domain.worldsetting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisCandidateSourceGuard;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeSourcePurgeRequestRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldimage.service.AutomaticImageService;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateConfirmRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateGroupConfirmRequest;
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
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingConsolidationStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("개별 직접 검토 재분석 세계관의 현재 설정 보호")
class WorldSettingConfirmedHistoryProtectionTest {
    private final WorldSettingCandidateRepository candidates = mock(WorldSettingCandidateRepository.class);
    private final WorldSettingComparisonDecisionSourceRepository sources = mock(WorldSettingComparisonDecisionSourceRepository.class);
    private final WorldSettingRepository settings = mock(WorldSettingRepository.class);
    private final WorkRepository works = mock(WorkRepository.class);
    private final UploadBatchRepository uploads = mock(UploadBatchRepository.class);
    private final AutomaticImageService images = mock(AutomaticImageService.class);
    private final WorldSettingMapper mapper = mock(WorldSettingMapper.class);
    private final EpisodeSourcePurgeRequestRepository purges = mock(EpisodeSourcePurgeRequestRepository.class);
    private final WorldSettingCandidateServiceImpl service = new WorldSettingCandidateServiceImpl(
            works, mock(AnalysisRunStateService.class), uploads, mock(AnalysisJobRepository.class), settings,
            images, candidates, sources, mapper, mock(AiTokenService.class),
            new WorldSettingAnalysisConfirmation(candidates, new AnalysisCandidateSourceGuard(purges)));
    private final List<WorldSettingCandidate> rows = new ArrayList<>();
    private final List<WorldSettingCandidate> history = new ArrayList<>();
    private Work work;
    private UploadBatch upload;
    private UUID uploadId;
    private WorldSetting current;

    @BeforeEach
    void setup() {
        work = Work.create(Member.register("history-world@example.com", "pass", "01012345678", "작가"),
                "작품", WorkGenre.FANTASY, "설명");
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        upload = mock(UploadBatch.class);
        uploadId = UUID.randomUUID();
        when(upload.getId()).thenReturn(uploadId);
        current = WorldSetting.create(work, WorldSettingCategory.RACE, "설인", "서식지", "북부");
        ReflectionTestUtils.setField(current, "id", UUID.randomUUID());
        when(works.getOwnedWorkForUpdate(work.getId(), 1L)).thenReturn(work);
        when(uploads.findByIdAndWorkId(upload.getId(), work.getId())).thenReturn(Optional.of(upload));
        when(settings.findByIdentityForUpdate(eq(work.getId()), eq(WorldSettingCategory.RACE), any()))
                .thenAnswer(invocation -> Optional.ofNullable(current));
        when(settings.findByIdAndWorkIdForUpdate(any(), eq(work.getId())))
                .thenAnswer(invocation -> Optional.ofNullable(current));
        when(candidates.findByIdAndWorkIdForUpdate(any(), eq(work.getId())))
                .thenAnswer(invocation -> rows.stream().filter(row -> row.getId().equals(invocation.getArgument(0))).findFirst());
        when(candidates.findAllByIdsAndBatchForUpdate(eq(work.getId()), eq(uploadId), anySet()))
                .thenAnswer(invocation -> rows.stream().filter(row -> invocation.<Set<UUID>>getArgument(2).contains(row.getId())).toList());
        when(candidates.findAllByTargetWorldSettingIdAndReviewStatusOrderByReviewedAtDescCreatedAtDescIdDesc(
                any(), eq(WorldSettingReviewStatus.CONFIRMED))).thenAnswer(invocation -> List.copyOf(history));
    }

    @ParameterizedTest
    @ValueSource(strings = {"later", "same", "manual", "unknown", "unknown-source", "unmatched-value"})
    @DisplayName("후행·같은 회차·수동·출처 불명 설정은 확정 이력만 추가한다")
    void preservesCurrentWithStoredEvidence(String reason) {
        var row = candidate(10, null, "서식지", "남부", "북부", WorldSettingOperation.UPDATE);
        if (!reason.equals("unknown")) {
            var prior = confirmed(reason.equals("later") ? 20 : reason.equals("same") ? 10 : 5,
                    null, "서식지", reason.equals("unmatched-value") ? "동부" : "북부");
            if (reason.equals("unknown-source")) ReflectionTestUtils.setField(prior, "sourceEpisode", null);
        }
        if (reason.equals("manual")) current.protectManualProperty(null, "서식지");

        var result = service.confirmCandidate(1L, work.getId(), row.getId(), request(row));

        assertThat(result.recomparisonRequired()).isFalse();
        assertThat(row.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED);
        assertThat(row.isHistoryOnly()).isTrue();
        assertThat(current.getPropertyValue("서식지")).isEqualTo("북부");
        assertThat(current.getVersion()).isZero();
        verify(images, never()).refreshWorldSettingImages(any());
    }

    @Test
    @DisplayName("앞 회차 확정 근거만 있는 경로는 현재값에 반영한다")
    void appliesNewerEvidence() {
        confirmed(5, null, "서식지", "북부");
        var row = candidate(10, null, "서식지", "남부", "북부", WorldSettingOperation.UPDATE);
        assertThat(service.confirmCandidate(1L, work.getId(), row.getId(), request(row)).recomparisonRequired()).isFalse();
        assertThat(row.isHistoryOnly()).isFalse();
        assertThat(current.getPropertyValue("서식지")).isEqualTo("남부");
        verify(images).refreshWorldSettingImages(List.of(current));
    }

    @Test
    @DisplayName("현재 대상이 없는 신규 항목은 확정과 함께 생성한다")
    void createsNewTarget() {
        current = null;
        var row = candidate(10, null, "서식지", "남부", null, WorldSettingOperation.ADD);
        var created = WorldSetting.create(work, WorldSettingCategory.RACE, "설인", "서식지", "남부");
        ReflectionTestUtils.setField(created, "id", UUID.randomUUID());
        when(mapper.toEntity(eq(work), any(WorldSettingCandidateConfirmRequest.class))).thenReturn(created);
        when(settings.saveAndFlush(created)).thenReturn(created);

        assertThat(service.confirmCandidate(1L, work.getId(), row.getId(), request(row)).recomparisonRequired()).isFalse();

        assertThat(row.isHistoryOnly()).isFalse();
        assertThat(row.getTargetWorldSetting()).isSameAs(created);
        verify(images).refreshWorldSettingImages(List.of(created));
    }

    @Test
    @DisplayName("사용자가 후보값을 편집해도 후행 확정값을 덮어쓰지 않는다")
    void protectsAuthorEditedDecision() {
        confirmed(20, null, "서식지", "북부");
        var row = candidate(10, null, "서식지", "남부", "북부", WorldSettingOperation.UPDATE);
        var request = new WorldSettingCandidateConfirmRequest(WorldSettingOperation.UPDATE,
                WorldSettingCategory.RACE, "설인", null, "서식지", "작가가 고친 남부", null, null);
        service.confirmCandidate(1L, work.getId(), row.getId(), request);
        assertThat(row.isHistoryOnly()).isTrue();
        assertThat(row.getFinalValue()).isEqualTo("작가가 고친 남부");
        assertThat(current.getPropertyValue("서식지")).isEqualTo("북부");
    }

    @Test
    @DisplayName("삭제 이력이 있는 경로를 신규 제안이 복원하지 않는다")
    void preservesRemovedPath() {
        confirmed(5, null, "특징", "오래된 특징");
        var row = candidate(10, null, "특징", "복원 제안", null, WorldSettingOperation.ADD);
        service.confirmCandidate(1L, work.getId(), row.getId(), request(row));
        assertThat(row.isHistoryOnly()).isTrue();
        assertThat(current.getPropertyValue("특징")).isNull();
        assertThat(current.getVersion()).isZero();
    }

    @Test
    @DisplayName("그룹의 후행 설정은 보존하고 독립적인 신규 경로만 반영한다")
    void groupAppliesOnlyIndependentNewPath() {
        confirmed(20, null, "서식지", "북부");
        var old = candidate(10, null, "서식지", "남부", "북부", WorldSettingOperation.UPDATE);
        var added = candidate(10, null, "특징", "추위에 강함", null, WorldSettingOperation.ADD);
        var result = service.confirmCandidateGroup(1L, work.getId(), group(old, added));
        assertThat(result.recomparisonRequired()).isFalse();
        assertThat(old.isHistoryOnly()).isTrue();
        assertThat(added.isHistoryOnly()).isFalse();
        assertThat(current.getPropertyValue("서식지")).isEqualTo("북부");
        assertThat(current.getPropertyValue("특징")).isEqualTo("추위에 강함");
    }

    @Test
    @DisplayName("그룹 전체가 이력 전용이면 현재값 버전과 이미지를 갱신하지 않는다")
    void historyOnlyGroupDoesNotWriteCurrentTarget() {
        confirmed(20, null, "서식지", "북부");
        var row = candidate(10, null, "서식지", "남부", "북부", WorldSettingOperation.UPDATE);

        assertThat(service.confirmCandidateGroup(1L, work.getId(), group(row)).recomparisonRequired()).isFalse();

        assertThat(row.isHistoryOnly()).isTrue();
        assertThat(current.getVersion()).isZero();
        verify(settings, never()).flush();
        verify(images, never()).refreshWorldSettingImages(any());
    }

    @Test
    @DisplayName("현재값 보존 대상이라도 비교 후 원래 값이 바뀌면 기존 stale 검증을 유지한다")
    void staleComparisonStillRequiresComparison() {
        confirmed(20, null, "서식지", "북부");
        var row = candidate(10, null, "서식지", "남부", "바뀌기 전 북부", WorldSettingOperation.UPDATE);
        assertThat(service.confirmCandidate(1L, work.getId(), row.getId(), request(row)).recomparisonRequired()).isTrue();
        assertThat(row.isHistoryOnly()).isFalse();
        assertThat(row.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.PENDING_REVIEW);
        assertThat(current.getPropertyValue("서식지")).isEqualTo("북부");
    }

    @Test
    @DisplayName("원문 파기가 예약되면 그룹 확정은 현재 설정과 후보를 바꾸지 않는다")
    void rejectsSourcePurgeBeforeGroupMutation() {
        var row = candidate(10, null, "특징", "추위에 강함", null, WorldSettingOperation.ADD);
        when(purges.existsByEpisodeId(row.getSourceEpisode().getId())).thenReturn(true);
        assertThatThrownBy(() -> service.confirmCandidateGroup(1L, work.getId(), group(row)))
                .isInstanceOf(AppException.class);
        assertThat(row.isPendingReview()).isTrue();
        assertThat(current.getPropertyValue("특징")).isNull();
    }

    @Test
    @DisplayName("범위 이동의 원본에 후행 근거가 있으면 신규 경로와 이동을 한 결정으로 보존한다")
    void protectsRootMoveSourceAndItsDecision() {
        confirmed(20, null, "서식지", "북부");
        var row = candidate(10, "외부", "특징", "추위에 강함", null, WorldSettingOperation.ADD);
        var batch = WorldSettingComparisonBatch.create(work, row.getSourceEpisode(), row.getAnalysisJob(),
                WorldSettingCategory.RACE, "외부", WorldSettingSubjectResolutionType.EXISTING,
                "world:" + current.getId(), "설인", JsonNodeFactory.instance.arrayNode().add(current.getId().toString()), 1);
        ReflectionTestUtils.setField(batch, "id", UUID.randomUUID());
        var decision = WorldSettingComparisonDecision.create(batch, "D1", "설인", current, null, null,
                WorldSettingConsolidationStatus.SINGLE, WorldSettingSuggestedOperation.ADD, null,
                "외부", "특징", null, "추위에 강함", "범위 이동",
                List.of(new WorldSettingComparisonDecision.ExistingRootPropertyMoveSnapshot("서식지", "북부")), null);
        ReflectionTestUtils.setField(decision, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(row, "comparisonDecision", decision);
        var source = WorldSettingComparisonDecisionSource.create(batch, decision, row, "C1", 0);
        when(sources.findAllByComparisonDecisionIdIn(anySet())).thenReturn(List.of(source));

        assertThat(service.confirmCandidateGroup(1L, work.getId(), group(row)).recomparisonRequired()).isFalse();

        assertThat(row.isHistoryOnly()).isTrue();
        assertThat(current.getPropertyValue("서식지")).isEqualTo("북부");
        assertThat(current.getPropertyValue("외부", "서식지")).isNull();
        assertThat(current.getPropertyValue("외부", "특징")).isNull();
        assertThat(decision.getRootPropertyMovesAppliedWorldSettingVersion()).isNull();
    }

    private WorldSettingCandidate candidate(int episodeNo, String scope, String property, String value,
            String before, WorldSettingOperation operation) {
        Episode episode = Episode.create(work, null, episodeNo, episodeNo + "화", "local/source", "v1", "a".repeat(64), 100);
        ReflectionTestUtils.setField(episode, "id", UUID.randomUUID());
        AnalysisJob job = AnalysisJob.create(work, upload, episode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(job, "id", UUID.randomUUID());
        WorldSettingCandidate row = WorldSettingCandidate.create(work, episode, job, WorldSettingCategory.RACE,
                "설인", property, value, JsonNodeFactory.instance.arrayNode(), BigDecimal.ONE, null);
        ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(row, "scopeName", scope);
        row.startComparison();
        row.completeComparison(current, WorldSettingConsolidationStatus.SINGLE, operation, scope,
                property, before, value, "원문 근거", null, LocalDateTime.now());
        rows.add(row);
        return row;
    }

    private WorldSettingCandidate confirmed(int episodeNo, String scope, String property, String value) {
        WorldSettingCandidate prior = candidate(episodeNo, scope, property, value, null, WorldSettingOperation.ADD);
        prior.confirm(WorldSettingOperation.ADD, WorldSettingCategory.RACE, "설인", scope, property, value,
                null, work.getMember(), current);
        rows.remove(prior);
        history.add(prior);
        return prior;
    }

    private WorldSettingCandidateConfirmRequest request(WorldSettingCandidate row) {
        return new WorldSettingCandidateConfirmRequest(WorldSettingOperation.valueOf(row.getSuggestedOperation().name()),
                WorldSettingCategory.RACE, "설인", row.getProposedScopeName(), row.getProposedSettingName(),
                row.getProposedValue(), null, null);
    }

    private WorldSettingCandidateGroupConfirmRequest group(WorldSettingCandidate... selected) {
        return new WorldSettingCandidateGroupConfirmRequest(upload.getId(), java.util.Arrays.stream(selected)
                .map(row -> new WorldSettingCandidateGroupConfirmRequest.Decision(row.getId(),
                        WorldSettingOperation.valueOf(row.getSuggestedOperation().name()), WorldSettingCategory.RACE,
                        "설인", row.getProposedScopeName(), row.getProposedSettingName(), row.getProposedValue(), null, null))
                .toList());
    }
}
