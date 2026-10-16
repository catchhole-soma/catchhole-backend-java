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
import java.time.LocalDateTime;
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
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.exception.OrderedCharacterComparisonClaimException;
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
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
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
    @Mock
    private CharacterAnalysisStateService analysisStateService;
    @Mock
    private CharacterFactComparisonJobCoordinator comparisonJobCoordinator;

    @Mock private CharacterAnalysisStateSource analysisStateSource;
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
                new CharacterFactComparisonWorkerMapper(),
                analysisStateService, analysisStateSource,
                new org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisRunContextMapper(new org.monitoring.catchholebackend.domain.character.mapper.CharacterFactEvidenceMapper(new org.monitoring.catchholebackend.domain.character.processor.CharacterFactSourceResolver())),
                comparisonJobCoordinator
        );

        when(analysisStateSource.captureCharacter(any(WorkCharacter.class))).thenAnswer(invocation ->
                new org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper()
                        .toState(invocation.getArgument(0), Map.of(), Map.of()));
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
        when(batchRepository.existsByAnalysisJobIdAndMatchedCharacterIdAndStatus(
                eq(analysisJobId),
                eq(character.getId()),
                eq(CharacterFactComparisonBatchStatus.PROCESSING)
        )).thenAnswer(invocation -> batches.values().stream()
                .anyMatch(CharacterFactComparisonBatch::isProcessing));
        when(batchRepository.saveAndFlush(any(CharacterFactComparisonBatch.class)))
                .thenAnswer(invocation -> {
                    CharacterFactComparisonBatch batch = invocation.getArgument(0);
                    ReflectionTestUtils.setField(batch, "id", UUID.randomUUID());
                    ReflectionTestUtils.setField(batch, "createdAt",
                            LocalDateTime.of(2026, 9, 10, 12, 0).plusSeconds(batches.size()));
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
    void batchNarrativeTimeSurvivesConfirmedClaimAndContext() throws Exception {
        assertBatchNarrativeTime(false);
    }

    @Test
    void batchNarrativeTimeSurvivesOrderedClaimAndContext() throws Exception {
        assertBatchNarrativeTime(true);
    }

    private void assertBatchNarrativeTime(boolean ordered) throws Exception {
        if (ordered) orderedState(0);
        SettingCandidate timed = candidate("status.부상", "부상", 10);
        JsonNode time = objectMapper.readTree("""
                {"factTime":{"description":"장마 전","evidenceSpans":[{"quote":"장마 전","startOffset":0,"endOffset":4,"sourceChunkId":"10000000-0000-4000-8000-000000000002"}]},
                 "referenceTime":{"description":"치료 뒤","evidenceSpans":[{"quote":"치료 뒤","startOffset":5,"endOffset":9,"sourceChunkId":"10000000-0000-4000-8000-000000000002"}]}}
                """);
        timed.recordNarrativeTime(time);
        candidate("status.출혈", "출혈", 20); // Existing candidates may have no located time.
        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        var context = worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken);
        String output = System.getenv("BATCH_TIME_FIX_OUTPUT");
        for (var entry : Map.<String, JsonNode>of("claim", objectMapper.valueToTree(claim),
                "context", objectMapper.valueToTree(context)).entrySet()) {
            if (output != null) {
                java.nio.file.Path file = java.nio.file.Path.of(output, (ordered ? "ordered-" : "confirmed-") + entry.getKey() + ".json");
                java.nio.file.Files.writeString(file, entry.getValue().toString());
            }
            assertThat(entry.getValue().path("candidates").get(0).path("narrativeTime"))
                    .as("%s candidate time", entry.getKey()).isEqualTo(time);
            assertThat(entry.getValue().path("candidates").get(1).path("narrativeTime").isNull()).isTrue();
        }
    }

    @Test
    @DisplayName("누적 비교는 실제 DB에 없는 앞 회차 상태를 동일 문맥에서 제거한다")
    void orderedCompletionUsesFrozenProvisionalState() {
        ObjectNode state = orderedState(1);
        SettingCandidate candidate = candidate("status.앞회차_0", "회복", 10);
        WorkerCharacterFactComparisonBatchPayload claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        var context = worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken);
        assertThat(context.analysisContext()).isNotNull();
        assertThat(context.snapshotEntries()).hasSize(1);
        assertThat(context.snapshotEntries().getFirst().provenance().confirmationStatus()).isEqualTo("PROVISIONAL");
        assertThat(context.snapshotEntries().getFirst().factKey()).isEqualTo("status.앞회차_0");
        String ref = context.snapshotEntries().getFirst().snapshotRef();
        worker.complete(analysisJobId, claim.comparisonBatchId(), leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(context.contextToken(),
                        List.of(remove("C1", "status.앞회차_0", List.of(ref), List.of())), List.of(), Map.of()));
        assertThat(candidate.getSuggestedOperation()).isEqualTo(CharacterFactOperation.REMOVE);
        assertThat(candidate.getRawComparisonJson().path("backendComparisonBefore").get(0)
                .path("factValue").asText()).isEqualTo("앞 회차 부상");
        assertThat(candidate.getRawComparisonJson().path("backendComparisonBefore").get(0)
                .path("factKey").asText()).isEqualTo("status.앞회차_0");
        assertThat(state.path("slots")).hasSize(1);
        org.mockito.Mockito.verify(analysisStateService).recordDecision(eq(analysisJob), eq(candidate),
                any(), any(), any());
    }

    @Test
    @DisplayName("STATUS snapshot이 비어도 같은 인물 PROFILE 회복과 BOOK 시점이 frozen advisory에 있고 token에 결속된다")
    void orderedContextIncludesCrossTypeFrozenCurrentAndTokenBindsIt() {
        ObjectNode state = orderedState(0);
        ObjectNode profile = state.withObject("slots").putObject("PROFILE:profile.오른손_감각");
        profile.put("factType", "PROFILE").put("factKey", "profile.오른손_감각").put("factValue", "정상");
        profile.set("valueJson", value("정상"));
        ObjectNode provenance = profile.putObject("provenance").put("confirmationStatus", "CONFIRMED");
        provenance.putArray("sourceCandidateIds");
        provenance.putArray("currentSourceTimes").addObject().put("sourceKind", "SETTING_BOOK")
                .putNull("sourceEpisodeNo").put("referenceTimeKind", "EPISODE").put("referenceEpisodeNo", 3)
                .putNull("effectiveFromEpisodeNo").put("temporalScope", "PRESENT");
        candidate("status.오른발_부상", "과거 화상", 10);
        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        var context = worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken);
        assertThat(context.snapshotEntries()).extracting(WorkerCharacterFactComparisonBatchContextResponse.SnapshotEntry::factType)
                .containsExactly(CharacterFactType.PROFILE);
        var json = objectMapper.valueToTree(context).path("advisoryCurrentContext");
        assertThat(json.path("complete").asBoolean()).isTrue();
        assertThat(json.path("characterId").asText()).isEqualTo(character.getId().toString());
        assertThat(json.path("captureVersion").asInt()).isZero();
        assertThat(json.path("currentSlots")).hasSize(1);
        assertThat(json.path("currentSlots").get(0).path("factType").asText()).isEqualTo("PROFILE");
        assertThat(json.path("currentSlots").get(0).path("factValue").asText()).isEqualTo("정상");
        assertThat(json.path("currentSlots").get(0).path("currentSourceTimes").get(0).path("referenceEpisodeNo").asInt()).isEqualTo(3);
        assertThat(json.path("currentSlots").get(0).path("supportingSources").get(0).path("unavailableReason").asText())
                .isEqualTo("LEGACY_INPUT_WITHOUT_FROZEN_EVIDENCE");
        profile.put("factValue", "작가가 바꾼 현재값");
        var changed = worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken);
        assertThat(changed.contextToken()).isNotEqualTo(context.contextToken());
        assertThatThrownBy(() -> worker.complete(analysisJobId, claim.comparisonBatchId(), leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(context.contextToken(), List.of(), List.of(), Map.of())))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("frozen advisory는 같은 이름이어도 다른 실제 인물의 CURRENT를 허용하지 않는다")
    void orderedAdvisoryRejectsAnotherOwner() {
        ObjectNode state = orderedState(0);
        state.put("actualCharacterId", UUID.randomUUID().toString());
        candidate("status.화상", "화상", 10);
        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        assertThatThrownBy(() -> worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken))
                .isInstanceOf(org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException.class);
    }

    @Test
    @DisplayName("새 capture의 근거 배열이 소실된 CURRENT를 legacy로 낮추지 않는다")
    void orderedAdvisoryRejectsIncompleteNewCapture() {
        ObjectNode state = orderedState(1);
        state.put("currentSupportingEvidenceVersion", 1);
        candidate("status.화상", "화상", 10);
        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        assertThatThrownBy(() -> worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken))
                .isInstanceOf(org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException.class);
    }

    @Test
    @DisplayName("읽기 전용 CURRENT는 빈 scalar와 null JSON·TIME도 있는 그대로 전달한다")
    void orderedAdvisoryPreservesEmptyAndNullValues() {
        ObjectNode state = orderedState(0);
        ObjectNode profile = state.withObject("slots").putObject("PROFILE:profile.비고");
        profile.put("factType", "PROFILE").put("factKey", "profile.비고").put("factValue", "").putNull("valueJson");
        profile.putObject("provenance").putArray("sourceCandidateIds");
        ObjectNode time = state.withObject("slots").putObject("TIME:time.시점");
        time.put("factType", "TIME").put("factKey", "time.시점").putNull("factValue").putNull("valueJson");
        time.putObject("provenance").putArray("sourceCandidateIds");
        candidate("status.화상", "화상", 10);
        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        var current = worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken).advisoryCurrentContext().currentSlots();
        assertThat(current).hasSize(2);
        assertThat(current.get(0).factValue()).isEmpty();
        assertThat(current.get(0).valueJson()).isNull();
        assertThat(current.get(1).factType()).isEqualTo(CharacterFactType.TIME);
        assertThat(current.get(1).factValue()).isNull();
        assertThat(current.get(1).valueJson()).isNull();
    }

    @Test
    @DisplayName("captureVersion 큰 정수는 1로 wrap하여 승인하지 않는다")
    void orderedAdvisoryRejectsOverflowedCaptureVersion() {
        ObjectNode state = orderedState(0);
        state.put("currentSupportingEvidenceVersion", 4294967297L);
        candidate("status.화상", "화상", 10);
        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        assertThatThrownBy(() -> worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken))
                .isInstanceOf(org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException.class);
    }

    @Test
    @DisplayName("누적 비교 문맥은 관련된 앞 회차 설정을 30개 제한으로 자르지 않는다")
    void orderedContextPreservesEveryRelevantSlot() {
        orderedState(35);
        candidate("status.앞회차_34", "상태 변화", 10);
        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        var context = worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken);
        assertThat(context.snapshotEntries()).hasSize(35);
        assertThat(context.snapshotEntries()).allSatisfy(entry -> assertThat(entry.provenance()).isNotNull());
    }

    @Test
    @DisplayName("인물 묶음은 모든 타입의 현재 설정을 30개 제한 없이 제공한다")
    void personContextPreservesAllTypesWithoutLegacyCap() {
        ObjectNode statuses = objectMapper.createObjectNode();
        for (int index = 0; index < 35; index++) statuses.set("status." + index, value("상태 " + index));
        ObjectNode profile = objectMapper.createObjectNode().set("profile.species", value("인간"));
        character.replaceCurrentSnapshots(null, null, profile, null, null, null, statuses);
        candidate("status.회복", "회복", 10);

        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        var context = worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken);

        assertThat(context.canonicalFactType()).isNull();
        assertThat(context.snapshotEntries()).hasSize(36);
        assertThat(context.snapshotEntries()).extracting(WorkerCharacterFactComparisonBatchContextResponse.SnapshotEntry::factType)
                .contains(CharacterFactType.PROFILE, CharacterFactType.STATUS);
    }

    @Test
    @DisplayName("누적 인물 묶음은 서로 다른 타입의 앞 회차 설정과 현재 Q 참조를 함께 검증한다")
    void orderedPersonBatchCompletesMixedTypesAndProjectedDependencies() throws Exception {
        ObjectNode state = orderedState(1);
        ObjectNode profile = state.withObject("slots").putObject("PROFILE:profile.species");
        profile.put("factType", "PROFILE").put("factKey", "profile.species").put("factValue", "인간");
        profile.set("valueJson", value("인간"));
        profile.putObject("provenance").put("confirmationStatus", "PROVISIONAL").put("sourceEpisodeNo", 1).putArray("sourceCandidateIds");
        when(schemaRepository.findAllActiveForWork(work.getId())).thenReturn(List.of(statusSchema(), profileSchema()));
        SettingCandidate injury = candidate("status.앞회차_0", "회복", 10);
        SettingCandidate species = candidate("profile.species", "엘프", 20);
        SettingCandidate laterSpecies = candidate("profile.species", "하이엘프", 30);

        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        var context = worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken);
        assertThat(claim.candidates()).hasSize(3);
        assertThat(context.snapshotEntries()).hasSize(2);
        exportPersonBatch("ordered", claim, context);
        worker.complete(analysisJobId, claim.comparisonBatchId(), leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(context.contextToken(), List.of(
                        remove("C1", "status.앞회차_0", List.of(snapshotRef(context, "status.앞회차_0")), List.of()),
                        update("C2", "profile.species", snapshotRef(context, "profile.species"), List.of(), "엘프"),
                        update("C3", "profile.species", "Q2", List.of("C2"), "하이엘프")), List.of(), Map.of()));

        assertThat(List.of(injury, species, laterSpecies)).allSatisfy(candidate ->
                assertThat(candidate.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.COMPLETED));
        assertThat(laterSpecies.getComparisonDependencyCandidateIds()).extracting(JsonNode::asText)
                .containsExactly(species.getId().toString());
        assertThat(laterSpecies.getComparisonTargetFactType()).isEqualTo(CharacterFactType.PROFILE);
        assertThat(state.path("slots").path("PROFILE:profile.species").path("factValue").asText()).isEqualTo("인간");
    }

    @Test
    @DisplayName("같은 키여도 다른 타입의 P 참조는 완료를 원자적으로 거절한다")
    void rejectsPersistedReferenceToSameKeyOfAnotherType() {
        ObjectNode profile = objectMapper.createObjectNode().set("status.부상", value("프로필 부상"));
        character.replaceCurrentSnapshots(null, null, profile, null, null, null, character.getStatusesJson());
        SettingCandidate injury = candidate("status.부상", "회복", 10);
        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        var context = worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken);
        assertThat(context.snapshotEntries()).anyMatch(entry -> entry.factType() == CharacterFactType.PROFILE);
        String profileRef = context.snapshotEntries().stream().filter(entry -> entry.factType() == CharacterFactType.PROFILE)
                .findFirst().orElseThrow().snapshotRef();

        assertThatThrownBy(() -> worker.complete(analysisJobId, claim.comparisonBatchId(), leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(context.contextToken(),
                        List.of(update("C1", "status.부상", profileRef, List.of(), "회복")), List.of(), Map.of())))
                .isInstanceOfSatisfying(AppException.class, exception -> assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_TARGET_INVALID));
        assertThat(injury.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PROCESSING);
    }

    @Test
    @DisplayName("같은 키여도 다른 타입의 Q 참조는 정상 앞 후보까지 저장하지 않는다")
    void rejectsProjectedReferenceOfAnotherTypeAtomically() {
        CharacterSettingSchema profileSchema = profileSchema();
        ReflectionTestUtils.setField(profileSchema, "schemaKey", "status.shared");
        ReflectionTestUtils.setField(profileSchema, "aliasesJson", objectMapper.createArrayNode().add("종족"));
        when(schemaRepository.findAllActiveForWork(work.getId())).thenReturn(List.of(statusSchema(), profileSchema));
        SettingCandidate profile = candidate("종족", "인간", 10);
        SettingCandidate status = candidate("status.부상", "부상", 20);
        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        var context = worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken);
        assertThat(claim.candidates()).hasSize(2);

        assertThatThrownBy(() -> worker.complete(analysisJobId, claim.comparisonBatchId(), leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(context.contextToken(), List.of(
                        add("C1", "status.shared", "인간"), update("C2", "status.shared", "Q1", List.of("C1"), "부상")),
                        List.of(), Map.of())))
                .isInstanceOfSatisfying(AppException.class, exception -> assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_TARGET_INVALID));
        assertThat(List.of(profile, status)).allSatisfy(candidate -> {
            assertThat(candidate.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PROCESSING);
            assertThat(candidate.getSuggestedOperation()).isNull();
        });
        worker.complete(analysisJobId, claim.comparisonBatchId(), leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(context.contextToken(), List.of(
                        add("C1", "status.shared", "인간"), add("C2", "status.shared", "부상")), List.of(), Map.of()));
        assertThat(profile.getComparisonTargetFactType()).isEqualTo(CharacterFactType.PROFILE);
        assertThat(status.getComparisonTargetFactType()).isEqualTo(CharacterFactType.STATUS);
        assertThat(status.getComparisonDependencyCandidateIds()).isEmpty();
    }

    @Test
    @DisplayName("임시 인물도 여러 타입을 하나의 고정 입력으로 비교한다")
    void provisionalPersonBatchIncludesEveryTypeAndKeepsFrozenIdentity() {
        ObjectNode state = orderedState(1);
        String provisionalKey = "provisional-character:" + UUID.randomUUID();
        state.putNull("actualCharacterId").put("provisionalSubjectKey", provisionalKey);
        when(schemaRepository.findAllActiveForWork(work.getId())).thenReturn(List.of(statusSchema(), profileSchema()));
        SettingCandidate status = candidate("status.앞회차_0", "회복", 10);
        SettingCandidate profile = candidate("profile.species", "인간", 20);
        for (SettingCandidate candidate : candidates) {
            ReflectionTestUtils.setField(candidate, "matchedCharacterId", null);
            ReflectionTestUtils.setField(candidate, "provisionalSubjectKey", provisionalKey);
        }
        when(candidateRepository.findProvisionalComparisonGroupForUpdate(analysisJobId, provisionalKey,
                SettingCandidateReviewStatus.PENDING_REVIEW, CharacterFactComparisonStatus.PENDING)).thenAnswer(call -> pendingCandidates());
        when(analysisStateService.getTarget(analysisJob, null, provisionalKey)).thenAnswer(call -> state.deepCopy());
        when(batchRepository.existsByAnalysisJobIdAndProvisionalSubjectKeyAndStatus(analysisJobId, provisionalKey,
                CharacterFactComparisonBatchStatus.PROCESSING)).thenAnswer(call -> batches.values().stream().anyMatch(CharacterFactComparisonBatch::isProcessing));

        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        var context = worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken);
        assertThat(claim.canonicalFactType()).isNull();
        assertThat(claim.provisionalSubjectKey()).isEqualTo(provisionalKey);
        assertThat(context.advisoryCurrentContext().provisionalSubjectKey()).isEqualTo(provisionalKey);
        worker.complete(analysisJobId, claim.comparisonBatchId(), leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(context.contextToken(), List.of(
                        remove("C1", "status.앞회차_0", List.of(snapshotRef(context, "status.앞회차_0")), List.of()),
                        add("C2", "profile.species", "인간")), List.of(), Map.of()));
        assertThat(List.of(status, profile)).allSatisfy(candidate ->
                assertThat(candidate.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.COMPLETED));
        assertThat(batches.get(claim.comparisonBatchId()).getAnalysisContextSnapshotJson()).isEqualTo(state);
    }

    @Test
    @DisplayName("인물 묶음 문맥은 같은 canonical key의 후보 타입 변경도 stale로 감지한다")
    void contextTokenBindsEachCandidateActualType() {
        SettingCandidate candidate = candidate("status.새부상", "부상", 10);
        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        var context = worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken);
        CharacterSettingSchema changedSchema = statusSchema();
        ReflectionTestUtils.setField(changedSchema, "factType", CharacterFactType.PROFILE);
        when(schemaRepository.findAllActiveForWork(work.getId())).thenReturn(List.of(changedSchema));

        assertThatThrownBy(() -> worker.complete(analysisJobId, claim.comparisonBatchId(), leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(context.contextToken(),
                        List.of(add("C1", "status.새부상", "부상")), List.of(), Map.of())))
                .isInstanceOfSatisfying(AppException.class, exception -> assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STALE));
        assertThat(candidate.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PROCESSING);
    }

    @Test
    @DisplayName("누적 묶음의 전체 실패 재요청은 멱등이며 후보별 재시도 연결을 유지한다")
    void orderedBatchFailureIsAtomicAndIdempotent() {
        orderedState(1);
        SettingCandidate first = candidate("status.출혈", "출혈", 10);
        SettingCandidate second = candidate("status.생명력", "생명력 5%", 20);
        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        var context = worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken);
        var failure = new WorkerCharacterFactComparisonBatchCompleteRequest.Failure(
                "C2", AnalysisFailureCode.LLM_PROVIDER_ERROR, "일시 실패");
        var failedRequest = new WorkerCharacterFactComparisonBatchCompleteRequest(context.contextToken(), List.of(),
                List.of(new WorkerCharacterFactComparisonBatchCompleteRequest.Failure(
                        "C1", AnalysisFailureCode.LLM_PROVIDER_ERROR, "일시 실패"), failure), Map.of());
        worker.complete(analysisJobId, claim.comparisonBatchId(), leaseToken, failedRequest);
        worker.complete(analysisJobId, claim.comparisonBatchId(), leaseToken, failedRequest);
        assertThat(List.of(first, second)).allSatisfy(row ->
                assertThat(row.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.FAILED));
        assertThat(batches.get(claim.comparisonBatchId()).getStatus()).isEqualTo(CharacterFactComparisonBatchStatus.FAILED);
        org.mockito.Mockito.verify(analysisStateService, org.mockito.Mockito.never()).recordDecision(any(), any(), any(), any(), any());
        first.retryFailedOrderedComparison();
        assertThat(first.getMatchedCharacterId()).isEqualTo(character.getId());
        assertThat(first.getCharacterComparisonBatch()).isNull();
        assertThat(first.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PENDING);
        assertThatThrownBy(first::retryFailedOrderedComparison).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("누적 묶음은 독립적인 성공과 개별 실패를 함께 저장하고 재전송해도 판단을 중복 기록하지 않는다")
    void orderedMixedCompletionPreservesIndependentSuccess() {
        orderedState(1);
        SettingCandidate first = candidate("status.출혈", "출혈", 10);
        SettingCandidate failed = candidate("status.생명력", "생명력 5%", 20);
        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        var context = worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken);
        var request = new WorkerCharacterFactComparisonBatchCompleteRequest(context.contextToken(),
                List.of(add("C1", "status.출혈", "출혈")),
                List.of(new WorkerCharacterFactComparisonBatchCompleteRequest.Failure(
                        "C2", AnalysisFailureCode.COMPARISON_VALIDATION_FAILED, "비교 결과를 확인해 주세요.")), Map.of());
        worker.complete(analysisJobId, claim.comparisonBatchId(), leaseToken, request);
        worker.complete(analysisJobId, claim.comparisonBatchId(), leaseToken, request);
        assertThat(first.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.COMPLETED);
        assertThat(failed.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.FAILED);
        assertThat(batches.get(claim.comparisonBatchId()).getStatus()).isEqualTo(CharacterFactComparisonBatchStatus.COMPLETED);
        org.mockito.Mockito.verify(analysisStateService, org.mockito.Mockito.times(1))
                .recordDecision(eq(analysisJob), eq(first), any(), any(), any());
        assertThat(failed.getAttributeValue()).isEqualTo("생명력 5%");
    }

    @Test
    @DisplayName("실패한 앞 후보의 제안을 참조하는 뒤 판단은 일부 성공으로 저장하지 않는다")
    void orderedMixedCompletionRejectsFailedDependency() {
        orderedState(1);
        SettingCandidate failed = candidate("status.출혈", "출혈", 10);
        SettingCandidate later = candidate("status.생명력", "회복", 20);
        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        var context = worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken);
        var request = new WorkerCharacterFactComparisonBatchCompleteRequest(context.contextToken(),
                List.of(remove("C2", "status.생명력", List.of("Q1"), List.of("C1"))),
                List.of(new WorkerCharacterFactComparisonBatchCompleteRequest.Failure(
                        "C1", AnalysisFailureCode.COMPARISON_VALIDATION_FAILED, "개별 비교 실패")), Map.of());
        assertThatThrownBy(() -> worker.complete(analysisJobId, claim.comparisonBatchId(), leaseToken, request))
                .isInstanceOf(AppException.class);
        assertThat(List.of(failed, later)).allSatisfy(candidate ->
                assertThat(candidate.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PROCESSING));
        org.mockito.Mockito.verify(analysisStateService, org.mockito.Mockito.never())
                .recordDecision(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("토큰 부족은 성공 후보와 섞어서 완료할 수 없다")
    void orderedMixedCompletionRejectsBlockingFailure() {
        orderedState(1);
        candidate("status.출혈", "출혈", 10);
        candidate("status.생명력", "생명력 5%", 20);
        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        var context = worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken);
        var request = new WorkerCharacterFactComparisonBatchCompleteRequest(context.contextToken(),
                List.of(add("C1", "status.출혈", "출혈")),
                List.of(new WorkerCharacterFactComparisonBatchCompleteRequest.Failure(
                        "C2", AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED, "사용량 부족")), Map.of());
        assertThatThrownBy(() -> worker.complete(analysisJobId, claim.comparisonBatchId(), leaseToken, request))
                .isInstanceOf(AppException.class);
    }

    private ObjectNode orderedState(int size) {
        ReflectionTestUtils.setField(analysisJob, "analysisMode",
                org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode.ORDERED_PROVISIONAL);
        ReflectionTestUtils.setField(analysisJob, "analysisRunId", UUID.randomUUID());
        ReflectionTestUtils.setField(analysisJob, "runGeneration", 1L);
        ReflectionTestUtils.setField(analysisJob, "inputStateHash", "a".repeat(64));
        ReflectionTestUtils.setField(analysisJob, "sourceContentHash", "b".repeat(64));
        ObjectNode state = objectMapper.createObjectNode();
        state.put("actualCharacterId", character.getId().toString());
        state.putNull("provisionalSubjectKey");
        state.put("name", character.getName());
        state.put("snapshotVersion", 0);
        state.putObject("absences");
        ObjectNode slots = state.putObject("slots");
        for (int index = 0; index < size; index++) {
            ObjectNode slot = slots.putObject("STATUS:status.앞회차_" + index);
            slot.put("factType", "STATUS");
            slot.put("factKey", "status.앞회차_" + index);
            slot.put("factValue", "앞 회차 부상");
            slot.set("valueJson", value("앞 회차 부상"));
            slot.putObject("provenance").put("confirmationStatus", "PROVISIONAL")
                    .put("sourceEpisodeNo", 1).putArray("sourceCandidateIds");
        }
        when(analysisStateService.getTarget(eq(analysisJob), eq(character.getId()), any()))
                .thenAnswer(invocation -> state.deepCopy());
        return state;
    }

    @Test
    @DisplayName("신규 캐릭터 그룹은 빈 snapshot에서 발생 후보를 해제 후보의 의존성으로 사용한다")
    void comparesNewCharacterOccurrenceAndRecoveryFromEmptySnapshot() {
        UploadBatch uploadBatch = org.mockito.Mockito.mock(UploadBatch.class);
        when(uploadBatch.getId()).thenReturn(UUID.randomUUID());
        AnalysisJob sourceJob = AnalysisJob.create(work, uploadBatch, null, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(sourceJob, "id", UUID.randomUUID());
        SettingCandidate occurrence = unmatchedCandidate(
                sourceJob,
                "status.의식_상실",
                "의식을 잃음",
                true,
                10
        );
        SettingCandidate recovery = unmatchedCandidate(
                sourceJob,
                "status.의식_상실",
                "의식을 되찾음",
                false,
                20
        );
        analysisJob = AnalysisJob.createCharacterFactComparison(occurrence, "a".repeat(64));
        analysisJobId = UUID.randomUUID();
        ReflectionTestUtils.setField(analysisJob, "id", analysisJobId);
        when(analysisJobLeaseService.getRunningAnalysisJobForUpdate(analysisJobId, leaseToken))
                .thenReturn(analysisJob);
        when(comparisonJobCoordinator.lockScopeCandidates(analysisJob))
                .thenReturn(List.of(occurrence, recovery));
        when(comparisonJobCoordinator.hasCurrentInput(analysisJob, List.of(occurrence, recovery)))
                .thenReturn(true);
        when(batchRepository.findByIdAndAnalysisJobIdForUpdate(any(UUID.class), eq(analysisJobId)))
                .thenAnswer(invocation -> Optional.ofNullable(batches.get(invocation.getArgument(0))));

        WorkerCharacterFactComparisonBatchPayload claim = worker.claimNext(
                analysisJobId,
                leaseToken
        ).orElseThrow();
        WorkerCharacterFactComparisonBatchContextResponse context = worker.getContext(
                analysisJobId,
                claim.comparisonBatchId(),
                leaseToken
        );

        assertThat(claim.matchedCharacterName()).isEqualTo("아리아");
        assertThat(context.baseSnapshotVersion()).isZero();
        assertThat(context.snapshotEntries()).isEmpty();
        assertThat(claim.candidates())
                .extracting(WorkerCharacterFactComparisonBatchPayload.Candidate::candidateRef)
                .containsExactly("C1", "C2");

        worker.complete(
                analysisJobId,
                claim.comparisonBatchId(),
                leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(
                        context.contextToken(),
                        List.of(
                                add("C1", "status.의식_상실", "의식을 잃음"),
                                remove(
                                        "C2",
                                        "status.의식_상실",
                                        List.of("Q1"),
                                        List.of("C1")
                                )
                        ),
                        List.of(),
                        Map.of("fixture", "new-character-occurrence-recovery")
                )
        );

        assertThat(occurrence.getSuggestedOperation()).isEqualTo(CharacterFactOperation.ADD);
        assertThat(recovery.getSuggestedOperation()).isEqualTo(CharacterFactOperation.REMOVE);
        assertThat(recovery.getComparisonDependencyCandidateIds().get(0).asText())
                .isEqualTo(occurrence.getId().toString());
        assertThat(List.of(occurrence, recovery))
                .allMatch(candidate -> candidate.getReviewStatus()
                        == SettingCandidateReviewStatus.PENDING_REVIEW);
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
                .containsExactly("status.마비독", "status.오른발_부상");

        worker.complete(
                analysisJobId,
                firstClaim.comparisonBatchId(),
                leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(
                        firstContext.contextToken(),
                        List.of(
                                update("C1", "status.오른발_부상", snapshotRef(firstContext, "status.오른발_부상"), List.of(), "오른발이 완전히 망가짐"),
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
                                        List.of("Q1", snapshotRef(firstContext, "status.마비독"), "Q2", "Q4"),
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
    @DisplayName("REMOVE 뒤 같은 slot을 다시 만들면 부재를 만든 후보까지 전이 의존한다")
    void removeAndRecreateSameSlotCarriesTransitiveAbsenceDependencies() {
        SettingCandidate firstRemoval = candidate("status.오른발_부상", "오른발 부상이 회복됨", 10);
        SettingCandidate firstRecreation = candidate("status.오른발_부상", "오른발을 다시 다침", 20);
        SettingCandidate secondRemoval = candidate("status.오른발_부상", "오른발 부상이 다시 회복됨", 30);
        SettingCandidate secondRecreation = candidate("status.오른발_부상", "오른발을 또 다침", 40);
        WorkerCharacterFactComparisonBatchPayload claim = worker.claimNext(
                analysisJobId,
                leaseToken
        ).orElseThrow();
        WorkerCharacterFactComparisonBatchContextResponse context = worker.getContext(
                analysisJobId,
                claim.comparisonBatchId(),
                leaseToken
        );
        String injuryRef = snapshotRef(context, "status.오른발_부상");

        worker.complete(
                analysisJobId,
                claim.comparisonBatchId(),
                leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(
                        context.contextToken(),
                        List.of(
                                remove(
                                        "C1",
                                        "status.오른발_부상",
                                        List.of(injuryRef),
                                        List.of()
                                ),
                                add(
                                        "C2",
                                        "status.오른발_부상",
                                        List.of("C1"),
                                        "오른발을 다시 다침"
                                ),
                                remove(
                                        "C3",
                                        "status.오른발_부상",
                                        List.of("Q2"),
                                        List.of("C1", "C2")
                                ),
                                add(
                                        "C4",
                                        "status.오른발_부상",
                                        List.of("C1", "C2", "C3"),
                                        "오른발을 또 다침"
                                )
                        ),
                        List.of(),
                        Map.of("fixture", "same-slot-absence-provenance")
                )
        );

        assertThat(firstRemoval.getComparisonDependencyCandidateIds()).isEmpty();
        assertThat(firstRecreation.getComparisonDependencyCandidateIds())
                .extracting(JsonNode::asText)
                .containsExactly(firstRemoval.getId().toString());
        assertThat(secondRemoval.getComparisonDependencyCandidateIds())
                .extracting(JsonNode::asText)
                .containsExactly(
                        firstRemoval.getId().toString(),
                        firstRecreation.getId().toString()
                );
        assertThat(secondRecreation.getComparisonDependencyCandidateIds())
                .extracting(JsonNode::asText)
                .containsExactly(
                        firstRemoval.getId().toString(),
                        firstRecreation.getId().toString(),
                        secondRemoval.getId().toString()
                );
    }

    @Test
    @DisplayName("앞 묶음이 만든 slot 부재도 뒤에 도착한 후보의 ADD 의존성으로 이어진다")
    void boundedPriorRemovalCarriesAbsenceDependencyToNextBatch() {
        SettingCandidate removal = candidate("status.오른발_부상", "오른발 부상이 회복됨", 10);

        WorkerCharacterFactComparisonBatchPayload firstClaim = worker.claimNext(
                analysisJobId,
                leaseToken
        ).orElseThrow();
        WorkerCharacterFactComparisonBatchContextResponse firstContext = worker.getContext(
                analysisJobId,
                firstClaim.comparisonBatchId(),
                leaseToken
        );
        worker.complete(
                analysisJobId,
                firstClaim.comparisonBatchId(),
                leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(
                        firstContext.contextToken(),
                        List.of(remove(
                                "C1",
                                "status.오른발_부상",
                                List.of(snapshotRef(firstContext, "status.오른발_부상")),
                                List.of()
                        )),
                        List.of(),
                        Map.of("fixture", "bounded-remove")
                )
        );

        SettingCandidate recreation = candidate("status.오른발_부상", "오른발을 다시 다침", 20);

        WorkerCharacterFactComparisonBatchPayload secondClaim = worker.claimNext(
                analysisJobId,
                leaseToken
        ).orElseThrow();
        WorkerCharacterFactComparisonBatchContextResponse secondContext = worker.getContext(
                analysisJobId,
                secondClaim.comparisonBatchId(),
                leaseToken
        );
        assertThat(secondContext.snapshotEntries())
                .extracting(WorkerCharacterFactComparisonBatchContextResponse.SnapshotEntry::factKey)
                .doesNotContain("status.오른발_부상");
        worker.complete(
                analysisJobId,
                secondClaim.comparisonBatchId(),
                leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(
                        secondContext.contextToken(),
                        List.of(add("C1", "status.오른발_부상", "오른발을 다시 다침")),
                        List.of(),
                        Map.of("fixture", "bounded-recreate")
                )
        );

        assertThat(recreation.getComparisonDependencyCandidateIds())
                .extracting(JsonNode::asText)
                .containsExactly(removal.getId().toString());
    }

    @Test
    @DisplayName("현재 상태를 추가하면서 다른 slot을 제거해도 제거 slot의 부재 의존성을 남긴다")
    void upsertWithRemovalCarriesAbsenceDependencyForRemovedSlot() {
        SettingCandidate recovery = candidate("status.회복_중", "신체가 빠르게 재생 중", 10);
        SettingCandidate injury = candidate("status.오른발_부상", "오른발을 다시 다침", 20);
        WorkerCharacterFactComparisonBatchPayload claim = worker.claimNext(
                analysisJobId,
                leaseToken
        ).orElseThrow();
        WorkerCharacterFactComparisonBatchContextResponse context = worker.getContext(
                analysisJobId,
                claim.comparisonBatchId(),
                leaseToken
        );

        worker.complete(
                analysisJobId,
                claim.comparisonBatchId(),
                leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(
                        context.contextToken(),
                        List.of(
                                decision(
                                        "C1",
                                        CharacterFactOperation.ADD,
                                        "status.회복_중",
                                        null,
                                        List.of(snapshotRef(context, "status.오른발_부상")),
                                        List.of(),
                                        "신체가 빠르게 재생 중",
                                        valueMap("신체가 빠르게 재생 중")
                                ),
                                add(
                                        "C2",
                                        "status.오른발_부상",
                                        List.of("C1"),
                                        "오른발을 다시 다침"
                                )
                        ),
                        List.of(),
                        Map.of("fixture", "upsert-with-remove")
                )
        );

        assertThat(recovery.getComparisonDependencyCandidateIds()).isEmpty();
        assertThat(injury.getComparisonDependencyCandidateIds())
                .extracting(JsonNode::asText)
                .containsExactly(recovery.getId().toString());
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
    @DisplayName("후보 25개는 전체 문맥과 원문 순서를 유지한 하나의 논리 묶음으로 claim한다")
    void fullGroupClaimHasNoCountOrCharacterCap() {
        ObjectNode state = orderedState(1);
        ObjectNode slot = (ObjectNode) state.path("slots").path("STATUS:status.앞회차_0");
        String existing = "기".repeat(40_000);
        slot.put("factValue", existing);
        slot.set("valueJson", value(existing));
        for (int index = 1; index <= 25; index++) {
            candidate("status.상태_" + index, "상태 " + index, index * 10);
        }
        var originalEvidence = candidates.stream().map(row -> row.getEvidenceSpans().deepCopy()).toList();
        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        var context = worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken);

        assertThat(claim.candidates()).hasSize(25);
        assertThat(claim.candidates()).extracting(WorkerCharacterFactComparisonBatchPayload.Candidate::rawFactKey)
                .containsExactlyElementsOf(candidates.stream().map(SettingCandidate::getAttributeName).toList());
        assertThat(context.candidates()).hasSize(25);
        assertThat(context.snapshotEntries().getFirst().factValue()).isEqualTo(existing);
        assertThat(candidates).allMatch(row -> row.getComparisonStatus() == CharacterFactComparisonStatus.PROCESSING);
        assertThat(candidates.stream().map(SettingCandidate::getEvidenceSpans).toList()).isEqualTo(originalEvidence);
        assertThat(batches).hasSize(1);
        var decisions = java.util.stream.IntStream.rangeClosed(1, 25)
                .mapToObj(index -> historyOnly("C" + index, "status.상태_" + index)).toList();
        worker.complete(analysisJobId, claim.comparisonBatchId(), leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(context.contextToken(), decisions, List.of(), Map.of()));
        assertThat(candidates).allMatch(row -> row.getComparisonStatus() == CharacterFactComparisonStatus.COMPLETED);
        assertThat(worker.claimNext(analysisJobId, leaseToken)).isEmpty();
    }

    @Test
    @DisplayName("자동 누적 분석은 잘못된 후보만 보류하고 같은 인물의 앞뒤 정상 후보를 계속 비교한다")
    void automaticOrderedInvalidMemberDoesNotBlockIndependentCandidates() {
        orderedState(1);
        ReflectionTestUtils.setField(analysisJob, "reviewMode",
                org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC);
        SettingCandidate first = candidate("status.정상", "앞 상태", 10);
        SettingCandidate invalid = candidate("status.잘못된값", "잘못된 값", 20);
        ReflectionTestUtils.setField(invalid, "valueJson", value("잘못된 값").put("active", "not_boolean"));
        SettingCandidate later = candidate("status.후속", "뒤 상태", 30);

        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();

        assertThat(claim.candidates()).extracting(WorkerCharacterFactComparisonBatchPayload.Candidate::rawFactKey)
                .containsExactly(first.getAttributeName(), later.getAttributeName());
        assertThat(invalid.canDeferFailedComparison()).isTrue();
        assertThat(invalid.getPreparationFailureStage()).isEqualTo(
                org.monitoring.catchholebackend.domain.analysis.type.CandidatePreparationFailureStage.COMPARISON_PREPARATION);
        assertThat(invalid.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
        assertThat(invalid.getCharacterComparisonBatch()).isNull();
    }

    @Test
    @DisplayName("긴 후보와 필수 기존 문맥은 Java에서 보류하거나 자르지 않고 AI에 함께 전달한다")
    void largeCandidateAndMandatoryContextAreDelegatedWithoutTrimming() {
        ObjectNode state = orderedState(1);
        JsonNode originalState = state.deepCopy();
        ReflectionTestUtils.setField(analysisJob, "reviewMode",
                org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC);
        SettingCandidate oversized = candidate("status.초장문", "긴 상태 ".repeat(10000), 10);
        SettingCandidate later = candidate("status.후속", "뒤 상태", 20);
        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        var context = worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken);
        assertThat(claim.candidates()).extracting(WorkerCharacterFactComparisonBatchPayload.Candidate::rawFactKey)
                .containsExactly(oversized.getAttributeName(), later.getAttributeName());
        assertThat(context.candidates().getFirst().attributeValue()).isEqualTo(oversized.getAttributeValue());
        assertThat(oversized.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PROCESSING);
        assertThat(oversized.getAutomaticReviewHoldReason()).isNull();
        assertThat(state).isEqualTo(originalState);
    }

    @Test
    @DisplayName("누적 claim의 첫 canonical 검증 실패는 후속 정상 후보를 건너뛰지 않는다")
    void orderedInvalidSeedStopsBeforeLaterCandidates() {
        orderedState(1);
        SettingCandidate invalid = candidate("status.잘못된값", "잘못된 값", 10);
        ReflectionTestUtils.setField(invalid, "valueJson", value("잘못된 값").put("active", "not_boolean"));
        SettingCandidate later = candidate("status.후속", "후속 상태", 20);

        assertThatThrownBy(() -> worker.claimNext(analysisJobId, leaseToken))
                .isInstanceOf(OrderedCharacterComparisonClaimException.class);

        assertThat(invalid.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.FAILED);
        assertThat(later.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PENDING);
        assertThat(batches).isEmpty();
    }

    @Test
    @DisplayName("누적 묶음 내부의 invalid 후보가 있으면 앞뒤 정상 후보 모두 미실행 상태를 유지한다")
    void orderedInvalidGroupMemberDoesNotCreatePartialBatch() {
        orderedState(1);
        SettingCandidate first = candidate("status.정상", "앞 상태", 10);
        SettingCandidate invalid = candidate("status.잘못된값", "잘못된 값", 20);
        ReflectionTestUtils.setField(invalid, "valueJson", value("잘못된 값").put("active", "not_boolean"));
        SettingCandidate later = candidate("status.후속", "뒤 상태", 30);

        assertThatThrownBy(() -> worker.claimNext(analysisJobId, leaseToken))
                .isInstanceOf(OrderedCharacterComparisonClaimException.class);

        assertThat(invalid.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.FAILED);
        assertThat(List.of(first, later)).allMatch(candidate ->
                candidate.getComparisonStatus() == CharacterFactComparisonStatus.PENDING);
        assertThat(batches).isEmpty();
    }

    @Test
    @DisplayName("기존 실패 후보가 남은 누적 claim은 빈 성공 응답으로 위장하지 않는다")
    void orderedExistingFailureIsNotAnEmptySuccessfulClaim() {
        orderedState(1);
        SettingCandidate failed = candidate("status.실패", "실패 상태", 10);
        failed.failComparison(AnalysisFailureCode.COMPARISON_VALIDATION_FAILED, "처리 실패");
        SettingCandidate later = candidate("status.후속", "후속 상태", 20);
        when(candidateRepository.findAllByAnalysisJobIdAndComparisonStatusIn(
                analysisJobId, List.of(CharacterFactComparisonStatus.FAILED))).thenReturn(List.of(failed));

        assertThatThrownBy(() -> worker.claimNext(analysisJobId, leaseToken))
                .isInstanceOf(OrderedCharacterComparisonClaimException.class);

        assertThat(later.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PENDING);
        assertThat(batches).isEmpty();
    }

    @Test
    @DisplayName("실패와 대기 후보가 모두 없는 누적 claim만 정상 빈 응답을 반환한다")
    void orderedCleanCompletionCanReturnEmptyClaim() {
        orderedState(1);

        assertThat(worker.claimNext(analysisJobId, leaseToken)).isEmpty();

        assertThat(batches).isEmpty();
    }

    @Test
    @DisplayName("완료 묶음의 EXCLUDE 자동 무시는 형제 후보의 문맥 membership을 바꾸지 않는다")
    void excludedCandidateKeepsCompletedBatchMembership() {
        SettingCandidate repeated = candidate("status.반복", "이미 알려진 상태", 10);
        SettingCandidate added = candidate("status.신규", "새 상태", 20);
        WorkerCharacterFactComparisonBatchPayload claim = worker.claimNext(
                analysisJobId,
                leaseToken
        ).orElseThrow();
        WorkerCharacterFactComparisonBatchContextResponse context = worker.getContext(
                analysisJobId,
                claim.comparisonBatchId(),
                leaseToken
        );

        worker.complete(
                analysisJobId,
                claim.comparisonBatchId(),
                leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(
                        context.contextToken(),
                        List.of(
                                decision(
                                        "C1",
                                        CharacterFactOperation.EXCLUDE,
                                        "status.반복",
                                        null,
                                        List.of(),
                                        List.of(),
                                        null,
                                        null
                                ),
                                add("C2", "status.신규", "새 상태")
                        ),
                        List.of(),
                        Map.of()
                )
        );

        assertThat(repeated.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.DISMISSED);
        assertThat(repeated.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.NOT_REQUIRED);
        assertThat(repeated.getCharacterComparisonBatch()).isNotNull();
        assertThat(repeated.getCharacterComparisonCandidateRef()).isEqualTo("C1");
        assertThat(worker.hasCurrentContext(added)).isTrue();
    }

    @Test
    @DisplayName("비파괴 결정은 관련 없는 FactType의 snapshot 변경 뒤에도 현재 문맥을 유지한다")
    void valueOnlyDecisionIgnoresUnrelatedSnapshotVersionChange() {
        when(schemaRepository.findAllActiveForWork(work.getId())).thenReturn(List.of(profileSchema()));
        ObjectNode profile = objectMapper.createObjectNode();
        profile.set("profile.species", value("바바리안"));
        character.replaceCurrentSnapshots(null, null, profile, null, null, null, character.getStatusesJson());
        SettingCandidate species = candidate("profile.species", "바바리안", 10);
        CharacterFactComparisonBatch legacy = batchRepository.saveAndFlush(CharacterFactComparisonBatch.create(
                work, null, analysisJob, character, CharacterFactType.PROFILE, 1, character.getSnapshotVersion()));
        species.startComparison(legacy, "C1");
        WorkerCharacterFactComparisonBatchContextResponse context = worker.getContext(
                analysisJobId,
                legacy.getId(),
                leaseToken
        );

        worker.complete(
                analysisJobId,
                legacy.getId(),
                leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(
                        context.contextToken(),
                        List.of(update(
                                "C1",
                                "profile.species",
                                snapshotRef(context, "profile.species"),
                                List.of(),
                                "바바리안"
                        )),
                        List.of(),
                        Map.of()
                )
        );
        ObjectNode changedStatuses = character.getStatusesJson().deepCopy();
        changedStatuses.set("status.새상태", value("관련 없는 변경"));
        character.replaceCurrentSnapshots(null, null, profile, null, null, null, changedStatuses);

        assertThat(worker.hasCurrentContext(species)).isTrue();
    }

    @Test
    @DisplayName("30건 STATUS 문맥은 exact slot 뒤 최근 근거 상태를 우선한다")
    void statusContextPrioritizesRecentSources() {
        ObjectNode statuses = objectMapper.createObjectNode();
        List<CharacterSnapshotSource> sources = new ArrayList<>();
        LocalDateTime base = LocalDateTime.of(2026, 9, 1, 0, 0);
        for (int index = 0; index < 31; index++) {
            String key = "status.%02d".formatted(index);
            statuses.set(key, value("상태 " + index));
            CharacterFact fact = CharacterFact.createManual(
                    character,
                    CharacterFactType.STATUS,
                    key,
                    "상태 " + index,
                    value("상태 " + index)
            );
            ReflectionTestUtils.setField(fact, "createdAt", base.plusMinutes(index));
            sources.add(CharacterSnapshotSource.create(
                    character,
                    CharacterFactType.STATUS,
                    key,
                    fact,
                    0
            ));
        }
        character.replaceCurrentSnapshots(null, null, null, null, null, null, statuses);
        when(snapshotSourceRepository.findAllByWorkCharacterIdOrderByFactTypeAscFactKeyAscSourceOrderAsc(
                character.getId()
        )).thenReturn(sources);
        SettingCandidate recovery = candidate("status.회복", "회복됨", 10);
        CharacterFactComparisonBatch legacy = batchRepository.saveAndFlush(CharacterFactComparisonBatch.create(
                work, null, analysisJob, character, CharacterFactType.STATUS, 1, character.getSnapshotVersion()));
        recovery.startComparison(legacy, "C1");
        WorkerCharacterFactComparisonBatchContextResponse context = worker.getContext(
                analysisJobId,
                legacy.getId(),
                leaseToken
        );

        assertThat(context.snapshotEntries()).hasSize(30);
        assertThat(context.snapshotEntries())
                .extracting(WorkerCharacterFactComparisonBatchContextResponse.SnapshotEntry::factKey)
                .contains("status.30")
                .doesNotContain("status.00");
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
    @DisplayName("과거 묶음의 참조 순서는 새 원문 순서와 반대여도 문맥·완료·수동 검토에 유지된다")
    void preservesAssignedBatchOrderAcrossContextCompletionAndReview() {
        SettingCandidate earlier = candidate("status.생명력", "생명력 2%", 10);
        SettingCandidate later = candidate("status.생명력", "생명력 5%", 20);
        CharacterFactComparisonBatch batch = batchRepository.saveAndFlush(CharacterFactComparisonBatch.create(
                work, null, analysisJob, character, CharacterFactType.STATUS, 2, character.getSnapshotVersion()));
        // 이전 런타임이 UUID 순서로 만들었던 배정을 실제 저장 형태 그대로 재현한다.
        later.startComparison(batch, "C1");
        earlier.startComparison(batch, "C2");
        var context = worker.getContext(analysisJobId, batch.getId(), leaseToken);
        assertThat(context.candidates()).extracting(WorkerCharacterFactComparisonBatchPayload.Candidate::attributeValue)
                .containsExactly("생명력 5%", "생명력 2%");
        assertThat(worker.getContext(analysisJobId, batch.getId(), leaseToken).contextToken())
                .isEqualTo(context.contextToken());
        worker.complete(analysisJobId, batch.getId(), leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(context.contextToken(), List.of(
                        add("C1", "status.생명력", "생명력 5%"),
                        update("C2", "status.생명력", "Q1", List.of("C1"), "생명력 2%")),
                        List.of(), Map.of()));
        assertThat(earlier.getComparisonDependencyCandidateIds())
                .extracting(JsonNode::asText).containsExactly(later.getId().toString());
        assertThat(worker.hasCurrentContext(earlier)).isTrue();
        assertThat(worker.hasCurrentContext(later)).isTrue();
        assertThat(later.getCharacterComparisonCandidateRef()).isEqualTo("C1");
        assertThat(earlier.getCharacterComparisonCandidateRef()).isEqualTo("C2");
    }

    @Test
    @DisplayName("과거 참조 순서를 보존해도 묶음 참조 누락은 계속 거절한다")
    void rejectsBrokenAssignedBatchReferences() {
        SettingCandidate first = candidate("status.출혈", "출혈", 10);
        SettingCandidate second = candidate("status.생명력", "생명력 5%", 20);
        var claim = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        ReflectionTestUtils.setField(second, "characterComparisonCandidateRef", "C3");
        assertThatThrownBy(() -> worker.getContext(analysisJobId, claim.comparisonBatchId(), leaseToken))
                .isInstanceOfSatisfying(AppException.class, exception -> assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_WORKER_JOB_INVALID));
        assertThat(first.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PROCESSING);
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
    @DisplayName("숨김 그룹은 원문 순서의 모든 FactType을 한 번에 비교하며 입력 hash를 보존한다")
    void interleavedFactTypesKeepTheOriginalJobInputThroughoutComparison() throws Exception {
        when(schemaRepository.findAllActiveForWork(work.getId()))
                .thenReturn(List.of(statusSchema(), profileSchema()));
        SettingCandidate injury = candidate("status.새부상", "부상", 10);
        SettingCandidate species = candidate("profile.species", "인간", 20);
        SettingCandidate bleeding = candidate("status.새출혈", "출혈", 30);
        // 이 회귀는 Job coordinator의 실제 hash 검증도 통과해야 한다.
        when(comparisonJobCoordinator.inputHash(any())).thenCallRealMethod();
        when(comparisonJobCoordinator.hasCurrentInput(any(), any())).thenCallRealMethod();
        analysisJob = AnalysisJob.createCharacterFactComparison(
                injury, comparisonJobCoordinator.inputHash(candidates));
        analysisJobId = UUID.randomUUID();
        ReflectionTestUtils.setField(analysisJob, "id", analysisJobId);
        when(analysisJobLeaseService.getRunningAnalysisJobForUpdate(analysisJobId, leaseToken))
                .thenReturn(analysisJob);
        when(comparisonJobCoordinator.lockScopeCandidates(analysisJob)).thenReturn(candidates);
        when(batchRepository.findByIdAndAnalysisJobIdForUpdate(any(UUID.class), eq(analysisJobId)))
                .thenAnswer(invocation -> Optional.ofNullable(batches.get(invocation.getArgument(0))));

        var first = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        var firstContext = worker.getContext(analysisJobId, first.comparisonBatchId(), leaseToken);
        assertThat(first.canonicalFactType()).isNull();
        assertThat(first.candidates()).extracting(WorkerCharacterFactComparisonBatchPayload.Candidate::rawFactKey)
                .containsExactly("status.새부상", "profile.species", "status.새출혈");
        assertThat(objectMapper.valueToTree(first).path("candidates"))
                .extracting(value -> value.path("canonicalFactType").asText())
                .containsExactly("STATUS", "PROFILE", "STATUS");
        assertThat(objectMapper.valueToTree(firstContext).path("candidates"))
                .isEqualTo(objectMapper.valueToTree(first).path("candidates"));
        exportPersonBatch("confirmed", first, firstContext);
        worker.complete(analysisJobId, first.comparisonBatchId(), leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(firstContext.contextToken(),
                        List.of(add("C1", "status.새부상", "부상"), add("C2", "profile.species", "인간"),
                                add("C3", "status.새출혈", "출혈")),
                        List.of(), Map.of()));

        assertThat(List.of(injury, species, bleeding)).allSatisfy(candidate ->
                assertThat(candidate.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.COMPLETED));
        assertThat(worker.claimNext(analysisJobId, leaseToken)).isEmpty();
    }

    @Test
    @DisplayName("동명 캐릭터 ID는 분리하고 같은 인물의 서로 다른 FactType은 한 묶음으로 claim한다")
    void separatesSameNameCharactersAndCombinesFactTypes() {
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
        assertThat(first.canonicalFactType()).isNull();
        assertThat(first.candidates()).hasSize(2);
        assertThat(firstStatus.getCharacterComparisonBatch().getId()).isEqualTo(first.comparisonBatchId());
        assertThat(namesakeStatus.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PENDING);
        assertThat(firstProfile.getCharacterComparisonBatch().getId()).isEqualTo(first.comparisonBatchId());
        worker.fail(analysisJobId, first.comparisonBatchId(), leaseToken, failure);

        WorkerCharacterFactComparisonBatchPayload second = worker.claimNext(
                analysisJobId,
                leaseToken
        ).orElseThrow();
        assertThat(second.canonicalFactType()).isNull();
        assertThat(second.candidates()).hasSize(1);
        assertThat(namesakeStatus.getCharacterComparisonBatch().getId()).isEqualTo(second.comparisonBatchId());
        worker.fail(analysisJobId, second.comparisonBatchId(), leaseToken, failure);

        assertThat(worker.claimNext(analysisJobId, leaseToken)).isEmpty();
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

    @Test
    @DisplayName("인물 묶음 지원이 없는 Worker는 기존 타입별 배치를 차례로 claim한다")
    void legacyWorkerClaimsTypedBatchesAndBlocksUntilCurrentPersonBatchEnds() {
        when(schemaRepository.findAllActiveForWork(work.getId())).thenReturn(List.of(statusSchema(), profileSchema()));
        SettingCandidate status = candidate("status.새부상", "부상", 10);
        SettingCandidate profile = candidate("profile.species", "인간", 20);
        var first = claimWithCapability(false).orElseThrow();
        assertThat(first.canonicalFactType()).isEqualTo(CharacterFactType.STATUS);
        assertThat(first.candidates()).hasSize(1);
        assertThat(claimWithCapability(true)).isEmpty();
        assertThat(profile.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PENDING);
        var context = worker.getContext(analysisJobId, first.comparisonBatchId(), leaseToken);
        worker.complete(analysisJobId, first.comparisonBatchId(), leaseToken,
                new WorkerCharacterFactComparisonBatchCompleteRequest(context.contextToken(),
                        List.of(add("C1", "status.새부상", "부상")), List.of(), Map.of()));
        var second = claimWithCapability(false).orElseThrow();
        assertThat(second.canonicalFactType()).isEqualTo(CharacterFactType.PROFILE);
        assertThat(second.candidates()).hasSize(1);
        assertThat(status.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.COMPLETED);
    }

    @Test
    @DisplayName("동일 인물의 진행 중 배치는 새 타입 후보가 와도 중복 claim하지 않는다")
    void processingPersonBatchBlocksLaterCandidatesAcrossTypes() {
        when(schemaRepository.findAllActiveForWork(work.getId())).thenReturn(List.of(statusSchema(), profileSchema()));
        candidate("status.새부상", "부상", 10);
        var first = worker.claimNext(analysisJobId, leaseToken).orElseThrow();
        SettingCandidate profile = candidate("profile.species", "인간", 20);
        assertThat(worker.claimNext(analysisJobId, leaseToken)).isEmpty();
        assertThat(claimWithCapability(false)).isEmpty();
        assertThat(batches.get(first.comparisonBatchId()).isProcessing()).isTrue();
        assertThat(profile.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PENDING);
    }

    private Optional<WorkerCharacterFactComparisonBatchPayload> claimWithCapability(boolean personBatches) {
        return worker.claimNext(analysisJobId, leaseToken, personBatches);
    }

    private void exportPersonBatch(String prefix, WorkerCharacterFactComparisonBatchPayload claim,
            WorkerCharacterFactComparisonBatchContextResponse context) throws Exception {
        String output = System.getenv("GH251_CHARACTER_BATCH_OUTPUT");
        if (output == null) return;
        java.nio.file.Path directory = java.nio.file.Path.of(output);
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve(prefix + "-claim.json"), objectMapper.writeValueAsString(claim));
        java.nio.file.Files.writeString(directory.resolve(prefix + "-context.json"), objectMapper.writeValueAsString(context));
    }

    private List<SettingCandidate> pendingCandidates() {
        return candidates.stream()
                .filter(candidate -> candidate.getComparisonStatus() == CharacterFactComparisonStatus.PENDING)
                .toList();
    }

    private SettingCandidate unmatchedCandidate(
            AnalysisJob sourceJob,
            String key,
            String displayValue,
            boolean active,
            int evidenceOffset
    ) {
        SettingCandidate candidate = SettingCandidate.create(
                work,
                null,
                UUID.randomUUID(),
                sourceJob,
                SettingEntityType.CHARACTER,
                "아리아",
                "아리아",
                null,
                SettingCandidateMatchStatus.UNRESOLVED,
                key,
                displayValue,
                SettingValueType.JSON,
                objectMapper.createObjectNode().put("name", "의식 상실").put("active", active),
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
        return add(candidateRef, resolvedKey, List.of(), displayValue);
    }

    private WorkerCharacterFactComparisonBatchCompleteRequest.Decision add(
            String candidateRef,
            String resolvedKey,
            List<String> dependencies,
            String displayValue
    ) {
        return decision(
                candidateRef,
                CharacterFactOperation.ADD,
                resolvedKey,
                null,
                List.of(),
                dependencies,
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

    private String snapshotRef(
            WorkerCharacterFactComparisonBatchContextResponse context,
            String factKey
    ) {
        return context.snapshotEntries().stream()
                .filter(entry -> entry.factKey().equals(factKey))
                .map(WorkerCharacterFactComparisonBatchContextResponse.SnapshotEntry::snapshotRef)
                .findFirst()
                .orElseThrow();
    }

    private ObjectNode value(String value) {
        return objectMapper.createObjectNode().put("value", value);
    }
}
