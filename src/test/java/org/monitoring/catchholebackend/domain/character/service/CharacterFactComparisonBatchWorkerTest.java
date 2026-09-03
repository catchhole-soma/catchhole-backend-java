package org.monitoring.catchholebackend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobLeaseService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.dto.request.WorkerCharacterFactComparisonBatchCompleteRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.WorkerCharacterFactComparisonFailRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterFactComparisonBatchContextResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterFactComparisonBatchPayload;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFactComparisonBatch;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterFactComparisonWorkerMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterFactComparisonDecisionValidator;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSettingValueValidator;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateSchemaResolver;
import org.monitoring.catchholebackend.domain.character.repository.CharacterFactComparisonBatchRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactCanonicalKeyResolution;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonBatchStatus;
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
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("캐릭터 Fact 묶음 비교 Worker 단위 테스트")
class CharacterFactComparisonBatchWorkerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AnalysisJobLeaseService analysisJobLeaseService;
    @Mock
    private SettingCandidateRepository candidateRepository;
    @Mock
    private CharacterFactComparisonBatchRepository batchRepository;
    @Mock
    private WorkCharacterRepository characterRepository;
    @Mock
    private CharacterSettingSchemaRepository schemaRepository;
    @Mock
    private CharacterSnapshotSourceRepository snapshotSourceRepository;

    private CharacterFactComparisonBatchWorker worker;
    private Work work;
    private WorkCharacter character;
    private AnalysisJob analysisJob;
    private UUID analysisJobId;
    private UUID leaseToken;
    private final List<SettingCandidate> candidates = new ArrayList<>();
    private final Map<UUID, CharacterFactComparisonBatch> batches = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        CharacterSettingValueValidator valueValidator = new CharacterSettingValueValidator();
        worker = new CharacterFactComparisonBatchWorker(
                analysisJobLeaseService,
                candidateRepository,
                batchRepository,
                characterRepository,
                schemaRepository,
                snapshotSourceRepository,
                new SettingCandidateSchemaResolver(),
                new CharacterSnapshotAccessor(),
                valueValidator,
                new CharacterFactComparisonDecisionValidator(valueValidator),
                new CharacterFactComparisonWorkerMapper()
        );

        Member member = Member.register("batch-worker@example.com", "password", "01012345678", "작가");
        work = Work.create(member, "묶음 비교 작품", WorkGenre.FANTASY, "테스트");
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        character = WorkCharacter.create(
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
        ObjectNode statuses = objectMapper.createObjectNode();
        statuses.set("status.오른발_부상", value("오른발을 쓰지 못함"));
        statuses.set("status.마비독", value("마비독에 중독됨"));
        character.replaceCurrentSnapshots(null, null, null, null, null, null, statuses);

        analysisJob = AnalysisJob.create(work, null, null, AnalysisJobType.SETTING_EXTRACTION);
        analysisJob.updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_CANDIDATES_SAVED);
        analysisJobId = UUID.randomUUID();
        leaseToken = UUID.randomUUID();
        ReflectionTestUtils.setField(analysisJob, "id", analysisJobId);

        when(analysisJobLeaseService.getRunningAnalysisJobForUpdate(analysisJobId, leaseToken))
                .thenReturn(analysisJob);
        when(schemaRepository.findAllActiveForWork(work.getId())).thenReturn(List.of(statusSchema()));
        when(characterRepository.findByIdAndWorkIdForUpdate(character.getId(), work.getId()))
                .thenReturn(Optional.of(character));
        when(snapshotSourceRepository.findAllByWorkCharacterIdOrderByFactTypeAscFactKeyAscSourceOrderAsc(
                character.getId()
        )).thenReturn(List.of());
        when(batchRepository.existsByAnalysisJobIdAndMatchedCharacterIdAndCanonicalFactTypeAndStatus(
                eq(analysisJobId),
                eq(character.getId()),
                eq(CharacterFactType.STATUS),
                eq(CharacterFactComparisonBatchStatus.PROCESSING)
        )).thenAnswer(invocation -> batches.values().stream()
                .anyMatch(CharacterFactComparisonBatch::isProcessing));
        when(batchRepository.saveAndFlush(any(CharacterFactComparisonBatch.class)))
                .thenAnswer(invocation -> {
                    CharacterFactComparisonBatch batch = invocation.getArgument(0);
                    ReflectionTestUtils.setField(batch, "id", UUID.randomUUID());
                    batches.put(batch.getId(), batch);
                    return batch;
                });
        when(batchRepository.findByIdAndAnalysisJobIdForUpdate(any(UUID.class), eq(analysisJobId)))
                .thenAnswer(invocation -> Optional.ofNullable(batches.get(invocation.getArgument(0))));
        when(candidateRepository.findComparisonClaimCandidates(
                eq(analysisJobId),
                eq(SettingCandidateReviewStatus.PENDING_REVIEW),
                eq(CharacterFactComparisonStatus.PENDING),
                any(Pageable.class)
        )).thenAnswer(invocation -> pendingCandidates());
        when(candidateRepository.findComparisonGroupCandidatesForUpdate(
                eq(analysisJobId),
                any(UUID.class),
                eq(SettingCandidateReviewStatus.PENDING_REVIEW),
                eq(CharacterFactComparisonStatus.PENDING)
        )).thenAnswer(invocation -> pendingCandidates().stream()
                .filter(candidate -> candidate.getMatchedCharacterId().equals(invocation.getArgument(1)))
                .toList());
        when(candidateRepository.findAllByCharacterComparisonBatchIdForUpdate(any(UUID.class)))
                .thenAnswer(invocation -> candidates.stream()
                        .filter(candidate -> candidate.getCharacterComparisonBatch() != null)
                        .filter(candidate -> candidate.getCharacterComparisonBatch().getId()
                                .equals(invocation.getArgument(0)))
                        .toList());
        when(candidateRepository.findCompletedComparisonCandidates(
                eq(analysisJobId),
                eq(character.getId()),
                eq(SettingCandidateReviewStatus.PENDING_REVIEW),
                eq(CharacterFactComparisonStatus.COMPLETED)
        )).thenAnswer(invocation -> candidates.stream()
                .filter(candidate -> candidate.getComparisonStatus()
                        == CharacterFactComparisonStatus.COMPLETED)
                .filter(candidate -> candidate.getCharacterComparisonBatch() != null)
                .toList());
    }

    @Test
    @DisplayName("5개 STATUS 후보가 P/Q를 순차 투영하고 회복 후보가 모두 종료한다")
    void projectsFiveStatusCandidatesAndKeepsEarlierContextStable() {
        SettingCandidate injury = candidate("status.오른발_부상", "오른발이 완전히 망가짐", 10);
        SettingCandidate bleeding = candidate("status.출혈", "출혈이 지속됨", 20);
        SettingCandidate healthFive = candidate("status.생명력", "생명력 5% 미만", 30);
        SettingCandidate healthTwo = candidate("status.생명력", "생명력 2% 이하", 40);
        SettingCandidate recovery = candidate("status.회복", "포션으로 완전히 회복함", 50);

        WorkerCharacterFactComparisonBatchPayload firstClaim = worker.claimNext(
                analysisJobId,
                leaseToken
        ).orElseThrow();
        WorkerCharacterFactComparisonBatchContextResponse firstContext = worker.getContext(
                analysisJobId,
                firstClaim.comparisonBatchId(),
                leaseToken
        );

        assertThat(firstClaim.candidates())
                .extracting(WorkerCharacterFactComparisonBatchPayload.Candidate::candidateRef)
                .containsExactly("C1", "C2", "C3", "C4", "C5");
        assertThat(firstContext.snapshotEntries())
                .extracting(WorkerCharacterFactComparisonBatchContextResponse.SnapshotEntry::factKey)
                .containsExactly("status.오른발_부상", "status.마비독");

        worker.complete(
                analysisJobId,
                firstClaim.comparisonBatchId(),
                leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(
                        firstContext.contextToken(),
                        List.of(
                                update("C1", "status.오른발_부상", "P1", List.of(), "오른발이 완전히 망가짐"),
                                add("C2", "status.출혈", "출혈이 지속됨"),
                                add("C3", "status.생명력", "생명력 5% 미만"),
                                update(
                                        "C4",
                                        "status.생명력",
                                        "Q3",
                                        List.of("C3"),
                                        "생명력 2% 이하"
                                ),
                                remove(
                                        "C5",
                                        "status.회복",
                                        List.of("Q1", "P2", "Q2", "Q4"),
                                        List.of("C1", "C2", "C3", "C4")
                                )
                        ),
                        List.of(),
                        Map.of("fixture", "five-status-transitions")
                )
        );

        assertThat(List.of(injury, bleeding, healthFive, healthTwo, recovery))
                .allMatch(candidate -> candidate.getComparisonStatus()
                        == CharacterFactComparisonStatus.COMPLETED);
        assertThat(healthTwo.getComparisonDependencyCandidateIds())
                .extracting(JsonNode::asText)
                .containsExactly(healthFive.getId().toString());
        assertThat(recovery.getComparisonDependencyCandidateIds())
                .extracting(JsonNode::asText)
                .containsExactly(
                        injury.getId().toString(),
                        bleeding.getId().toString(),
                        healthFive.getId().toString(),
                        healthTwo.getId().toString()
                );
        assertThat(recovery.getRemovedSnapshotEntriesJson())
                .extracting(node -> node.path("factKey").asText())
                .containsExactly(
                        "status.오른발_부상",
                        "status.마비독",
                        "status.출혈",
                        "status.생명력"
                );

        SettingCandidate historyProbe = candidate("status.휴식", "잠시 쉬었다", 100);
        WorkerCharacterFactComparisonBatchPayload secondClaim = worker.claimNext(
                analysisJobId,
                leaseToken
        ).orElseThrow();
        WorkerCharacterFactComparisonBatchContextResponse secondContext = worker.getContext(
                analysisJobId,
                secondClaim.comparisonBatchId(),
                leaseToken
        );
        assertThat(secondContext.snapshotEntries()).isEmpty();

        worker.complete(
                analysisJobId,
                secondClaim.comparisonBatchId(),
                leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(
                        secondContext.contextToken(),
                        List.of(historyOnly("C1", "status.휴식")),
                        List.of(),
                        Map.of("fixture", "present-history-only")
                )
        );

        assertThat(historyProbe.getSuggestedOperation()).isEqualTo(CharacterFactOperation.HISTORY_ONLY);
        assertThat(historyProbe.getTemporalScope()).isEqualTo(CharacterFactTemporalScope.PRESENT);
        assertThat(worker.hasCurrentContext(injury)).isTrue();
        assertThat(worker.hasCurrentContext(recovery)).isTrue();
    }

    @Test
    @DisplayName("묶음 실패 응답 유실 뒤 같은 요청을 재전송해도 성공한다")
    void batchFailureIsIdempotentForSameFailure() {
        SettingCandidate first = candidate("status.부상", "부상", 10);
        SettingCandidate second = candidate("status.출혈", "출혈", 20);
        WorkerCharacterFactComparisonBatchPayload claim = worker.claimNext(
                analysisJobId,
                leaseToken
        ).orElseThrow();
        WorkerCharacterFactComparisonFailRequest request = new WorkerCharacterFactComparisonFailRequest(
                AnalysisFailureCode.LLM_PROVIDER_ERROR,
                "provider unavailable"
        );

        worker.fail(analysisJobId, claim.comparisonBatchId(), leaseToken, request);
        worker.fail(analysisJobId, claim.comparisonBatchId(), leaseToken, request);

        assertThat(batches.get(claim.comparisonBatchId()).getStatus())
                .isEqualTo(CharacterFactComparisonBatchStatus.FAILED);
        assertThat(List.of(first, second))
                .allMatch(candidate -> candidate.getComparisonStatus()
                        == CharacterFactComparisonStatus.FAILED);
    }

    @Test
    @DisplayName("pattern STATUS key만 기존 canonical slot으로 해소할 수 있다")
    void patternStatusMayResolveToExistingCanonicalKey() {
        SettingCandidate candidate = candidate("status.우측_발_부상", "오른발을 쓰지 못함", 10);
        WorkerCharacterFactComparisonBatchPayload claim = worker.claimNext(
                analysisJobId,
                leaseToken
        ).orElseThrow();
        WorkerCharacterFactComparisonBatchContextResponse context = worker.getContext(
                analysisJobId,
                claim.comparisonBatchId(),
                leaseToken
        );
        String injuryRef = context.snapshotEntries().stream()
                .filter(entry -> entry.factKey().equals("status.오른발_부상"))
                .map(WorkerCharacterFactComparisonBatchContextResponse.SnapshotEntry::snapshotRef)
                .findFirst()
                .orElseThrow();

        worker.complete(
                analysisJobId,
                claim.comparisonBatchId(),
                leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(
                        context.contextToken(),
                        List.of(update(
                                "C1",
                                "status.오른발_부상",
                                injuryRef,
                                List.of(),
                                "오른발을 쓰지 못함"
                        )),
                        List.of(),
                        Map.of()
                )
        );

        assertThat(candidate.getResolvedCanonicalFactKey()).isEqualTo("status.오른발_부상");
        assertThat(candidate.getComparisonTargetFactKey()).isEqualTo("status.오른발_부상");
    }

    @Test
    @DisplayName("exact와 alias로 정해진 canonical key는 Worker가 다른 key로 바꿀 수 없다")
    void exactAndAliasCanonicalKeysStayFixed() {
        when(schemaRepository.findAllActiveForWork(work.getId()))
                .thenReturn(List.of(profileSchemaWithAlias()));
        SettingCandidate exact = candidate("profile.species", "바바리안", 10);
        SettingCandidate alias = candidate("종족", "바바리안", 20);
        WorkerCharacterFactComparisonBatchPayload claim = worker.claimNext(
                analysisJobId,
                leaseToken
        ).orElseThrow();
        WorkerCharacterFactComparisonBatchContextResponse context = worker.getContext(
                analysisJobId,
                claim.comparisonBatchId(),
                leaseToken
        );

        assertThat(claim.candidates())
                .extracting(WorkerCharacterFactComparisonBatchPayload.Candidate::canonicalKeyResolution)
                .containsExactly(
                        CharacterFactCanonicalKeyResolution.EXACT,
                        CharacterFactCanonicalKeyResolution.ALIAS
                );
        WorkerCharacterFactComparisonBatchCompleteRequest.Failure ignoredFailure =
                new WorkerCharacterFactComparisonBatchCompleteRequest.Failure(
                        "C2",
                        AnalysisFailureCode.COMPARISON_VALIDATION_FAILED,
                        "key immutability fixture"
                );
        assertThatThrownBy(() -> worker.complete(
                analysisJobId,
                claim.comparisonBatchId(),
                leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(
                        context.contextToken(),
                        List.of(add("C1", "profile.job", "전사")),
                        List.of(ignoredFailure),
                        Map.of()
                )
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_TARGET_INVALID));

        WorkerCharacterFactComparisonBatchCompleteRequest.Failure exactFailure =
                new WorkerCharacterFactComparisonBatchCompleteRequest.Failure(
                        "C1",
                        AnalysisFailureCode.COMPARISON_VALIDATION_FAILED,
                        "key immutability fixture"
                );
        assertThatThrownBy(() -> worker.complete(
                analysisJobId,
                claim.comparisonBatchId(),
                leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(
                        context.contextToken(),
                        List.of(add("C2", "profile.job", "전사")),
                        List.of(exactFailure),
                        Map.of()
                )
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_TARGET_INVALID));
        assertThat(List.of(exact, alias)).allMatch(candidate ->
                candidate.getComparisonStatus() == CharacterFactComparisonStatus.PROCESSING);
    }

    @Test
    @DisplayName("누락·중복·아직 존재하지 않는 ref는 후보 하나도 완료하지 않고 거절한다")
    void invalidCoverageAndFutureReferenceAreAtomic() {
        SettingCandidate first = candidate("status.출혈", "출혈", 10);
        SettingCandidate second = candidate("status.생명력", "생명력 5%", 20);
        WorkerCharacterFactComparisonBatchPayload claim = worker.claimNext(
                analysisJobId,
                leaseToken
        ).orElseThrow();
        WorkerCharacterFactComparisonBatchContextResponse context = worker.getContext(
                analysisJobId,
                claim.comparisonBatchId(),
                leaseToken
        );
        WorkerCharacterFactComparisonBatchCompleteRequest.Decision firstAdd = add(
                "C1",
                "status.출혈",
                "출혈"
        );

        assertThatThrownBy(() -> worker.complete(
                analysisJobId,
                claim.comparisonBatchId(),
                leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(
                        context.contextToken(),
                        List.of(firstAdd),
                        List.of(),
                        Map.of()
                )
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_BATCH_RESPONSE_INVALID));

        assertThatThrownBy(() -> worker.complete(
                analysisJobId,
                claim.comparisonBatchId(),
                leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(
                        context.contextToken(),
                        List.of(firstAdd, firstAdd),
                        List.of(),
                        Map.of()
                )
        )).isInstanceOf(AppException.class);

        WorkerCharacterFactComparisonBatchCompleteRequest.Decision futureRef = update(
                "C1",
                "status.출혈",
                "Q2",
                List.of(),
                "출혈"
        );
        assertThatThrownBy(() -> worker.complete(
                analysisJobId,
                claim.comparisonBatchId(),
                leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(
                        context.contextToken(),
                        List.of(futureRef, add("C2", "status.생명력", "생명력 5%")),
                        List.of(),
                        Map.of()
                )
        )).isInstanceOf(AppException.class);

        assertThat(List.of(first, second))
                .allMatch(candidate -> candidate.getComparisonStatus()
                        == CharacterFactComparisonStatus.PROCESSING);
        assertThat(batches.get(claim.comparisonBatchId()).isProcessing()).isTrue();
    }

    @Test
    @DisplayName("후보 수 제한으로 나뉜 묶음은 다음 claim에서 빠짐없이 이어진다")
    void boundedSplitDoesNotLoseCandidates() {
        ReflectionTestUtils.setField(worker, "maxBatchCandidates", 2);
        for (int index = 1; index <= 5; index++) {
            candidate("status.상태_" + index, "상태 " + index, index * 10);
        }
        WorkerCharacterFactComparisonFailRequest failure = new WorkerCharacterFactComparisonFailRequest(
                AnalysisFailureCode.COMPARISON_VALIDATION_FAILED,
                "split fixture"
        );

        List<Integer> batchSizes = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            WorkerCharacterFactComparisonBatchPayload claim = worker.claimNext(
                    analysisJobId,
                    leaseToken
            ).orElseThrow();
            batchSizes.add(claim.candidates().size());
            worker.fail(analysisJobId, claim.comparisonBatchId(), leaseToken, failure);
        }

        assertThat(batchSizes).containsExactly(2, 2, 1);
        assertThat(candidates).allMatch(candidate ->
                candidate.getComparisonStatus() == CharacterFactComparisonStatus.FAILED);
        assertThat(worker.claimNext(analysisJobId, leaseToken)).isEmpty();
    }

    @Test
    @DisplayName("숨김 재비교 Job은 연결된 후보 하나만 singleton 묶음으로 claim한다")
    void hiddenRecomparisonClaimsOnlyLinkedCandidate() {
        SettingCandidate linked = candidate("status.부상", "부상", 10);
        candidate("status.출혈", "출혈", 20);
        ReflectionTestUtils.setField(analysisJob, "jobType", AnalysisJobType.CHARACTER_FACT_COMPARISON);
        ReflectionTestUtils.setField(analysisJob, "settingCandidate", linked);
        when(candidateRepository.findByIdAndWorkIdForUpdate(linked.getId(), work.getId()))
                .thenReturn(Optional.of(linked));

        WorkerCharacterFactComparisonBatchPayload claim = worker.claimNext(
                analysisJobId,
                leaseToken
        ).orElseThrow();

        assertThat(claim.candidates()).hasSize(1);
        assertThat(claim.candidates().getFirst().candidateRef()).isEqualTo("C1");
        assertThat(linked.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PROCESSING);
        assertThat(candidates.get(1).getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PENDING);
    }

    @Test
    @DisplayName("묶음 문맥 뒤 snapshot이 바뀌면 전체 완료를 stale로 거절한다")
    void staleSnapshotRejectsWholeBatch() {
        SettingCandidate first = candidate("status.출혈", "출혈", 10);
        SettingCandidate second = candidate("status.생명력", "생명력 5%", 20);
        WorkerCharacterFactComparisonBatchPayload claim = worker.claimNext(
                analysisJobId,
                leaseToken
        ).orElseThrow();
        WorkerCharacterFactComparisonBatchContextResponse context = worker.getContext(
                analysisJobId,
                claim.comparisonBatchId(),
                leaseToken
        );
        ObjectNode changedStatuses = objectMapper.createObjectNode();
        changedStatuses.set("status.오른발_부상", value("오른발을 쓰지 못함"));
        changedStatuses.set("status.마비독", value("마비독에 중독됨"));
        changedStatuses.set("status.새상태", value("외부에서 추가됨"));
        character.replaceCurrentSnapshots(null, null, null, null, null, null, changedStatuses);

        assertThatThrownBy(() -> worker.complete(
                analysisJobId,
                claim.comparisonBatchId(),
                leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(
                        context.contextToken(),
                        List.of(
                                add("C1", "status.출혈", "출혈"),
                                add("C2", "status.생명력", "생명력 5%")
                        ),
                        List.of(),
                        Map.of()
                )
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STALE));
        assertThat(List.of(first, second))
                .allMatch(candidate -> candidate.getComparisonStatus()
                        == CharacterFactComparisonStatus.PROCESSING);
        assertThat(batches.get(claim.comparisonBatchId()).isProcessing()).isTrue();
    }

    @Test
    @DisplayName("동명 캐릭터 ID와 같은 캐릭터의 서로 다른 FactType을 별도 묶음으로 claim한다")
    void separatesSameNameCharactersAndFactTypes() {
        WorkCharacter namesake = WorkCharacter.create(
                work,
                character.getName(),
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
        ReflectionTestUtils.setField(namesake, "id", UUID.randomUUID());
        when(characterRepository.findByIdAndWorkIdForUpdate(namesake.getId(), work.getId()))
                .thenReturn(Optional.of(namesake));
        when(schemaRepository.findAllActiveForWork(work.getId()))
                .thenReturn(List.of(statusSchema(), profileSchema()));
        SettingCandidate firstStatus = candidateFor(
                character,
                "status.부상",
                "첫 캐릭터 부상",
                10
        );
        SettingCandidate namesakeStatus = candidateFor(
                namesake,
                "status.부상",
                "동명 캐릭터 부상",
                20
        );
        SettingCandidate firstProfile = candidateFor(
                character,
                "profile.species",
                "바바리안",
                30
        );
        WorkerCharacterFactComparisonFailRequest failure = new WorkerCharacterFactComparisonFailRequest(
                AnalysisFailureCode.COMPARISON_VALIDATION_FAILED,
                "group split fixture"
        );

        WorkerCharacterFactComparisonBatchPayload first = worker.claimNext(
                analysisJobId,
                leaseToken
        ).orElseThrow();
        assertThat(first.canonicalFactType()).isEqualTo(CharacterFactType.STATUS);
        assertThat(first.candidates()).hasSize(1);
        assertThat(firstStatus.getCharacterComparisonBatch().getId()).isEqualTo(first.comparisonBatchId());
        assertThat(namesakeStatus.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PENDING);
        assertThat(firstProfile.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PENDING);
        worker.fail(analysisJobId, first.comparisonBatchId(), leaseToken, failure);

        WorkerCharacterFactComparisonBatchPayload second = worker.claimNext(
                analysisJobId,
                leaseToken
        ).orElseThrow();
        assertThat(second.canonicalFactType()).isEqualTo(CharacterFactType.STATUS);
        assertThat(second.candidates()).hasSize(1);
        assertThat(namesakeStatus.getCharacterComparisonBatch().getId()).isEqualTo(second.comparisonBatchId());
        worker.fail(analysisJobId, second.comparisonBatchId(), leaseToken, failure);

        WorkerCharacterFactComparisonBatchPayload third = worker.claimNext(
                analysisJobId,
                leaseToken
        ).orElseThrow();
        assertThat(third.canonicalFactType()).isEqualTo(CharacterFactType.PROFILE);
        assertThat(third.candidates()).hasSize(1);
        assertThat(firstProfile.getCharacterComparisonBatch().getId()).isEqualTo(third.comparisonBatchId());
    }

    @Test
    @DisplayName("표시 이름이 달라도 같은 캐릭터 ID와 FactType이면 같은 묶음으로 claim한다")
    void groupsByCharacterIdInsteadOfDisplayName() {
        SettingCandidate canonicalName = candidate("status.부상", "부상", 10);
        SettingCandidate alternateName = candidate("status.출혈", "출혈", 20);
        ReflectionTestUtils.setField(alternateName, "entityName", "비요른");

        WorkerCharacterFactComparisonBatchPayload claim = worker.claimNext(
                analysisJobId,
                leaseToken
        ).orElseThrow();

        assertThat(claim.candidates()).hasSize(2);
        assertThat(canonicalName.getCharacterComparisonBatch().getId())
                .isEqualTo(claim.comparisonBatchId());
        assertThat(alternateName.getCharacterComparisonBatch().getId())
                .isEqualTo(claim.comparisonBatchId());
    }

    private List<SettingCandidate> pendingCandidates() {
        return candidates.stream()
                .filter(candidate -> candidate.getComparisonStatus() == CharacterFactComparisonStatus.PENDING)
                .toList();
    }

    private SettingCandidate candidate(String key, String displayValue, int evidenceOffset) {
        return candidateFor(character, key, displayValue, evidenceOffset);
    }

    private SettingCandidate candidateFor(
            WorkCharacter targetCharacter,
            String key,
            String displayValue,
            int evidenceOffset
    ) {
        SettingCandidate candidate = SettingCandidate.create(
                work,
                null,
                UUID.randomUUID(),
                analysisJob,
                SettingEntityType.CHARACTER,
                targetCharacter.getName(),
                targetCharacter.getName(),
                targetCharacter.getId(),
                SettingCandidateMatchStatus.MATCHED,
                key,
                displayValue,
                SettingValueType.JSON,
                value(displayValue),
                objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                        .put("quote", displayValue)
                        .put("startOffset", evidenceOffset)
                        .put("endOffset", evidenceOffset + displayValue.length())),
                new BigDecimal("0.9000"),
                objectMapper.createObjectNode()
        );
        ReflectionTestUtils.setField(candidate, "id", UUID.randomUUID());
        candidates.add(candidate);
        return candidate;
    }

    private CharacterSettingSchema statusSchema() {
        return CharacterSettingSchema.create(
                null,
                "statuses.status",
                "status.*",
                "상태",
                CharacterFactType.STATUS,
                SettingValueType.JSON,
                CharacterSettingValueSemantics.BASE_VALUE,
                CharacterSettingMergePolicy.REPLACE,
                objectMapper.createArrayNode(),
                CharacterSettingSchemaSource.SYSTEM_SEED,
                true
        );
    }

    private CharacterSettingSchema profileSchema() {
        return CharacterSettingSchema.create(
                null,
                "profile.species",
                null,
                "종족",
                CharacterFactType.PROFILE,
                SettingValueType.JSON,
                CharacterSettingValueSemantics.BASE_VALUE,
                CharacterSettingMergePolicy.REPLACE,
                objectMapper.createArrayNode(),
                CharacterSettingSchemaSource.SYSTEM_SEED,
                true
        );
    }

    private CharacterSettingSchema profileSchemaWithAlias() {
        return CharacterSettingSchema.create(
                null,
                "profile.species",
                null,
                "종족",
                CharacterFactType.PROFILE,
                SettingValueType.JSON,
                CharacterSettingValueSemantics.BASE_VALUE,
                CharacterSettingMergePolicy.REPLACE,
                objectMapper.createArrayNode().add("종족"),
                CharacterSettingSchemaSource.SYSTEM_SEED,
                true
        );
    }

    private WorkerCharacterFactComparisonBatchCompleteRequest.Decision add(
            String candidateRef,
            String resolvedKey,
            String displayValue
    ) {
        return decision(
                candidateRef,
                CharacterFactOperation.ADD,
                resolvedKey,
                null,
                List.of(),
                List.of(),
                displayValue,
                valueMap(displayValue)
        );
    }

    private WorkerCharacterFactComparisonBatchCompleteRequest.Decision update(
            String candidateRef,
            String resolvedKey,
            String targetRef,
            List<String> dependencies,
            String displayValue
    ) {
        return decision(
                candidateRef,
                CharacterFactOperation.UPDATE,
                resolvedKey,
                targetRef,
                List.of(),
                dependencies,
                displayValue,
                valueMap(displayValue)
        );
    }

    private WorkerCharacterFactComparisonBatchCompleteRequest.Decision remove(
            String candidateRef,
            String resolvedKey,
            List<String> removedRefs,
            List<String> dependencies
    ) {
        return decision(
                candidateRef,
                CharacterFactOperation.REMOVE,
                resolvedKey,
                null,
                removedRefs,
                dependencies,
                null,
                null
        );
    }

    private WorkerCharacterFactComparisonBatchCompleteRequest.Decision historyOnly(
            String candidateRef,
            String resolvedKey
    ) {
        return decision(
                candidateRef,
                CharacterFactOperation.HISTORY_ONLY,
                resolvedKey,
                null,
                List.of(),
                List.of(),
                null,
                null
        );
    }

    private WorkerCharacterFactComparisonBatchCompleteRequest.Decision decision(
            String candidateRef,
            CharacterFactOperation operation,
            String resolvedKey,
            String targetRef,
            List<String> removedRefs,
            List<String> dependencies,
            String proposedFactValue,
            Object proposedValueJson
    ) {
        return new WorkerCharacterFactComparisonBatchCompleteRequest.Decision(
                candidateRef,
                operation,
                resolvedKey,
                targetRef,
                removedRefs,
                dependencies,
                proposedFactValue,
                proposedValueJson,
                CharacterFactTemporalScope.PRESENT,
                "테스트 비교 근거",
                Map.of("candidateRef", candidateRef)
        );
    }

    private Map<String, Object> valueMap(String value) {
        return Map.of("value", value);
    }

    private ObjectNode value(String value) {
        return objectMapper.createObjectNode().put("value", value);
    }
}
