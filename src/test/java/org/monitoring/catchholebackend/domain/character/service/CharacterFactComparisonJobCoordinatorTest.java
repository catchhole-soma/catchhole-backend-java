package org.monitoring.catchholebackend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("캐릭터 Fact 그룹 Job 인계 조정 테스트")
class CharacterFactComparisonJobCoordinatorTest {

    @Mock
    private UploadBatchRepository uploadBatchRepository;
    @Mock
    private AnalysisJobRepository analysisJobRepository;
    @Mock
    private SettingCandidateRepository settingCandidateRepository;
    @Mock
    private AiTokenService aiTokenService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CharacterFactComparisonJobCoordinator coordinator;
    private Work work;
    private UploadBatch batch;
    private UUID batchId;

    @BeforeEach
    void setUp() {
        coordinator = new CharacterFactComparisonJobCoordinator(
                uploadBatchRepository,
                analysisJobRepository,
                settingCandidateRepository,
                aiTokenService
        );
        Member member = Member.register("handoff@example.com", "password", "01012345678", "작가");
        work = Work.create(member, "인계 테스트", WorkGenre.FANTASY, "테스트");
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        batch = org.mockito.Mockito.mock(UploadBatch.class);
        batchId = UUID.randomUUID();
    }

    @Test
    @DisplayName("순차 회차의 후보는 batch 입력 barrier와 legacy 숨김 Job에 넘기지 않는다")
    void orderedCandidatesNeverEnterLegacyGroupHandoff() {
        AnalysisJob ordered = sourceJob();
        ReflectionTestUtils.setField(ordered, "analysisMode",
                org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode.ORDERED_PROVISIONAL);
        SettingCandidate provisional = candidate(ordered, "세룸", "status.부상", true);
        ReflectionTestUtils.setField(provisional, "provisionalSubjectKey", "provisional-character:" + UUID.randomUUID());

        coordinator.handoffIfInputComplete(ordered);
        coordinator.enqueueIfNeeded(1L, provisional);

        assertThat(coordinator.scopeRefs(List.of(provisional))).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(uploadBatchRepository, analysisJobRepository,
                settingCandidateRepository, aiTokenService);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ordered.updateCheckpointStage(
                AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_HANDED_OFF))
                .isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class);
        assertThat(ordered.hasHandedOffCharacterComparisons()).isFalse();
    }

    @Test
    @DisplayName("모든 회차가 후보 게시를 인계한 뒤에만 하나의 그룹 Job을 생성한다")
    void waitsForAllSourceJobsBeforeCreatingOneGroupJob() {
        when(batch.getId()).thenReturn(batchId);
        when(uploadBatchRepository.findByIdForUpdate(batchId)).thenReturn(Optional.of(batch));
        AnalysisJob firstJob = sourceJob();
        AnalysisJob secondJob = sourceJob();
        firstJob.updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_HANDED_OFF);
        secondJob.updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_CANDIDATES_SAVED);
        SettingCandidate first = candidate(firstJob, "비요른 얘델", "status.의식_상실", true);
        SettingCandidate second = candidate(secondJob, "  비요른   얘델  ", "status.의식_상실", false);
        when(analysisJobRepository.findAllByBatchIdAndJobTypeOrderByCreatedAtAsc(
                batchId,
                AnalysisJobType.SETTING_EXTRACTION
        )).thenReturn(List.of(firstJob, secondJob));
        when(settingCandidateRepository.findAllPendingInBatchForUpdate(
                work.getId(),
                batchId,
                SettingCandidateReviewStatus.PENDING_REVIEW
        )).thenReturn(List.of(first, second));
        when(analysisJobRepository.findAllActiveComparisonJobs(
                eq(batchId),
                eq(AnalysisJobType.CHARACTER_FACT_COMPARISON),
                eq(List.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.RUNNING))
        )).thenReturn(List.of());

        coordinator.handoffIfInputComplete(firstJob);

        verify(analysisJobRepository, never()).save(any(AnalysisJob.class));

        secondJob.updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_HANDED_OFF);
        coordinator.handoffIfInputComplete(secondJob);

        ArgumentCaptor<AnalysisJob> captor = ArgumentCaptor.forClass(AnalysisJob.class);
        verify(analysisJobRepository).save(captor.capture());
        AnalysisJob comparisonJob = captor.getValue();
        assertThat(comparisonJob.getJobType()).isEqualTo(AnalysisJobType.CHARACTER_FACT_COMPARISON);
        assertThat(comparisonJob.getSettingCandidate()).isSameAs(first);
        assertThat(comparisonJob.getCharacterComparisonInputHash()).matches("[0-9a-f]{64}");
        assertThat(coordinator.inputHash(List.of(first, second)))
                .isEqualTo(comparisonJob.getCharacterComparisonInputHash());
    }

    @Test
    @DisplayName("JSON 객체 필드 순서가 달라도 같은 입력 hash를 만든다")
    void inputHashCanonicalizesJsonObjectOrder() {
        AnalysisJob sourceJob = sourceJob();
        SettingCandidate candidate = candidate(sourceJob, "수아", "profile.eye_color", true);
        ReflectionTestUtils.setField(candidate, "valueJson", objectMapper.createObjectNode()
                .put("name", "눈 색깔")
                .put("value", "갈색"));
        String firstHash = coordinator.inputHash(List.of(candidate));

        ReflectionTestUtils.setField(candidate, "valueJson", objectMapper.createObjectNode()
                .put("value", "갈색")
                .put("name", "눈 색깔"));

        assertThat(coordinator.inputHash(List.of(candidate))).isEqualTo(firstHash);
    }

    @Test
    @DisplayName("구 Worker의 비교 완료 checkpoint를 신규 그룹 인계로 해석하지 않는다")
    void legacyComparisonFinishedDoesNotSignalGroupHandoff() {
        AnalysisJob sourceJob = sourceJob();

        sourceJob.updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED);

        assertThat(sourceJob.hasHandedOffCharacterComparisons()).isFalse();
    }

    @Test
    @DisplayName("구 Worker가 완료한 회차는 후보 게시 barrier를 막지 않는다")
    void legacySucceededSourceDoesNotBlockGroupHandoff() {
        when(batch.getId()).thenReturn(batchId);
        when(uploadBatchRepository.findByIdForUpdate(batchId)).thenReturn(Optional.of(batch));
        AnalysisJob legacyJob = sourceJob();
        legacyJob.updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED);
        legacyJob.succeed(null, 0, 0);
        AnalysisJob currentJob = sourceJob();
        currentJob.updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_HANDED_OFF);
        SettingCandidate candidate = candidate(currentJob, "수아", "profile.species", true);
        when(analysisJobRepository.findAllByBatchIdAndJobTypeOrderByCreatedAtAsc(
                batchId,
                AnalysisJobType.SETTING_EXTRACTION
        )).thenReturn(List.of(legacyJob, currentJob));
        when(settingCandidateRepository.findAllPendingInBatchForUpdate(
                work.getId(),
                batchId,
                SettingCandidateReviewStatus.PENDING_REVIEW
        )).thenReturn(List.of(candidate));
        when(analysisJobRepository.findAllActiveComparisonJobs(
                batchId,
                AnalysisJobType.CHARACTER_FACT_COMPARISON,
                List.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.RUNNING)
        )).thenReturn(List.of());

        coordinator.handoffIfInputComplete(currentJob);

        verify(analysisJobRepository).save(any(AnalysisJob.class));
    }

    @Test
    @DisplayName("원문 재시도로 입력이 다시 열리면 남은 그룹 후보의 비교를 무효화한다")
    void reopenedInputInvalidatesRemainingScopeCandidates() {
        when(batch.getId()).thenReturn(batchId);
        AnalysisJob sourceJob = sourceJob();
        SettingCandidate replaced = candidate(sourceJob, "수아", "status.부상", true);
        SettingCandidate remaining = candidate(sourceJob, "수아", "profile.species", true);
        ReflectionTestUtils.setField(remaining, "comparisonStatus", CharacterFactComparisonStatus.COMPLETED);
        when(settingCandidateRepository.findAllPendingInBatchForUpdate(
                work.getId(),
                batchId,
                SettingCandidateReviewStatus.PENDING_REVIEW
        )).thenReturn(List.of(replaced, remaining));

        coordinator.invalidateReopenedInputScopes(
                coordinator.scopeRefs(List.of(replaced)),
                Set.of(replaced.getId())
        );

        assertThat(remaining.getComparisonStatus())
                .isEqualTo(CharacterFactComparisonStatus.RECOMPARISON_REQUIRED);
    }

    private AnalysisJob sourceJob() {
        AnalysisJob job = AnalysisJob.create(work, batch, null, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(job, "id", UUID.randomUUID());
        return job;
    }

    private SettingCandidate candidate(
            AnalysisJob sourceJob,
            String entityName,
            String attributeName,
            boolean active
    ) {
        SettingCandidate candidate = SettingCandidate.create(
                work,
                null,
                UUID.randomUUID(),
                sourceJob,
                SettingEntityType.CHARACTER,
                entityName,
                entityName.trim(),
                null,
                SettingCandidateMatchStatus.UNRESOLVED,
                attributeName,
                active ? "의식을 잃음" : "의식을 되찾음",
                SettingValueType.JSON,
                objectMapper.createObjectNode().put("name", "의식 상실").put("active", active),
                objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                        .put("quote", active ? "의식을 잃었다." : "의식을 되찾았다.")
                        .put("startOffset", active ? 10 : 20)
                        .put("endOffset", active ? 18 : 30)),
                new BigDecimal("0.9000"),
                objectMapper.createObjectNode()
        );
        ReflectionTestUtils.setField(candidate, "id", UUID.randomUUID());
        return candidate;
    }
}
