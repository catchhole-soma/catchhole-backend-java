package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisJobWorkerMapper;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFactComparisonBatch;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.repository.CharacterFactComparisonBatchRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.service.CharacterFactComparisonJobCoordinator;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonBatchStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingComparisonBatchRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonBatchStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("분석 Worker 캐릭터 비교 묶음 lease 복구 테스트")
class AnalysisJobWorkerServiceCharacterBatchRecoveryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AnalysisJobRepository analysisJobRepository;
    @Mock
    private AnalysisJobLeaseService analysisJobLeaseService;
    @Mock
    private WorkCharacterRepository workCharacterRepository;
    @Mock
    private CharacterSnapshotSourceRepository characterSnapshotSourceRepository;
    @Mock
    private CharacterSettingSchemaRepository characterSettingSchemaRepository;
    @Mock
    private AnalysisJobWorkerMapper analysisJobWorkerMapper;
    @Mock
    private AiTokenService aiTokenService;
    @Mock
    private WorldSettingComparisonBatchRepository worldBatchRepository;
    @Mock
    private CharacterFactComparisonBatchRepository characterBatchRepository;
    @Mock
    private WorldSettingCandidateRepository worldCandidateRepository;
    @Mock
    private SettingCandidateRepository settingCandidateRepository;
    @Mock
    private CharacterFactComparisonJobCoordinator characterComparisonJobCoordinator;

    private AnalysisJobWorkerServiceImpl service;

    @Mock
    private org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobClaimRepository claimRepository;
    @Mock
    private AnalysisRunStateService analysisRunStateService;

    @BeforeEach
    void setUp() {
        service = new AnalysisJobWorkerServiceImpl(
                claimRepository,
                analysisRunStateService,
                List.of(),
                List.of(),
                org.mockito.Mockito.mock(org.monitoring.catchholebackend.domain.work.repository.WorkRepository.class),
                analysisJobRepository,
                analysisJobLeaseService,
                workCharacterRepository,
                characterSnapshotSourceRepository,
                characterSettingSchemaRepository,
                analysisJobWorkerMapper,
                aiTokenService,
                worldBatchRepository,
                characterBatchRepository,
                worldCandidateRepository,
                settingCandidateRepository,
                characterComparisonJobCoordinator
        );
    }

    @Test
    @DisplayName("lease 첫 만료는 묶음을 닫고 후보를 PENDING으로 되돌린다")
    void expiredLeaseClosesBatchAndRecoversCandidates() {
        Member member = Member.register("lease@example.com", "password", "01012345678", "작가");
        Work work = Work.create(member, "lease 작품", WorkGenre.FANTASY, "테스트");
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        WorkCharacter character = WorkCharacter.create(
                work,
                "비요른 얀델",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        ReflectionTestUtils.setField(character, "id", UUID.randomUUID());
        AnalysisJob job = AnalysisJob.create(work, null, null, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(job, "id", UUID.randomUUID());
        job.claim("test-model", "비교 중", LocalDateTime.now().minusMinutes(1));
        SettingCandidate candidate = SettingCandidate.create(
                work,
                null,
                UUID.randomUUID(),
                job,
                SettingEntityType.CHARACTER,
                character.getName(),
                character.getName(),
                character.getId(),
                SettingCandidateMatchStatus.MATCHED,
                "status.부상",
                "부상",
                SettingValueType.JSON,
                objectMapper.createObjectNode().put("value", "부상"),
                objectMapper.createArrayNode(),
                new BigDecimal("0.9000"),
                objectMapper.createObjectNode()
        );
        ReflectionTestUtils.setField(candidate, "id", UUID.randomUUID());
        CharacterFactComparisonBatch batch = CharacterFactComparisonBatch.create(
                work,
                null,
                job,
                character,
                CharacterFactType.STATUS,
                1,
                character.getSnapshotVersion()
        );
        ReflectionTestUtils.setField(batch, "id", UUID.randomUUID());
        candidate.startComparison(batch, "C1");

        when(analysisJobRepository.findExpiredLeaseCandidates(
                eq(AnalysisJobStatus.RUNNING),
                eq(Set.of(AnalysisJobType.SETTING_EXTRACTION)),
                any(LocalDateTime.class),
                any(Pageable.class)
        )).thenReturn(List.of(job));
        when(claimRepository.findClaimableJob(any())).thenReturn(java.util.Optional.empty());
        when(worldBatchRepository.findAllByAnalysisJobIdAndStatusForUpdate(
                job.getId(),
                WorldSettingComparisonBatchStatus.PROCESSING
        )).thenReturn(List.of());
        when(characterBatchRepository.findAllByAnalysisJobIdAndStatusForUpdate(
                job.getId(),
                CharacterFactComparisonBatchStatus.PROCESSING
        )).thenReturn(List.of(batch));
        when(worldCandidateRepository.findAllByAnalysisJobIdAndComparisonStatus(
                job.getId(),
                WorldSettingComparisonStatus.PROCESSING
        )).thenReturn(List.of());
        when(settingCandidateRepository.findAllByAnalysisJobIdAndComparisonStatus(
                job.getId(),
                CharacterFactComparisonStatus.PROCESSING
        )).thenReturn(List.of(candidate));

        assertThat(service.claimAnalysisJob(new WorkerAnalysisJobClaimRequest(
                "test-model",
                "claim",
                Set.of(AnalysisJobType.SETTING_EXTRACTION),
                false
        ))).isEqualTo(Optional.empty());

        assertThat(batch.getStatus()).isEqualTo(CharacterFactComparisonBatchStatus.FAILED);
        assertThat(batch.getFailureCode()).isEqualTo(AnalysisFailureCode.WORKER_LEASE_EXPIRED);
        assertThat(candidate.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PENDING);
        assertThat(candidate.getCharacterComparisonBatch()).isNull();
        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
        verify(aiTokenService).releaseReservedForAnalysisJob(
                job.getId(),
                org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome.WORKER_LEASE_EXPIRED
        );
    }

    @Test
    @DisplayName("오래된 그룹 Job의 lease 만료는 최신 입력 후보 상태를 되돌리지 않는다")
    void expiredStaleGroupJobDoesNotRecoverCurrentCandidates() {
        Member member = Member.register("stale@example.com", "password", "01012345678", "작가");
        Work work = Work.create(member, "stale 작품", WorkGenre.FANTASY, "테스트");
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        SettingCandidate candidate = SettingCandidate.create(
                work,
                null,
                UUID.randomUUID(),
                null,
                SettingEntityType.CHARACTER,
                "수아",
                "수아",
                null,
                SettingCandidateMatchStatus.UNRESOLVED,
                "status.부상",
                "부상",
                SettingValueType.JSON,
                objectMapper.createObjectNode().put("value", "부상"),
                objectMapper.createArrayNode(),
                new BigDecimal("0.9000"),
                objectMapper.createObjectNode()
        );
        ReflectionTestUtils.setField(candidate, "id", UUID.randomUUID());
        candidate.startComparison();
        AnalysisJob staleJob = AnalysisJob.createCharacterFactComparison(candidate, "a".repeat(64));
        ReflectionTestUtils.setField(staleJob, "id", UUID.randomUUID());
        staleJob.claim("test-model", "비교 중", LocalDateTime.now().minusMinutes(1));
        when(analysisJobRepository.findExpiredLeaseCandidates(
                eq(AnalysisJobStatus.RUNNING),
                eq(Set.of(AnalysisJobType.CHARACTER_FACT_COMPARISON)),
                any(LocalDateTime.class),
                any(Pageable.class)
        )).thenReturn(List.of(staleJob));
        when(claimRepository.findClaimableJob(any())).thenReturn(java.util.Optional.empty());
        when(worldBatchRepository.findAllByAnalysisJobIdAndStatusForUpdate(
                staleJob.getId(),
                WorldSettingComparisonBatchStatus.PROCESSING
        )).thenReturn(List.of());
        when(characterBatchRepository.findAllByAnalysisJobIdAndStatusForUpdate(
                staleJob.getId(),
                CharacterFactComparisonBatchStatus.PROCESSING
        )).thenReturn(List.of());
        when(characterComparisonJobCoordinator.lockScopeCandidates(staleJob))
                .thenReturn(List.of(candidate));
        when(characterComparisonJobCoordinator.hasCurrentInput(staleJob, List.of(candidate)))
                .thenReturn(false);

        assertThat(service.claimAnalysisJob(new WorkerAnalysisJobClaimRequest(
                "test-model",
                "claim",
                Set.of(AnalysisJobType.CHARACTER_FACT_COMPARISON),
                false
        ))).isEqualTo(Optional.empty());

        assertThat(candidate.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PROCESSING);
        assertThat(staleJob.getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
    }

    @Test
    @DisplayName("그룹 Job은 비교할 수 없어 격리한 후보를 남기고 정상 종료한다")
    void groupJobCompletesWithQuarantinedCandidate() {
        Member member = Member.register("invalid@example.com", "password", "01012345678", "작가");
        Work work = Work.create(member, "invalid 작품", WorkGenre.FANTASY, "테스트");
        SettingCandidate candidate = SettingCandidate.create(
                work,
                null,
                UUID.randomUUID(),
                null,
                SettingEntityType.CHARACTER,
                "수아",
                "수아",
                null,
                SettingCandidateMatchStatus.UNRESOLVED,
                "unknown",
                "값",
                SettingValueType.STRING,
                objectMapper.createObjectNode().put("value", "값"),
                objectMapper.createArrayNode(),
                new BigDecimal("0.9000"),
                objectMapper.createObjectNode()
        );
        candidate.quarantineInvalidComparison();
        AnalysisJob job = AnalysisJob.createCharacterFactComparison(candidate, "a".repeat(64));
        ReflectionTestUtils.setField(job, "id", UUID.randomUUID());
        UUID leaseToken = job.claim("test-model", "비교 완료", LocalDateTime.now().plusMinutes(1));

        when(analysisJobLeaseService.getRunningAnalysisJobForUpdate(job.getId(), leaseToken))
                .thenReturn(job);
        when(characterComparisonJobCoordinator.lockScopeCandidates(job)).thenReturn(List.of(candidate));
        when(characterComparisonJobCoordinator.hasCurrentInput(job, List.of(candidate))).thenReturn(true);
        when(aiTokenService.getAnalysisJobTokenTotals(job.getId())).thenReturn(new long[]{0L, 0L});

        service.completeAnalysisJob(
                job.getId(),
                leaseToken,
                new WorkerAnalysisJobCompleteRequest("{}", null, null)
        );

        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
        assertThat(candidate.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.NOT_REQUIRED);
    }
}
