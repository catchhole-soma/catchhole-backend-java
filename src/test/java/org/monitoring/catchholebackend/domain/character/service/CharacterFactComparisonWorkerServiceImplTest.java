package org.monitoring.catchholebackend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobLeaseService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.dto.request.WorkerCharacterFactComparisonCompleteRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterFactComparisonContextResponse;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterFactComparisonWorkerMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSettingValueValidator;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateSchemaResolver;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("캐릭터 Fact 2차 비교 Worker Service 단위 테스트")
class CharacterFactComparisonWorkerServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AnalysisJobLeaseService analysisJobLeaseService;
    @Mock
    private SettingCandidateRepository candidateRepository;
    @Mock
    private WorkCharacterRepository characterRepository;
    @Mock
    private CharacterSettingSchemaRepository schemaRepository;
    @Mock
    private CharacterSnapshotSourceRepository snapshotSourceRepository;

    private CharacterFactComparisonWorkerServiceImpl service;
    private Work work;
    private WorkCharacter character;
    private AnalysisJob analysisJob;
    private UUID analysisJobId;
    private UUID leaseToken;

    @BeforeEach
    void setUp() {
        service = new CharacterFactComparisonWorkerServiceImpl(
                analysisJobLeaseService,
                candidateRepository,
                characterRepository,
                schemaRepository,
                snapshotSourceRepository,
                new SettingCandidateSchemaResolver(),
                new CharacterSnapshotAccessor(),
                new CharacterSettingValueValidator(),
                new CharacterFactComparisonWorkerMapper()
        );
        Member member = Member.register("worker@example.com", "password", "01012345678", "작가");
        work = Work.create(member, "비교 작품", WorkGenre.FANTASY, "테스트");
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        character = WorkCharacter.create(
                work,
                "아리아",
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
        analysisJob = AnalysisJob.create(work, null, null, AnalysisJobType.SETTING_EXTRACTION);
        analysisJob.updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_CANDIDATES_SAVED);
        analysisJobId = UUID.randomUUID();
        ReflectionTestUtils.setField(analysisJob, "id", analysisJobId);
        leaseToken = UUID.randomUUID();
        when(analysisJobLeaseService.getRunningAnalysisJobForUpdate(analysisJobId, leaseToken))
                .thenReturn(analysisJob);
        when(snapshotSourceRepository.findAllByWorkCharacterIdOrderByFactTypeAscFactKeyAscSourceOrderAsc(
                character.getId()
        )).thenReturn(List.of());
    }

    @Test
    @DisplayName("독립 STAT 후보 문맥은 같은 타입의 다른 slot을 포함하지 않는다")
    void statContextContainsOnlyExactSlot() {
        ObjectNode stats = objectMapper.createObjectNode();
        stats.set("stats.strength", value(10));
        stats.set("stats.agility", value(20));
        character.replaceCurrentSnapshots(
                null,
                null,
                null,
                stats,
                null,
                null,
                null
        );
        SettingCandidate candidate = prepareCandidate(
                "stats.strength",
                "10",
                SettingValueType.NUMBER,
                value(10),
                schema("stats.strength", null, CharacterFactType.STAT, SettingValueType.NUMBER)
        );

        WorkerCharacterFactComparisonContextResponse context = claimAndGetContext(candidate);

        assertThat(context.contextToken()).matches("[0-9a-f]{64}");
        assertThat(context.snapshotEntries())
                .extracting(WorkerCharacterFactComparisonContextResponse.SnapshotEntry::factKey)
                .containsExactly("stats.strength");
        assertThat(context.snapshotEntries().getFirst().factValue()).isEqualTo("10");
        assertThat(context.candidate().canonicalFactType()).isEqualTo(CharacterFactType.STAT);
    }

    @Test
    @DisplayName("잘못된 후보를 격리하고 다음 정상 후보를 claim한다")
    void claimSkipsInvalidCandidateAndContinuesQueue() {
        SettingCandidate invalid = newCandidate(
                "stats.mental",
                "정신: 37",
                SettingValueType.NUMBER,
                value(37)
        );
        SettingCandidate valid = newCandidate(
                "stats.strength",
                "10",
                SettingValueType.NUMBER,
                value(10)
        );
        when(schemaRepository.findAllActiveForWork(work.getId())).thenReturn(List.of(
                schema("stats.mental", null, CharacterFactType.STAT, SettingValueType.NUMBER),
                schema("stats.strength", null, CharacterFactType.STAT, SettingValueType.NUMBER)
        ));
        when(candidateRepository.findComparisonClaimCandidates(
                eq(analysisJobId),
                eq(SettingCandidateReviewStatus.PENDING_REVIEW),
                eq(CharacterFactComparisonStatus.PENDING),
                any(Pageable.class)
        )).thenReturn(List.of(invalid), List.of(valid));
        when(candidateRepository.findByIdAndWorkIdForUpdate(valid.getId(), work.getId()))
                .thenReturn(Optional.of(valid));
        when(characterRepository.findByIdAndWorkId(character.getId(), work.getId()))
                .thenReturn(Optional.of(character));
        when(characterRepository.findByIdAndWorkIdForUpdate(character.getId(), work.getId()))
                .thenReturn(Optional.of(character));

        var claimed = service.claimNextCharacterFactComparison(analysisJobId, leaseToken);
        var context = service.getCharacterFactComparisonContext(
                analysisJobId,
                valid.getId(),
                leaseToken
        );

        assertThat(claimed).isPresent();
        assertThat(claimed.orElseThrow().candidateId()).isEqualTo(valid.getId());
        assertThat(context.candidate().candidateId()).isEqualTo(valid.getId());
        assertThat(invalid.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.NOT_REQUIRED);
        assertThat(valid.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PROCESSING);
        verify(candidateRepository).flush();
    }

    @Test
    @DisplayName("같은 batch의 앞선 동일 STAT 후보를 미확정 시간순 문맥으로 제공한다")
    void sameSlotPriorCandidateIsIncludedAsChronology() {
        UploadBatch batch = mock(UploadBatch.class);
        UUID batchId = UUID.randomUUID();
        when(batch.getId()).thenReturn(batchId);
        ReflectionTestUtils.setField(analysisJob, "batch", batch);

        CharacterSettingSchema mentalSchema = schema(
                "stats.mental",
                null,
                CharacterFactType.STAT,
                SettingValueType.NUMBER
        );
        SettingCandidate prior = newCandidate(
                "stats.mental",
                "35",
                SettingValueType.NUMBER,
                value(35)
        );
        ReflectionTestUtils.setField(prior, "createdAt", LocalDateTime.of(2026, 8, 1, 0, 0));
        SettingCandidate candidate = prepareCandidate(
                "stats.mental",
                "1",
                SettingValueType.NUMBER,
                value(1),
                mentalSchema
        );
        ReflectionTestUtils.setField(candidate, "evidenceSpans", objectMapper.createArrayNode().add(
                objectMapper.createObjectNode()
                        .put("quote", "정신이 영구적으로 1 상승했다.")
                        .put("startOffset", 20)
                        .put("endOffset", 36)
        ));
        ReflectionTestUtils.setField(candidate, "createdAt", LocalDateTime.of(2026, 8, 2, 0, 0));
        when(candidateRepository.findPendingComparisonChronology(
                work.getId(),
                batchId,
                character.getId(),
                SettingCandidateReviewStatus.PENDING_REVIEW
        )).thenReturn(List.of(candidate, prior));

        WorkerCharacterFactComparisonContextResponse context = claimAndGetContext(candidate);

        assertThat(context.priorCandidates()).hasSize(1);
        assertThat(context.priorCandidates().getFirst().attributeName()).isEqualTo("stats.mental");
        assertThat(context.priorCandidates().getFirst().attributeValue()).isEqualTo("35");
        assertThat(context.priorCandidates().getFirst().comparisonStatus())
                .isEqualTo(CharacterFactComparisonStatus.PENDING);
    }

    @Test
    @DisplayName("잘못된 앞선 다른 slot 후보는 현재 후보 문맥을 막지 않는다")
    void invalidPriorCandidateDoesNotPoisonContext() {
        UploadBatch batch = mock(UploadBatch.class);
        UUID batchId = UUID.randomUUID();
        when(batch.getId()).thenReturn(batchId);
        ReflectionTestUtils.setField(analysisJob, "batch", batch);

        CharacterSettingSchema mentalSchema = schema(
                "stats.mental",
                null,
                CharacterFactType.STAT,
                SettingValueType.NUMBER
        );
        CharacterSettingSchema strengthSchema = schema(
                "stats.strength",
                null,
                CharacterFactType.STAT,
                SettingValueType.NUMBER
        );
        SettingCandidate prior = newCandidate(
                "stats.strength",
                "힘: 10",
                SettingValueType.NUMBER,
                value(10)
        );
        ReflectionTestUtils.setField(prior, "createdAt", LocalDateTime.of(2026, 8, 1, 0, 0));
        SettingCandidate candidate = prepareCandidate(
                "stats.mental",
                "37",
                SettingValueType.NUMBER,
                value(37),
                mentalSchema
        );
        ReflectionTestUtils.setField(candidate, "createdAt", LocalDateTime.of(2026, 8, 2, 0, 0));
        when(schemaRepository.findAllActiveForWork(work.getId()))
                .thenReturn(List.of(mentalSchema, strengthSchema));
        when(candidateRepository.findPendingComparisonChronology(
                work.getId(),
                batchId,
                character.getId(),
                SettingCandidateReviewStatus.PENDING_REVIEW
        )).thenReturn(List.of(candidate, prior));

        WorkerCharacterFactComparisonContextResponse context = claimAndGetContext(candidate);

        assertThat(context.priorCandidates()).isEmpty();
        assertThat(candidate.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PROCESSING);
        assertThat(prior.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PENDING);
    }

    @Test
    @DisplayName("STATUS 문맥은 exact slot 뒤에 최근 생성된 source Fact 순으로 최대 30개를 제공한다")
    void statusContextContainsRelatedStatusesWithExactFirstAndRecentSourcesNext() {
        var statuses = objectMapper.createObjectNode();
        statuses.set("status.target", value("대상"));
        for (int index = 0; index < 31; index++) {
            statuses.set("status.%02d".formatted(index), value(index));
        }
        statuses.set("status.old", value("오래된 상태"));
        statuses.set("status.middle", value("중간 상태"));
        statuses.set("status.new", value("최근 상태"));
        character.replaceCurrentSnapshots(null, null, null, null, null, null, statuses);
        when(snapshotSourceRepository.findAllByWorkCharacterIdOrderByFactTypeAscFactKeyAscSourceOrderAsc(
                character.getId()
        )).thenReturn(List.of(
                snapshotSource("status.old", "오래된 상태", LocalDateTime.of(2026, 1, 1, 0, 0)),
                snapshotSource("status.middle", "중간 상태", LocalDateTime.of(2026, 2, 1, 0, 0)),
                snapshotSource("status.new", "최근 상태", LocalDateTime.of(2026, 3, 1, 0, 0))
        ));
        SettingCandidate candidate = prepareCandidate(
                "status.target",
                "치료됨",
                SettingValueType.JSON,
                value("치료됨"),
                schema("statuses.status", "status.*", CharacterFactType.STATUS, SettingValueType.JSON)
        );

        WorkerCharacterFactComparisonContextResponse context = claimAndGetContext(candidate);

        assertThat(context.snapshotEntries()).hasSize(30);
        assertThat(context.snapshotEntries())
                .extracting(WorkerCharacterFactComparisonContextResponse.SnapshotEntry::factKey)
                .startsWith("status.target", "status.new", "status.middle", "status.old");
    }

    @Test
    @DisplayName("STATUS 문맥 30건에서 제외된 slot을 제거 대상으로 보내면 거절한다")
    void rejectsRemovalOfStatusThatWasNotProvidedInContext() {
        var statuses = objectMapper.createObjectNode();
        statuses.set("status.target", value("대상"));
        for (int index = 0; index < 35; index++) {
            statuses.set("status.%02d".formatted(index), value(index));
        }
        character.replaceCurrentSnapshots(null, null, null, null, null, null, statuses);
        SettingCandidate candidate = prepareCandidate(
                "status.target",
                "치료됨",
                SettingValueType.JSON,
                value("치료됨"),
                schema("statuses.status", "status.*", CharacterFactType.STATUS, SettingValueType.JSON)
        );
        WorkerCharacterFactComparisonContextResponse context = claimAndGetContext(candidate);
        Set<String> providedKeys = context.snapshotEntries().stream()
                .map(WorkerCharacterFactComparisonContextResponse.SnapshotEntry::factKey)
                .collect(java.util.stream.Collectors.toSet());
        String omittedKey = java.util.stream.IntStream.range(0, 35)
                .mapToObj(index -> "status.%02d".formatted(index))
                .filter(key -> !providedKeys.contains(key))
                .findFirst()
                .orElseThrow();

        assertThatThrownBy(() -> service.completeCharacterFactComparison(
                analysisJobId,
                candidate.getId(),
                leaseToken,
                completeRequest(
                        CharacterFactOperation.UPDATE,
                        CharacterFactType.STATUS,
                        "status.target",
                        Map.of("value", "치료됨"),
                        List.of(new WorkerCharacterFactComparisonCompleteRequest.SnapshotEntry(
                                CharacterFactType.STATUS,
                                omittedKey
                        )),
                        CharacterFactTemporalScope.PRESENT,
                        context.contextToken()
                )
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_TARGET_INVALID));
    }

    @Test
    @DisplayName("과거 서술을 현재값 UPDATE로 보내면 신뢰하지 않고 거절한다")
    void rejectsPastUpdate() {
        character.replaceCurrentSnapshots(17, null, null, null, null, null, null);
        SettingCandidate candidate = prepareCandidate(
                "age",
                "18",
                SettingValueType.NUMBER,
                value(18),
                schema("age", null, CharacterFactType.AGE, SettingValueType.NUMBER)
        );
        WorkerCharacterFactComparisonContextResponse context = claimAndGetContext(candidate);

        assertThatThrownBy(() -> service.completeCharacterFactComparison(
                analysisJobId,
                candidate.getId(),
                leaseToken,
                completeRequest(
                        CharacterFactOperation.UPDATE,
                        CharacterFactType.AGE,
                        "age",
                        Map.of("value", 18),
                        List.of(),
                        CharacterFactTemporalScope.PAST,
                        context.contextToken()
                )
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID));
    }

    @Test
    @DisplayName("현재 snapshot에 반영할 제안은 비어 있지 않은 최종 표시값을 함께 보내야 한다")
    void rejectsUpsertWithoutProposedFactValue() {
        SettingCandidate candidate = prepareCandidate(
                "stats.strength",
                "18",
                SettingValueType.NUMBER,
                value(18),
                schema("stats.strength", null, CharacterFactType.STAT, SettingValueType.NUMBER)
        );
        WorkerCharacterFactComparisonContextResponse context = claimAndGetContext(candidate);
        WorkerCharacterFactComparisonCompleteRequest request =
                new WorkerCharacterFactComparisonCompleteRequest(
                        CharacterFactOperation.ADD,
                        null,
                        null,
                        "  ",
                        Map.of("value", 18),
                        List.of(),
                        CharacterFactTemporalScope.PRESENT,
                        "새 현재값",
                        context.contextToken(),
                        Map.of("operation", "ADD")
                );

        assertThatThrownBy(() -> service.completeCharacterFactComparison(
                analysisJobId,
                candidate.getId(),
                leaseToken,
                request
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID));
    }

    @Test
    @DisplayName("NUMBER 비교 제안의 표시값과 구조화 값이 다르면 완료를 거절한다")
    void rejectsMismatchedNumberProposal() {
        SettingCandidate candidate = prepareCandidate(
                "stats.strength",
                "18",
                SettingValueType.NUMBER,
                value(18),
                schema("stats.strength", null, CharacterFactType.STAT, SettingValueType.NUMBER)
        );
        WorkerCharacterFactComparisonContextResponse context = claimAndGetContext(candidate);
        WorkerCharacterFactComparisonCompleteRequest request =
                new WorkerCharacterFactComparisonCompleteRequest(
                        CharacterFactOperation.ADD,
                        null,
                        null,
                        "19",
                        Map.of("value", 18),
                        List.of(),
                        CharacterFactTemporalScope.PRESENT,
                        "새 현재값",
                        context.contextToken(),
                        Map.of("operation", "ADD")
                );

        assertThatThrownBy(() -> service.completeCharacterFactComparison(
                analysisJobId,
                candidate.getId(),
                leaseToken,
                request
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_VALUE_MISMATCH));
    }

    @Test
    @DisplayName("동일한 현재 STATUS의 종료 제안은 값 없이 정확한 slot을 대상으로 완료할 수 있다")
    void completesSameStatusSlotRemoval() {
        var statuses = objectMapper.createObjectNode().set("status.부상", value("부상"));
        character.replaceCurrentSnapshots(null, null, null, null, null, null, statuses);
        SettingCandidate candidate = prepareCandidate(
                "status.부상",
                "부상이 완전히 회복됨",
                SettingValueType.JSON,
                value("회복됨"),
                schema("statuses.status", "status.*", CharacterFactType.STATUS, SettingValueType.JSON)
        );
        WorkerCharacterFactComparisonContextResponse context = claimAndGetContext(candidate);

        service.completeCharacterFactComparison(
                analysisJobId,
                candidate.getId(),
                leaseToken,
                completeRequest(
                        CharacterFactOperation.REMOVE,
                        CharacterFactType.STATUS,
                        "status.부상",
                        null,
                        List.of(),
                        CharacterFactTemporalScope.PRESENT,
                        context.contextToken()
                )
        );

        assertThat(candidate.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.COMPLETED);
        assertThat(candidate.getSuggestedOperation()).isEqualTo(CharacterFactOperation.REMOVE);
        assertThat(candidate.getProposedFactValue()).isNull();
        assertThat(candidate.getProposedValueJson()).isNull();
    }

    @Test
    @DisplayName("STATUS가 아닌 후보의 snapshot 제거 제안은 거절한다")
    void rejectsRemovalFromNonStatusCandidate() {
        var statuses = objectMapper.createObjectNode().set("status.부상", value("부상"));
        character.replaceCurrentSnapshots(17, null, null, null, null, null, statuses);
        SettingCandidate candidate = prepareCandidate(
                "age",
                "18",
                SettingValueType.NUMBER,
                value(18),
                schema("age", null, CharacterFactType.AGE, SettingValueType.NUMBER)
        );
        WorkerCharacterFactComparisonContextResponse context = claimAndGetContext(candidate);

        assertThatThrownBy(() -> service.completeCharacterFactComparison(
                analysisJobId,
                candidate.getId(),
                leaseToken,
                completeRequest(
                        CharacterFactOperation.UPDATE,
                        CharacterFactType.AGE,
                        "age",
                        Map.of("value", 18),
                        List.of(new WorkerCharacterFactComparisonCompleteRequest.SnapshotEntry(
                                CharacterFactType.STATUS,
                                "status.부상"
                        )),
                        CharacterFactTemporalScope.PRESENT,
                        context.contextToken()
                )
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID));
    }

    @Test
    @DisplayName("AGE 비교 뒤 무관한 STAT 변경은 proposal을 stale로 만들지 않는다")
    void unrelatedSnapshotChangeDoesNotInvalidateContext() {
        character.replaceCurrentSnapshots(17, null, null, null, null, null, null);
        SettingCandidate candidate = prepareCandidate(
                "age",
                "18",
                SettingValueType.NUMBER,
                value(18),
                schema("age", null, CharacterFactType.AGE, SettingValueType.NUMBER)
        );
        WorkerCharacterFactComparisonContextResponse context = claimAndGetContext(candidate);
        character.replaceCurrentSnapshots(
                17,
                null,
                null,
                objectMapper.createObjectNode().set("stats.strength", value(10)),
                null,
                null,
                null
        );

        service.completeCharacterFactComparison(
                analysisJobId,
                candidate.getId(),
                leaseToken,
                completeRequest(
                        CharacterFactOperation.UPDATE,
                        CharacterFactType.AGE,
                        "age",
                        Map.of("value", 18),
                        List.of(),
                        CharacterFactTemporalScope.PRESENT,
                        context.contextToken()
                )
        );

        assertThat(candidate.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.COMPLETED);
        assertThat(candidate.getProposedFactValue()).isEqualTo("18");
    }

    @Test
    @DisplayName("STATUS 관련 문맥이 바뀌면 이전 comparison token을 stale로 거절한다")
    void relatedStatusChangeInvalidatesContext() {
        character.replaceCurrentSnapshots(
                null,
                null,
                null,
                null,
                null,
                null,
                objectMapper.createObjectNode().set("status.부상", value("부상"))
        );
        SettingCandidate candidate = prepareCandidate(
                "status.회복",
                "회복",
                SettingValueType.JSON,
                value("회복"),
                schema("statuses.status", "status.*", CharacterFactType.STATUS, SettingValueType.JSON)
        );
        WorkerCharacterFactComparisonContextResponse context = claimAndGetContext(candidate);
        ObjectNode statuses = objectMapper.createObjectNode();
        statuses.set("status.부상", value("부상"));
        statuses.set("status.출혈", value("출혈"));
        character.replaceCurrentSnapshots(
                null,
                null,
                null,
                null,
                null,
                null,
                statuses
        );

        assertThatThrownBy(() -> service.completeCharacterFactComparison(
                analysisJobId,
                candidate.getId(),
                leaseToken,
                completeRequest(
                        CharacterFactOperation.ADD,
                        null,
                        null,
                        Map.of("value", "회복"),
                        List.of(),
                        CharacterFactTemporalScope.PRESENT,
                        context.contextToken()
                )
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STALE));
    }

    private SettingCandidate prepareCandidate(
            String attributeName,
            String attributeValue,
            SettingValueType valueType,
            com.fasterxml.jackson.databind.JsonNode valueJson,
            CharacterSettingSchema schema
    ) {
        SettingCandidate candidate = newCandidate(
                attributeName,
                attributeValue,
                valueType,
                valueJson
        );
        when(schemaRepository.findAllActiveForWork(work.getId())).thenReturn(List.of(schema));
        when(candidateRepository.findComparisonClaimCandidates(
                eq(analysisJobId),
                eq(SettingCandidateReviewStatus.PENDING_REVIEW),
                eq(CharacterFactComparisonStatus.PENDING),
                any(Pageable.class)
        )).thenReturn(List.of(candidate));
        when(candidateRepository.findByIdAndWorkIdForUpdate(candidate.getId(), work.getId()))
                .thenReturn(Optional.of(candidate));
        when(characterRepository.findByIdAndWorkId(character.getId(), work.getId()))
                .thenReturn(Optional.of(character));
        when(characterRepository.findByIdAndWorkIdForUpdate(character.getId(), work.getId()))
                .thenReturn(Optional.of(character));
        return candidate;
    }

    private SettingCandidate newCandidate(
            String attributeName,
            String attributeValue,
            SettingValueType valueType,
            com.fasterxml.jackson.databind.JsonNode valueJson
    ) {
        SettingCandidate candidate = SettingCandidate.create(
                work,
                null,
                UUID.randomUUID(),
                analysisJob,
                SettingEntityType.CHARACTER,
                character.getName(),
                character.getName(),
                character.getId(),
                SettingCandidateMatchStatus.MATCHED,
                attributeName,
                attributeValue,
                valueType,
                valueJson,
                objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                        .put("quote", "원문 근거")
                        .put("startOffset", 1)
                        .put("endOffset", 5)),
                new BigDecimal("0.9000"),
                objectMapper.createObjectNode()
        );
        ReflectionTestUtils.setField(candidate, "id", UUID.randomUUID());
        return candidate;
    }

    private WorkerCharacterFactComparisonContextResponse claimAndGetContext(SettingCandidate candidate) {
        assertThat(service.claimNextCharacterFactComparison(analysisJobId, leaseToken)).isPresent();
        return service.getCharacterFactComparisonContext(analysisJobId, candidate.getId(), leaseToken);
    }

    private WorkerCharacterFactComparisonCompleteRequest completeRequest(
            CharacterFactOperation operation,
            CharacterFactType targetFactType,
            String targetFactKey,
            Object proposedValue,
            List<WorkerCharacterFactComparisonCompleteRequest.SnapshotEntry> removals,
            CharacterFactTemporalScope temporalScope,
            String token
    ) {
        return new WorkerCharacterFactComparisonCompleteRequest(
                operation,
                targetFactType,
                targetFactKey,
                proposalDisplayValue(proposedValue),
                proposedValue,
                removals,
                temporalScope,
                "비교 판단 이유",
                token,
                Map.of("operation", operation.name())
        );
    }

    private String proposalDisplayValue(Object proposedValue) {
        if (!(proposedValue instanceof Map<?, ?> proposedMap)) {
            return proposedValue == null ? null : proposedValue.toString();
        }
        Object value = proposedMap.get("value");
        return value == null ? null : value.toString();
    }

    private CharacterSettingSchema schema(
            String schemaKey,
            String pattern,
            CharacterFactType factType,
            SettingValueType valueType
    ) {
        return CharacterSettingSchema.create(
                null,
                schemaKey,
                pattern,
                schemaKey,
                factType,
                valueType,
                CharacterSettingValueSemantics.BASE_VALUE,
                CharacterSettingMergePolicy.REPLACE,
                objectMapper.createArrayNode(),
                CharacterSettingSchemaSource.SYSTEM_SEED,
                true
        );
    }

    private CharacterSnapshotSource snapshotSource(
            String factKey,
            String factValue,
            LocalDateTime createdAt
    ) {
        CharacterFact fact = CharacterFact.createManual(
                character,
                CharacterFactType.STATUS,
                factKey,
                factValue,
                value(factValue)
        );
        ReflectionTestUtils.setField(fact, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(fact, "createdAt", createdAt);
        return CharacterSnapshotSource.create(
                character,
                CharacterFactType.STATUS,
                factKey,
                fact,
                0
        );
    }

    private com.fasterxml.jackson.databind.JsonNode value(int value) {
        return objectMapper.createObjectNode().put("value", value);
    }

    private com.fasterxml.jackson.databind.JsonNode value(String value) {
        return objectMapper.createObjectNode().put("value", value);
    }
}
