package org.monitoring.catchholebackend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobEpisodeRange;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateCharacterMatchRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateConfirmRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateGroupConfirmDecision;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateGroupConfirmRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateListResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateReviewStatusResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateValueValidationResponse;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.mapper.SettingCandidateMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSettingValueValidator;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateSchemaResolver;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateValueValidation;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateBatchCounts;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactConfirmApplicationMode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateCharacterMatchResolutionType;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateKind;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateValueValidationStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
@DisplayName("설정 후보 Service 단위 테스트")
class SettingCandidateServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private WorkRepository workRepository;

    @Mock
    private UploadBatchRepository uploadBatchRepository;

    @Mock
    private AnalysisJobRepository analysisJobRepository;

    @Mock
    private SettingCandidateRepository settingCandidateRepository;

    @Mock
    private WorkCharacterRepository workCharacterRepository;

    @Mock
    private CharacterSettingSchemaRepository characterSettingSchemaRepository;

    @Mock
    private SettingCandidateMapper settingCandidateMapper;

    @Mock
    private SettingCandidatePromotionService settingCandidatePromotionService;

    @Mock
    private CharacterFactComparisonWorkerService characterFactComparisonWorkerService;

    @Mock
    private AiTokenService aiTokenService;

    private SettingCandidateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SettingCandidateServiceImpl(
                workRepository,
                uploadBatchRepository,
                analysisJobRepository,
                settingCandidateRepository,
                workCharacterRepository,
                characterSettingSchemaRepository,
                settingCandidateMapper,
                settingCandidatePromotionService,
                characterFactComparisonWorkerService,
                new SettingCandidateSchemaResolver(),
                new CharacterSettingValueValidator(),
                aiTokenService
        );
    }

    @Test
    @DisplayName("업로드 묶음 전체 집계와 필터된 후보 페이지를 함께 조회한다")
    void getSettingCandidatesReturnsBatchSummaryAndFilteredPage() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(work, "아리아", "age", "17");
        List<SettingCandidate> candidates = List.of(candidate);
        List<SettingCandidateResponse> responses = List.of(response(workId));
        PageRequest pageRequest = PageRequest.of(0, 20);
        SettingCandidateBatchCounts counts = org.mockito.Mockito.mock(SettingCandidateBatchCounts.class);
        AnalysisJobEpisodeRange episodeRange = org.mockito.Mockito.mock(AnalysisJobEpisodeRange.class);
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(uploadBatchRepository.findByIdAndWorkId(batchId, workId))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(UploadBatch.class)));
        when(settingCandidateRepository.findReviewCandidates(
                workId,
                batchId,
                SettingCandidateReviewStatus.PENDING_REVIEW,
                Set.of(SettingCandidateMatchStatus.AMBIGUOUS)
        )).thenReturn(candidates);
        when(settingCandidateRepository.findReviewPage(
                workId,
                batchId,
                SettingCandidateReviewStatus.PENDING_REVIEW,
                Set.of(SettingCandidateMatchStatus.AMBIGUOUS),
                pageRequest
        )).thenReturn(new PageImpl<>(candidates, pageRequest, 4));
        when(settingCandidateRepository.countReviewSummary(
                workId,
                batchId,
                SettingCandidateReviewStatus.PENDING_REVIEW,
                SettingCandidateMatchStatus.AMBIGUOUS
        )).thenReturn(counts);
        when(counts.getTotalCandidateCount()).thenReturn(4L);
        when(counts.getReviewedCandidateCount()).thenReturn(1L);
        when(counts.getPendingCandidateCount()).thenReturn(3L);
        when(counts.getMatchRequiredCandidateCount()).thenReturn(2L);
        when(analysisJobRepository.findEpisodeRangeByWorkIdAndBatchId(workId, batchId))
                .thenReturn(episodeRange);
        when(episodeRange.getEpisodeStartNo()).thenReturn(1);
        when(episodeRange.getEpisodeEndNo()).thenReturn(5);
        when(episodeRange.getEpisodeCount()).thenReturn(5L);
        when(characterSettingSchemaRepository.findAllActiveForWork(workId))
                .thenReturn(List.of(schema("age", null, CharacterFactType.AGE, SettingValueType.NUMBER)));
        when(settingCandidateMapper.toReviewListResponse(
                candidate,
                false,
                null,
                SettingCandidateValueValidation.valid()
        ))
                .thenReturn(responses.getFirst());

        SettingCandidateListResponse result = service.getSettingCandidates(
                memberId,
                workId,
                batchId,
                SettingCandidateReviewStatus.PENDING_REVIEW,
                Set.of(SettingCandidateMatchStatus.AMBIGUOUS),
                0,
                20,
                true
        );

        assertThat(result.batchId()).isEqualTo(batchId);
        assertThat(result.episodeStartNo()).isEqualTo(1);
        assertThat(result.episodeEndNo()).isEqualTo(5);
        assertThat(result.episodeCount()).isEqualTo(5);
        assertThat(result.totalCandidateCount()).isEqualTo(4);
        assertThat(result.reviewedCandidateCount()).isEqualTo(1);
        assertThat(result.pendingCandidateCount()).isEqualTo(3);
        assertThat(result.matchRequiredCandidateCount()).isEqualTo(2);
        assertThat(result.groups().content()).hasSize(1);
        assertThat(result.groups().content().getFirst().groupKey()).isEqualTo("아리아");
        assertThat(result.groups().content().getFirst().candidates()).containsExactlyElementsOf(responses);
        assertThat(result.candidates().content()).containsExactlyElementsOf(responses);
        verify(settingCandidateRepository).findReviewCandidates(
                workId,
                batchId,
                SettingCandidateReviewStatus.PENDING_REVIEW,
                Set.of(SettingCandidateMatchStatus.AMBIGUOUS)
        );
        verify(settingCandidateRepository).findReviewPage(
                workId,
                batchId,
                SettingCandidateReviewStatus.PENDING_REVIEW,
                Set.of(SettingCandidateMatchStatus.AMBIGUOUS),
                pageRequest
        );
    }

    @Test
    @DisplayName("후보 그룹은 페이지를 자른 뒤 현재 snapshot 응답으로 변환한다")
    void getSettingCandidatesMapsOnlyRequestedGroupPage() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate firstGroupCandidate = candidate(work, "아리아", "age", "17");
        SettingCandidate secondGroupCandidate = candidate(work, "비요른", "level", "3");
        SettingCandidateBatchCounts counts = org.mockito.Mockito.mock(SettingCandidateBatchCounts.class);
        AnalysisJobEpisodeRange episodeRange = org.mockito.Mockito.mock(AnalysisJobEpisodeRange.class);

        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(uploadBatchRepository.findByIdAndWorkId(batchId, workId))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(UploadBatch.class)));
        when(settingCandidateRepository.findReviewCandidates(
                workId,
                batchId,
                SettingCandidateReviewStatus.PENDING_REVIEW,
                Set.of(SettingCandidateMatchStatus.AMBIGUOUS)
        )).thenReturn(List.of(firstGroupCandidate, secondGroupCandidate));
        when(settingCandidateRepository.countReviewSummary(
                workId,
                batchId,
                SettingCandidateReviewStatus.PENDING_REVIEW,
                SettingCandidateMatchStatus.AMBIGUOUS
        )).thenReturn(counts);
        when(analysisJobRepository.findEpisodeRangeByWorkIdAndBatchId(workId, batchId))
                .thenReturn(episodeRange);
        when(characterSettingSchemaRepository.findAllActiveForWork(workId)).thenReturn(List.of());
        SettingCandidateResponse firstResponse = response(workId);
        when(settingCandidateMapper.toReviewListResponse(
                firstGroupCandidate,
                false,
                null,
                SettingCandidateValueValidation.unrepairableInvalid(
                        CharacterErrorCode.SETTING_CANDIDATE_SCHEMA_NOT_MATCHED
                )
        ))
                .thenReturn(firstResponse);

        SettingCandidateListResponse result = service.getSettingCandidates(
                memberId,
                workId,
                batchId,
                SettingCandidateReviewStatus.PENDING_REVIEW,
                Set.of(SettingCandidateMatchStatus.AMBIGUOUS),
                0,
                1,
                false
        );

        assertThat(result.groups().content())
                .singleElement()
                .extracting(group -> group.entityName())
                .isEqualTo("아리아");
        verify(settingCandidateMapper).toReviewListResponse(
                firstGroupCandidate,
                false,
                null,
                SettingCandidateValueValidation.unrepairableInvalid(
                        CharacterErrorCode.SETTING_CANDIDATE_SCHEMA_NOT_MATCHED
                )
        );
        verify(settingCandidateMapper, never()).toReviewListResponse(
                secondGroupCandidate,
                false,
                null,
                SettingCandidateValueValidation.valid()
        );
    }

    @Test
    @DisplayName("다른 작품이거나 존재하지 않는 업로드 묶음은 찾을 수 없음으로 숨긴다")
    void getSettingCandidatesRejectsBatchOutsideWork() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        Work work = work(workId);
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(uploadBatchRepository.findByIdAndWorkId(batchId, workId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSettingCandidates(
                memberId,
                workId,
                batchId,
                null,
                null,
                0,
                20,
                true
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_BATCH_NOT_FOUND));

        verifyNoInteractions(settingCandidateRepository, analysisJobRepository);
    }

    @Test
    @DisplayName("작품 안에서 후보를 찾지 못하면 예외를 던진다")
    void getSettingCandidateRejectsMissingCandidateInWork() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdAndAnalysisJobBatchId(candidateId, workId, batchId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSettingCandidate(memberId, workId, batchId, candidateId))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_NOT_FOUND));
    }

    @Test
    @DisplayName("조회 시 숫자가 아닌 NUMBER 표시값을 INVALID로 파생한다")
    void getSettingCandidateDerivesInvalidNumberDisplayValue() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(
                work,
                "아리아",
                "stats.strength",
                "매우 강함",
                SettingValueType.NUMBER,
                objectMapper.createObjectNode().put("value", 17)
        );
        SettingCandidateResponse response = response(workId);
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdAndAnalysisJobBatchId(
                candidateId,
                workId,
                batchId
        )).thenReturn(Optional.of(candidate));
        when(characterSettingSchemaRepository.findAllActiveForWork(workId)).thenReturn(List.of(
                schema("stats.strength", null, CharacterFactType.STAT, SettingValueType.NUMBER)
        ));
        when(settingCandidateMapper.toResponse(
                candidate,
                false,
                null,
                SettingCandidateValueValidation.invalid(
                        CharacterErrorCode.SETTING_CANDIDATE_VALUE_FORMAT_INVALID
                )
        )).thenReturn(response);

        SettingCandidateResponse result = service.getSettingCandidate(
                memberId,
                workId,
                batchId,
                candidateId
        );

        assertThat(result).isSameAs(response);
    }

    @Test
    @DisplayName("검토 대기 후보의 보정 가능 필드를 수정한다")
    void updateSettingCandidateUpdatesPendingCandidate() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(work, "아리아", "age", "17");
        JsonNode originalEvidenceSpans = candidate.getEvidenceSpans();
        SettingCandidateResponse response = response(workId);
        SettingCandidateUpdateRequest request = new SettingCandidateUpdateRequest(
                "  age  ",
                "  23  "
        );
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(characterSettingSchemaRepository.findAllActiveForWork(workId))
                .thenReturn(List.of(schema("age", null, CharacterFactType.AGE, SettingValueType.NUMBER)));
        when(settingCandidateMapper.toResponse(
                any(SettingCandidate.class),
                anyBoolean(),
                nullable(String.class),
                any(SettingCandidateValueValidation.class)
        ))
                .thenReturn(response);

        SettingCandidateResponse result = service.updateSettingCandidate(memberId, workId, candidateId, request);

        assertThat(result).isSameAs(response);
        assertThat(candidate.getEntityName()).isEqualTo("아리아");
        assertThat(candidate.getMatchedCharacterId()).isNull();
        assertThat(candidate.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.UNRESOLVED);
        assertThat(candidate.getAttributeName()).isEqualTo("age");
        assertThat(candidate.getAttributeValue()).isEqualTo("23");
        assertThat(candidate.getValueJson().get("value").asInt()).isEqualTo(23);
        assertThat(candidate.getValueJson()).hasToString("{\"value\":23}");
        assertThat(candidate.getValueType()).isEqualTo(SettingValueType.NUMBER);
        assertThat(candidate.getEvidenceSpans()).isSameAs(originalEvidenceSpans);
        assertThat(candidate.getRawAiResultJson().get("raw_value").asText()).isEqualTo("17");
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("설정명과 값이 같으면 복합 valueJson과 근거를 그대로 보존한다")
    void updateSettingCandidatePreservesRichJsonWhenContentIsUnchanged() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        JsonNode valueJson = objectMapper.createObjectNode()
                .put("name", "화염 검술")
                .put("level", 5)
                .put("effect", "화염 공격");
        SettingCandidate candidate = candidate(
                work,
                "아리아",
                " skill.화염 검술 ",
                " Lv.5 ",
                SettingValueType.JSON,
                valueJson
        );
        JsonNode originalEvidenceSpans = candidate.getEvidenceSpans();
        JsonNode originalRawAiResult = candidate.getRawAiResultJson();
        SettingCandidateResponse response = response(workId);
        SettingCandidateUpdateRequest request = new SettingCandidateUpdateRequest(
                " skill.화염 검술 ",
                " Lv.5 "
        );
        CharacterSettingSchema schema =
                schema("skills.skill", "skill.*", CharacterFactType.SKILL, SettingValueType.JSON);
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(characterSettingSchemaRepository.findAllActiveForWork(workId)).thenReturn(List.of(schema));
        when(settingCandidateMapper.toResponse(
                any(SettingCandidate.class),
                anyBoolean(),
                nullable(String.class),
                any(SettingCandidateValueValidation.class)
        ))
                .thenReturn(response);

        service.updateSettingCandidate(memberId, workId, candidateId, request);

        assertThat(candidate.getAttributeName()).isEqualTo("skill.화염_검술");
        assertThat(candidate.getAttributeValue()).isEqualTo("Lv.5");
        assertThat(candidate.getValueJson()).isSameAs(valueJson);
        assertThat(candidate.getEvidenceSpans()).isSameAs(originalEvidenceSpans);
        assertThat(candidate.getRawAiResultJson()).isSameAs(originalRawAiResult);
        verify(settingCandidateMapper).toResponse(
                candidate,
                true,
                "skill.",
                SettingCandidateValueValidation.valid()
        );
    }

    @Test
    @DisplayName("나이의 숨은 scalar가 문자열이면 같은 표시값 저장도 숫자 envelope로 수리한다")
    void updateSettingCandidateRepairsInvalidCoreScalarWhenVisibleValueIsUnchanged() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(
                work,
                "아리아",
                "age",
                "17",
                SettingValueType.NUMBER,
                objectMapper.createObjectNode()
                        .put("value", "17")
                        .put("unit", "years")
        );
        JsonNode originalEvidenceSpans = candidate.getEvidenceSpans();
        JsonNode originalRawAiResult = candidate.getRawAiResultJson();
        SettingCandidateResponse response = response(workId);
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(characterSettingSchemaRepository.findAllActiveForWork(workId))
                .thenReturn(List.of(schema("age", null, CharacterFactType.AGE, SettingValueType.NUMBER)));
        when(settingCandidateMapper.toResponse(
                candidate,
                false,
                null,
                SettingCandidateValueValidation.valid()
        )).thenReturn(response);

        service.updateSettingCandidate(
                memberId,
                workId,
                candidateId,
                new SettingCandidateUpdateRequest("age", "17")
        );

        assertThat(candidate.getValueJson()).hasToString("{\"value\":17}");
        assertThat(candidate.getValueJson().get("value").isNumber()).isTrue();
        assertThat(candidate.getEvidenceSpans()).isSameAs(originalEvidenceSpans);
        assertThat(candidate.getRawAiResultJson()).isSameAs(originalRawAiResult);
    }

    @Test
    @DisplayName("잘못된 일반 NUMBER 후보는 숫자 표시값으로 수정해 typed envelope를 복구한다")
    void updateSettingCandidateRepairsInvalidStatDisplayValue() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(
                work,
                "아리아",
                "stats.strength",
                "매우 강함",
                SettingValueType.NUMBER,
                objectMapper.createObjectNode().put("value", 17)
        );
        SettingCandidateResponse response = response(workId);
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId))
                .thenReturn(Optional.of(candidate));
        when(characterSettingSchemaRepository.findAllActiveForWork(workId)).thenReturn(List.of(
                schema("stats.strength", null, CharacterFactType.STAT, SettingValueType.NUMBER)
        ));
        when(settingCandidateMapper.toResponse(
                candidate,
                false,
                null,
                SettingCandidateValueValidation.valid()
        )).thenReturn(response);

        SettingCandidateResponse result = service.updateSettingCandidate(
                memberId,
                workId,
                candidateId,
                new SettingCandidateUpdateRequest("stats.strength", "17")
        );

        assertThat(result).isSameAs(response);
        assertThat(candidate.getAttributeValue()).isEqualTo("17");
        assertThat(candidate.getValueJson().get("value").isNumber()).isTrue();
        assertThat(candidate.getValueJson().get("value").decimalValue())
                .isEqualByComparingTo("17");
    }

    @Test
    @DisplayName("나이의 숨은 scalar가 숫자이면 같은 표시값 저장에서 rich JSON을 보존한다")
    void updateSettingCandidatePreservesTypedCoreRichJsonWhenVisibleValueIsUnchanged() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        JsonNode valueJson = objectMapper.createObjectNode()
                .put("value", 17)
                .put("unit", "years");
        SettingCandidate candidate = candidate(
                work,
                "아리아",
                "age",
                "17",
                SettingValueType.NUMBER,
                valueJson
        );
        JsonNode originalEvidenceSpans = candidate.getEvidenceSpans();
        JsonNode originalRawAiResult = candidate.getRawAiResultJson();
        SettingCandidateResponse response = response(workId);
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(characterSettingSchemaRepository.findAllActiveForWork(workId))
                .thenReturn(List.of(schema("age", null, CharacterFactType.AGE, SettingValueType.NUMBER)));
        when(settingCandidateMapper.toResponse(
                candidate,
                false,
                null,
                SettingCandidateValueValidation.valid()
        )).thenReturn(response);

        service.updateSettingCandidate(
                memberId,
                workId,
                candidateId,
                new SettingCandidateUpdateRequest("age", "17")
        );

        assertThat(candidate.getValueJson()).isSameAs(valueJson);
        assertThat(candidate.getEvidenceSpans()).isSameAs(originalEvidenceSpans);
        assertThat(candidate.getRawAiResultJson()).isSameAs(originalRawAiResult);
    }

    @Test
    @DisplayName("표시값이 null인 JSON 후보를 그대로 저장하면 rich valueJson을 유지한다")
    void updateSettingCandidatePreservesRichJsonWhenNullableValueIsUnchanged() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        JsonNode valueJson = objectMapper.createObjectNode()
                .put("name", "화염 검술")
                .put("level", 5)
                .put("effect", "화염 공격");
        SettingCandidate candidate = candidate(
                work,
                "아리아",
                "skill.화염_검술",
                null,
                SettingValueType.JSON,
                valueJson
        );
        CharacterSettingSchema schema =
                schema("skills.skill", "skill.*", CharacterFactType.SKILL, SettingValueType.JSON);
        SettingCandidateResponse response = response(workId);
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(characterSettingSchemaRepository.findAllActiveForWork(workId)).thenReturn(List.of(schema));
        when(settingCandidateMapper.toResponse(
                candidate,
                true,
                "skill.",
                SettingCandidateValueValidation.valid()
        )).thenReturn(response);

        service.updateSettingCandidate(
                memberId,
                workId,
                candidateId,
                new SettingCandidateUpdateRequest("skill.화염_검술", null)
        );

        assertThat(candidate.getAttributeValue()).isNull();
        assertThat(candidate.getValueJson()).isSameAs(valueJson);
        verify(settingCandidateMapper).toResponse(
                candidate,
                true,
                "skill.",
                SettingCandidateValueValidation.valid()
        );
    }

    @Test
    @DisplayName("동적 JSON 후보를 수정하면 같은 prefix의 key와 name만 남긴 valueJson을 조립한다")
    void updateSettingCandidateRebuildsEditedDynamicJsonWithNameOnly() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(
                work,
                "아리아",
                "skill.파이어볼",
                "Lv.3",
                SettingValueType.JSON,
                objectMapper.createObjectNode()
                        .put("name", "파이어볼")
                        .put("level", 3)
                        .put("effect", "화염 공격")
        );
        JsonNode originalEvidenceSpans = candidate.getEvidenceSpans();
        JsonNode originalRawAiResult = candidate.getRawAiResultJson();
        SettingCandidateResponse response = response(workId);
        SettingCandidateUpdateRequest request = new SettingCandidateUpdateRequest(
                " skill.화염 검술 ",
                " 주력기 "
        );
        CharacterSettingSchema schema =
                schema("skills.skill", "skill.*", CharacterFactType.SKILL, SettingValueType.JSON);
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(characterSettingSchemaRepository.findAllActiveForWork(workId)).thenReturn(List.of(schema));
        when(settingCandidateMapper.toResponse(
                any(SettingCandidate.class),
                anyBoolean(),
                nullable(String.class),
                any(SettingCandidateValueValidation.class)
        ))
                .thenReturn(response);

        service.updateSettingCandidate(memberId, workId, candidateId, request);

        assertThat(candidate.getAttributeName()).isEqualTo("skill.화염_검술");
        assertThat(candidate.getAttributeValue()).isEqualTo("주력기");
        assertThat(candidate.getValueType()).isEqualTo(SettingValueType.JSON);
        assertThat(candidate.getValueJson()).hasToString("{\"name\":\"화염 검술\"}");
        assertThat(candidate.getEvidenceSpans()).isSameAs(originalEvidenceSpans);
        assertThat(candidate.getRawAiResultJson()).isSameAs(originalRawAiResult);
    }

    @Test
    @DisplayName("동적 설정명의 suffix가 밑줄뿐이면 확정 불가능한 빈 name을 만들지 않고 거절한다")
    void updateSettingCandidateRejectsBlankDynamicDisplayName() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(
                work,
                "아리아",
                "skill.파이어볼",
                "Lv.3",
                SettingValueType.JSON,
                objectMapper.createObjectNode().put("name", "파이어볼").put("level", 3)
        );
        CharacterSettingSchema schema =
                schema("skills.skill", "skill.*", CharacterFactType.SKILL, SettingValueType.JSON);
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(characterSettingSchemaRepository.findAllActiveForWork(workId)).thenReturn(List.of(schema));

        assertThatThrownBy(() -> service.updateSettingCandidate(
                memberId,
                workId,
                candidateId,
                new SettingCandidateUpdateRequest("skill.___", "Lv.4")
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_ATTRIBUTE_NAME_INVALID));

        assertThat(candidate.getAttributeName()).isEqualTo("skill.파이어볼");
        assertThat(candidate.getValueJson()).hasToString("{\"name\":\"파이어볼\",\"level\":3}");
    }

    @Test
    @DisplayName("기존의 잘못된 동적 suffix는 유효한 설정명으로 교정할 수 있다")
    void updateSettingCandidateRepairsInvalidStoredDynamicSuffix() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(
                work,
                "아리아",
                "skill.___",
                "Lv.3",
                SettingValueType.JSON,
                objectMapper.createObjectNode().put("name", "").put("level", 3)
        );
        CharacterSettingSchema schema =
                schema("skills.skill", "skill.*", CharacterFactType.SKILL, SettingValueType.JSON);
        SettingCandidateResponse response = response(workId);
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(characterSettingSchemaRepository.findAllActiveForWork(workId)).thenReturn(List.of(schema));
        when(settingCandidateMapper.toResponse(
                candidate,
                true,
                "skill.",
                SettingCandidateValueValidation.valid()
        )).thenReturn(response);

        service.updateSettingCandidate(
                memberId,
                workId,
                candidateId,
                new SettingCandidateUpdateRequest("skill.화염 검술", "Lv.4")
        );

        assertThat(candidate.getAttributeName()).isEqualTo("skill.화염_검술");
        assertThat(candidate.getAttributeValue()).isEqualTo("Lv.4");
        assertThat(candidate.getValueJson()).hasToString("{\"name\":\"화염 검술\"}");
        verify(settingCandidateMapper).toResponse(
                candidate,
                true,
                "skill.",
                SettingCandidateValueValidation.valid()
        );
    }

    @Test
    @DisplayName("수정한 동적 설정명이 여러 schema pattern과 겹치면 모호성 충돌을 유지한다")
    void updateSettingCandidatePreservesRequestedSchemaAmbiguity() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(
                work,
                "아리아",
                "skill.파이어볼",
                "Lv.3",
                SettingValueType.JSON,
                objectMapper.createObjectNode().put("name", "파이어볼")
        );
        CharacterSettingSchema genericSchema =
                schema("skills.skill", "skill.*", CharacterFactType.SKILL, SettingValueType.JSON);
        CharacterSettingSchema nestedSchema =
                schema("skills.special", "skill.special.*", CharacterFactType.SKILL, SettingValueType.JSON);
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(characterSettingSchemaRepository.findAllActiveForWork(workId))
                .thenReturn(List.of(genericSchema, nestedSchema));

        assertThatThrownBy(() -> service.updateSettingCandidate(
                memberId,
                workId,
                candidateId,
                new SettingCandidateUpdateRequest("skill.special.파이어볼", "Lv.4")
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_SCHEMA_MATCH_AMBIGUOUS));

        assertThat(candidate.getAttributeName()).isEqualTo("skill.파이어볼");
        assertThat(candidate.getAttributeValue()).isEqualTo("Lv.3");
    }

    @Test
    @DisplayName("고정 schema 후보의 설정명 변경은 거절한다")
    void updateSettingCandidateRejectsFixedAttributeNameChange() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(work, "아리아", "age", "17");
        SettingCandidateUpdateRequest request = new SettingCandidateUpdateRequest("level", "17");
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(characterSettingSchemaRepository.findAllActiveForWork(workId))
                .thenReturn(List.of(schema("age", null, CharacterFactType.AGE, SettingValueType.NUMBER)));

        assertThatThrownBy(() -> service.updateSettingCandidate(memberId, workId, candidateId, request))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_ATTRIBUTE_NAME_NOT_EDITABLE));

        assertThat(candidate.getAttributeName()).isEqualTo("age");
        assertThat(candidate.getAttributeValue()).isEqualTo("17");
    }

    @Test
    @DisplayName("alias로 매칭된 고정 schema 후보도 설정명 변경을 거절한다")
    void updateSettingCandidateRejectsAliasAttributeNameChange() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(work, "아리아", "나이", "17");
        SettingCandidateUpdateRequest request = new SettingCandidateUpdateRequest("age", "17");
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(characterSettingSchemaRepository.findAllActiveForWork(workId))
                .thenReturn(List.of(schema(
                        "age",
                        null,
                        CharacterFactType.AGE,
                        SettingValueType.NUMBER,
                        "나이"
                )));

        assertThatThrownBy(() -> service.updateSettingCandidate(memberId, workId, candidateId, request))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_ATTRIBUTE_NAME_NOT_EDITABLE));

        assertThat(candidate.getAttributeName()).isEqualTo("나이");
        assertThat(candidate.getValueJson().get("value").asText()).isEqualTo("17");
    }

    @Test
    @DisplayName("숫자 후보에 lv.5 같은 문자열 값은 저장하지 않는다")
    void updateSettingCandidateRejectsNonNumericNumberValue() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(work, "아리아", "stats.strength", "10");
        SettingCandidateUpdateRequest request =
                new SettingCandidateUpdateRequest("stats.strength", "lv.5");
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(characterSettingSchemaRepository.findAllActiveForWork(workId))
                .thenReturn(List.of(schema(
                        "stats.strength",
                        null,
                        CharacterFactType.STAT,
                        SettingValueType.NUMBER
                )));

        assertThatThrownBy(() -> service.updateSettingCandidate(memberId, workId, candidateId, request))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_VALUE_FORMAT_INVALID));

        assertThat(candidate.getAttributeValue()).isEqualTo("10");
    }

    @Test
    @DisplayName("나이와 레벨 후보 수정은 0 이상의 int 정수만 허용한다")
    void updateSettingCandidateRejectsInvalidCoreNumberValue() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(work, "아리아", "age", "17");
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(characterSettingSchemaRepository.findAllActiveForWork(workId))
                .thenReturn(List.of(schema("age", null, CharacterFactType.AGE, SettingValueType.NUMBER)));

        for (String invalidValue : new String[]{"-1", "1.5", "2147483648", null}) {
            SettingCandidateUpdateRequest request =
                    new SettingCandidateUpdateRequest("age", invalidValue);

            assertThatThrownBy(() -> service.updateSettingCandidate(memberId, workId, candidateId, request))
                    .isInstanceOfSatisfying(AppException.class, exception ->
                            assertThat(exception.getResultCode())
                                    .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_VALUE_INVALID));
        }

        assertThat(candidate.getAttributeValue()).isEqualTo("17");
    }

    @Test
    @DisplayName("동적 scalar 후보 수정은 typed value와 사용자용 name을 함께 조립한다")
    void updateSettingCandidateRebuildsEditedDynamicScalarValue() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(
                work,
                "아리아",
                "profile.별명",
                "불꽃",
                SettingValueType.STRING,
                objectMapper.createObjectNode().put("value", "불꽃").put("name", "별명")
        );
        SettingCandidateResponse response = response(workId);
        SettingCandidateUpdateRequest request =
                new SettingCandidateUpdateRequest(" profile.대표 별명 ", " 홍염 ");
        CharacterSettingSchema schema =
                schema("profile.attribute", "profile.*", CharacterFactType.PROFILE, SettingValueType.STRING);
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(characterSettingSchemaRepository.findAllActiveForWork(workId)).thenReturn(List.of(schema));
        when(settingCandidateMapper.toResponse(
                any(SettingCandidate.class),
                anyBoolean(),
                nullable(String.class),
                any(SettingCandidateValueValidation.class)
        ))
                .thenReturn(response);

        service.updateSettingCandidate(memberId, workId, candidateId, request);

        assertThat(candidate.getAttributeName()).isEqualTo("profile.대표_별명");
        assertThat(candidate.getAttributeValue()).isEqualTo("홍염");
        assertThat(candidate.getValueJson())
                .hasToString("{\"value\":\"홍염\",\"name\":\"대표 별명\"}");
    }

    @Test
    @DisplayName("고정 JSON 후보 수정은 숨은 기존 name 대신 schema 표시명을 사용한다")
    void updateSettingCandidateUsesSchemaDisplayNameForEditedFixedJson() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(
                work,
                "아리아",
                "profile",
                "주인공",
                SettingValueType.JSON,
                objectMapper.createObjectNode().put("name", "숨은 이름").put("role", "주인공")
        );
        SettingCandidateResponse response = response(workId);
        SettingCandidateUpdateRequest request =
                new SettingCandidateUpdateRequest("profile", "라이벌");
        CharacterSettingSchema schema = CharacterSettingSchema.create(
                null,
                "profile",
                null,
                "프로필",
                CharacterFactType.PROFILE,
                SettingValueType.JSON,
                CharacterSettingValueSemantics.BASE_VALUE,
                CharacterSettingMergePolicy.REPLACE,
                objectMapper.createArrayNode(),
                CharacterSettingSchemaSource.SYSTEM_SEED,
                true
        );
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(characterSettingSchemaRepository.findAllActiveForWork(workId)).thenReturn(List.of(schema));
        when(settingCandidateMapper.toResponse(
                any(SettingCandidate.class),
                anyBoolean(),
                nullable(String.class),
                any(SettingCandidateValueValidation.class)
        ))
                .thenReturn(response);

        service.updateSettingCandidate(memberId, workId, candidateId, request);

        assertThat(candidate.getAttributeName()).isEqualTo("profile");
        assertThat(candidate.getAttributeValue()).isEqualTo("라이벌");
        assertThat(candidate.getValueJson()).hasToString("{\"name\":\"프로필\"}");
    }

    @Test
    @DisplayName("검토 완료 후보 수정은 거절한다")
    void updateSettingCandidateRejectsReviewedCandidate() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(work, "아리아", "age", "17");
        candidate.confirm();
        SettingCandidateUpdateRequest request = new SettingCandidateUpdateRequest(
                "level",
                "23"
        );
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));

        assertThatThrownBy(() -> service.updateSettingCandidate(memberId, workId, candidateId, request))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_NOT_EDITABLE));

        verify(settingCandidateMapper, never()).toResponse(
                any(SettingCandidate.class),
                anyBoolean(),
                nullable(String.class),
                any(SettingCandidateValueValidation.class)
        );
    }

    @Test
    @DisplayName("기존 캐릭터에 연결하면 후보 매칭 상태를 MATCHED로 갱신한다")
    void updateSettingCandidateCharacterMatchConnectsExistingCharacter() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID characterId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(work, "미상", "age", "17");
        WorkCharacter character = character(work, characterId, "아리아");
        SettingCandidateResponse response = response(workId);
        SettingCandidateCharacterMatchRequest request = new SettingCandidateCharacterMatchRequest(
                SettingCandidateCharacterMatchResolutionType.MATCH_EXISTING,
                characterId,
                null
        );
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(workCharacterRepository.findByIdAndWorkIdForUpdate(characterId, workId))
                .thenReturn(Optional.of(character));
        when(characterSettingSchemaRepository.findAllActiveForWork(workId))
                .thenReturn(List.of(schema("age", null, CharacterFactType.AGE, SettingValueType.NUMBER)));
        when(settingCandidateMapper.toResponse(
                any(SettingCandidate.class),
                anyBoolean(),
                nullable(String.class),
                any(SettingCandidateValueValidation.class)
        ))
                .thenReturn(response);

        SettingCandidateResponse result =
                service.updateSettingCandidateCharacterMatch(memberId, workId, candidateId, request);

        assertThat(result).isSameAs(response);
        assertThat(candidate.getEntityName()).isEqualTo("아리아");
        assertThat(candidate.getMatchedCharacterId()).isEqualTo(characterId);
        assertThat(candidate.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.MATCHED);
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("confirm 전 새 캐릭터 등록 예정으로 지정하면 후보 매칭 상태를 UNRESOLVED로 유지한다")
    void updateSettingCandidateCharacterMatchMarksAsNewCharacter() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(work, "미상", "age", "17");
        SettingCandidateResponse response = response(workId);
        SettingCandidateCharacterMatchRequest request = new SettingCandidateCharacterMatchRequest(
                SettingCandidateCharacterMatchResolutionType.CREATE_NEW,
                null,
                "  아리아  "
        );
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(workCharacterRepository.existsByWorkIdAndName(workId, "아리아"))
                .thenReturn(false);
        when(characterSettingSchemaRepository.findAllActiveForWork(workId))
                .thenReturn(List.of(schema("age", null, CharacterFactType.AGE, SettingValueType.NUMBER)));
        when(settingCandidateMapper.toResponse(
                any(SettingCandidate.class),
                anyBoolean(),
                nullable(String.class),
                any(SettingCandidateValueValidation.class)
        ))
                .thenReturn(response);

        SettingCandidateResponse result =
                service.updateSettingCandidateCharacterMatch(memberId, workId, candidateId, request);

        assertThat(result).isSameAs(response);
        assertThat(candidate.getEntityName()).isEqualTo("아리아");
        assertThat(candidate.getMatchedCharacterId()).isNull();
        assertThat(candidate.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.UNRESOLVED);
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("기존 캐릭터 연결 요청에 캐릭터 ID가 없으면 거절한다")
    void updateSettingCandidateCharacterMatchRejectsMissingMatchedCharacterId() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(work, "미상", "age", "17");
        SettingCandidateCharacterMatchRequest request = new SettingCandidateCharacterMatchRequest(
                SettingCandidateCharacterMatchResolutionType.MATCH_EXISTING,
                null,
                null
        );
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));

        assertThatThrownBy(() -> service.updateSettingCandidateCharacterMatch(memberId, workId, candidateId, request))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_REQUIRED));

        verify(workCharacterRepository, never()).findByIdAndWorkIdForUpdate(any(UUID.class), any(UUID.class));
        verify(settingCandidateMapper, never()).toResponse(
                any(SettingCandidate.class),
                anyBoolean(),
                nullable(String.class),
                any(SettingCandidateValueValidation.class)
        );
    }

    @Test
    @DisplayName("새 캐릭터 등록 예정 지정 요청에 이름이 없으면 거절한다")
    void updateSettingCandidateCharacterMatchRejectsMissingNewCharacterName() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(work, "미상", "age", "17");
        SettingCandidateCharacterMatchRequest request = new SettingCandidateCharacterMatchRequest(
                SettingCandidateCharacterMatchResolutionType.CREATE_NEW,
                null,
                "  "
        );
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));

        assertThatThrownBy(() -> service.updateSettingCandidateCharacterMatch(memberId, workId, candidateId, request))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_NEW_CHARACTER_NAME_REQUIRED));

        verify(workCharacterRepository, never()).findByWorkIdAndNameAndStatus(
                any(UUID.class),
                any(String.class),
                any(CharacterStatus.class)
        );
        verify(settingCandidateMapper, never()).toResponse(
                any(SettingCandidate.class),
                anyBoolean(),
                nullable(String.class),
                any(SettingCandidateValueValidation.class)
        );
    }

    @Test
    @DisplayName("새 캐릭터 이름이 기존 캐릭터와 같으면 거절한다")
    void updateSettingCandidateCharacterMatchRejectsDuplicateNewCharacterName() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID characterId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(work, "미상", "age", "17");
        WorkCharacter character = character(work, characterId, "아리아");
        SettingCandidateCharacterMatchRequest request = new SettingCandidateCharacterMatchRequest(
                SettingCandidateCharacterMatchResolutionType.CREATE_NEW,
                null,
                "아리아"
        );
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(workCharacterRepository.existsByWorkIdAndName(workId, "아리아"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.updateSettingCandidateCharacterMatch(memberId, workId, candidateId, request))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED));

        verify(settingCandidateMapper, never()).toResponse(
                any(SettingCandidate.class),
                anyBoolean(),
                nullable(String.class),
                any(SettingCandidateValueValidation.class)
        );
    }

    @Test
    @DisplayName("보관된 캐릭터 연결은 거절한다")
    void updateSettingCandidateCharacterMatchRejectsArchivedCharacter() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID characterId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(work, "미상", "age", "17");
        WorkCharacter character = character(work, characterId, "아리아");
        character.archive();
        SettingCandidateCharacterMatchRequest request = new SettingCandidateCharacterMatchRequest(
                SettingCandidateCharacterMatchResolutionType.MATCH_EXISTING,
                characterId,
                null
        );
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(workCharacterRepository.findByIdAndWorkIdForUpdate(characterId, workId))
                .thenReturn(Optional.of(character));

        assertThatThrownBy(() -> service.updateSettingCandidateCharacterMatch(memberId, workId, candidateId, request))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID));

        verify(settingCandidateMapper, never()).toResponse(
                any(SettingCandidate.class),
                anyBoolean(),
                nullable(String.class),
                any(SettingCandidateValueValidation.class)
        );
    }

    @Test
    @DisplayName("검토 완료 후보의 캐릭터 연결 해소는 캐릭터 조회 전에 거절한다")
    void updateSettingCandidateCharacterMatchRejectsReviewedCandidateBeforeCharacterLookup() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID characterId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(work, "미상", "age", "17");
        candidate.confirm();
        SettingCandidateCharacterMatchRequest request = new SettingCandidateCharacterMatchRequest(
                SettingCandidateCharacterMatchResolutionType.MATCH_EXISTING,
                characterId,
                null
        );
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));

        assertThatThrownBy(() -> service.updateSettingCandidateCharacterMatch(memberId, workId, candidateId, request))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_NOT_EDITABLE));

        verify(workCharacterRepository, never()).findByIdAndWorkIdForUpdate(any(UUID.class), any(UUID.class));
        verify(settingCandidateMapper, never()).toResponse(
                any(SettingCandidate.class),
                anyBoolean(),
                nullable(String.class),
                any(SettingCandidateValueValidation.class)
        );
    }

    @Test
    @DisplayName("검토 대기 후보를 확정 상태로 전환한다")
    void confirmSettingCandidateConfirmsPendingCandidate() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(work, "아리아", "age", "17");
        SettingCandidateReviewStatusResponse response = reviewStatusResponse(
                candidateId,
                SettingCandidateReviewStatus.CONFIRMED
        );
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(settingCandidateMapper.toReviewStatusResponse(candidate)).thenReturn(response);

        SettingCandidateConfirmResult result =
                service.confirmSettingCandidate(memberId, workId, candidateId, null);

        assertThat(result.response()).isSameAs(response);
        assertThat(result.recomparisonRequired()).isFalse();
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
        verify(settingCandidatePromotionService).promote(
                candidate,
                CharacterFactConfirmApplicationMode.APPLY_PROPOSAL
        );
    }

    @Test
    @DisplayName("대소문자와 연속 공백만 다른 기존 캐릭터는 신규 생성하지 않고 재비교한다")
    void confirmSettingCandidateMatchesExistingCharacterByNormalizedGroupName() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID characterId = UUID.randomUUID();
        Work work = work(workId);
        WorkCharacter character = character(work, characterId, "Alice Smith");
        SettingCandidate candidate = candidate(work, "alice  smith", "age", "17");
        ReflectionTestUtils.setField(candidate, "id", candidateId);
        SettingCandidateReviewStatusResponse response = reviewStatusResponse(
                candidateId,
                SettingCandidateReviewStatus.PENDING_REVIEW
        );
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId))
                .thenReturn(Optional.of(candidate));
        when(workCharacterRepository.findByWorkIdAndNameAndStatus(
                workId,
                "alice smith",
                CharacterStatus.ACTIVE
        )).thenReturn(Optional.empty());
        when(workCharacterRepository.findAllByWorkIdAndStatusOrderByCreatedAtDesc(
                workId,
                CharacterStatus.ACTIVE
        )).thenReturn(List.of(character));
        when(workCharacterRepository.findByIdAndWorkIdForUpdate(characterId, workId))
                .thenReturn(Optional.of(character));
        when(settingCandidateMapper.toReviewStatusResponse(candidate)).thenReturn(response);

        SettingCandidateConfirmResult result = service.confirmSettingCandidate(
                memberId,
                workId,
                candidateId,
                null
        );

        assertThat(result.recomparisonRequired()).isTrue();
        assertThat(candidate.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.MATCHED);
        assertThat(candidate.getMatchedCharacterId()).isEqualTo(characterId);
        verify(analysisJobRepository).save(any(AnalysisJob.class));
        verify(settingCandidatePromotionService, never()).promote(
                any(SettingCandidate.class),
                any(CharacterFactConfirmApplicationMode.class)
        );
    }

    @Test
    @DisplayName("정규화 이름이 같은 활성 캐릭터가 여러 명이면 임의로 연결하지 않는다")
    void confirmSettingCandidateRejectsAmbiguousNormalizedCharacterMatches() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        WorkCharacter first = character(work, UUID.randomUUID(), "Alice Smith");
        WorkCharacter second = character(work, UUID.randomUUID(), "ALICE  SMITH");
        SettingCandidate candidate = candidate(work, "alice smith", "age", "17");
        ReflectionTestUtils.setField(candidate, "id", candidateId);
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId))
                .thenReturn(Optional.of(candidate));
        when(workCharacterRepository.findByWorkIdAndNameAndStatus(
                workId,
                "alice smith",
                CharacterStatus.ACTIVE
        )).thenReturn(Optional.empty());
        when(workCharacterRepository.findAllByWorkIdAndStatusOrderByCreatedAtDesc(
                workId,
                CharacterStatus.ACTIVE
        )).thenReturn(List.of(first, second));

        assertThatThrownBy(() -> service.confirmSettingCandidate(memberId, workId, candidateId, null))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED));

        verify(settingCandidatePromotionService, never()).promote(
                any(SettingCandidate.class),
                any(CharacterFactConfirmApplicationMode.class)
        );
    }

    @Test
    @DisplayName("이력으로만 확정할 때는 현재 snapshot 문맥이 바뀌어도 재비교하지 않는다")
    void confirmHistoryOnlyDoesNotValidateCurrentSnapshotContext() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID characterId = UUID.randomUUID();
        Work work = work(workId);
        WorkCharacter character = character(work, characterId, "아리아");
        SettingCandidate candidate = candidate(work, "아리아", "age", "17");
        candidate.matchExistingCharacter(character);
        candidate.startComparison();
        candidate.recordComparisonContext(1L, "stale-context");
        candidate.completeComparison(
                CharacterFactOperation.HISTORY_ONLY,
                null,
                null,
                null,
                null,
                objectMapper.createArrayNode(),
                CharacterFactTemporalScope.PAST,
                "과거 회상",
                objectMapper.createObjectNode(),
                LocalDateTime.now()
        );
        SettingCandidateReviewStatusResponse response = reviewStatusResponse(
                candidateId,
                SettingCandidateReviewStatus.CONFIRMED
        );
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId))
                .thenReturn(Optional.of(candidate));
        when(settingCandidateMapper.toReviewStatusResponse(candidate)).thenReturn(response);

        SettingCandidateConfirmResult result = service.confirmSettingCandidate(
                memberId,
                workId,
                candidateId,
                new SettingCandidateConfirmRequest(
                        CharacterFactConfirmApplicationMode.HISTORY_ONLY,
                        999L
                )
        );

        assertThat(result.recomparisonRequired()).isFalse();
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
        verify(characterFactComparisonWorkerService, never()).hasCurrentContext(candidate);
        verify(settingCandidatePromotionService).promote(
                candidate,
                CharacterFactConfirmApplicationMode.HISTORY_ONLY
        );
    }

    @Test
    @DisplayName("순차 배포 중 남은 MATCHED NOT_REQUIRED 후보는 숨김 재비교 Job으로 복구한다")
    void confirmLegacyMatchedCandidateRequestsComparison() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID characterId = UUID.randomUUID();
        Work work = work(workId);
        WorkCharacter character = character(work, characterId, "아리아");
        SettingCandidate candidate = candidate(work, "아리아", "age", "17");
        candidate.matchExistingCharacter(character);
        ReflectionTestUtils.setField(
                candidate,
                "comparisonStatus",
                CharacterFactComparisonStatus.NOT_REQUIRED
        );
        SettingCandidateReviewStatusResponse response = reviewStatusResponse(
                candidateId,
                SettingCandidateReviewStatus.PENDING_REVIEW
        );
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId))
                .thenReturn(Optional.of(candidate));
        when(settingCandidateMapper.toReviewStatusResponse(candidate)).thenReturn(response);

        SettingCandidateConfirmResult result = service.confirmSettingCandidate(
                memberId,
                workId,
                candidateId,
                null
        );

        assertThat(result.recomparisonRequired()).isTrue();
        assertThat(result.response()).isSameAs(response);
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
        assertThat(candidate.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PENDING);
        verify(analysisJobRepository).save(any(org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.class));
        verify(settingCandidatePromotionService, never()).promote(
                any(SettingCandidate.class),
                any(CharacterFactConfirmApplicationMode.class)
        );
    }

    @Test
    @DisplayName("원 분석 Job이 실행 중이어도 사용자 재비교는 후보 전용 hidden Job에 위임한다")
    void retryComparisonCreatesHiddenJobWhileSourceJobIsRunning() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID characterId = UUID.randomUUID();
        Work work = work(workId);
        WorkCharacter character = character(work, characterId, "아리아");
        AnalysisJob sourceJob = AnalysisJob.create(
                work,
                null,
                null,
                AnalysisJobType.SETTING_EXTRACTION
        );
        sourceJob.claim("gpt-5.6-terra", "캐릭터 비교", LocalDateTime.now().plusMinutes(5));
        SettingCandidate candidate = candidate(work, "아리아", "age", "17");
        ReflectionTestUtils.setField(candidate, "id", candidateId);
        ReflectionTestUtils.setField(candidate, "analysisJob", sourceJob);
        candidate.matchExistingCharacter(character);
        SettingCandidateResponse response = response(workId);

        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId))
                .thenReturn(Optional.of(candidate));
        when(analysisJobRepository.existsBySettingCandidateIdAndStatusIn(
                candidateId,
                List.of(
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING,
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING
                )
        )).thenReturn(false);
        when(characterSettingSchemaRepository.findAllActiveForWork(workId))
                .thenReturn(List.of(schema("age", null, CharacterFactType.AGE, SettingValueType.NUMBER)));
        when(settingCandidateMapper.toResponse(
                candidate,
                false,
                null,
                SettingCandidateValueValidation.valid()
        )).thenReturn(response);

        SettingCandidateResponse result = service.retryComparison(memberId, workId, candidateId);

        assertThat(result).isSameAs(response);
        assertThat(candidate.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PENDING);
        verify(analysisJobRepository).save(any(AnalysisJob.class));
        verify(analysisJobRepository, never())
                .findFirstBySettingCandidateIdAndStatusInOrderByCreatedAtDesc(any(), any());
        verify(aiTokenService).ensureAnalysisCanStart(memberId);
    }

    @Test
    @DisplayName("유효하지 않은 후보는 재비교 Job을 만들지 않는다")
    void retryComparisonRejectsInvalidCandidateBeforeEnqueue() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        WorkCharacter character = character(work, UUID.randomUUID(), "아리아");
        SettingCandidate candidate = candidate(
                work,
                "아리아",
                "stats.mental",
                "정신: 37",
                SettingValueType.NUMBER,
                objectMapper.createObjectNode().put("value", 37)
        );
        ReflectionTestUtils.setField(candidate, "id", candidateId);
        candidate.matchExistingCharacter(character);
        ReflectionTestUtils.setField(
                candidate,
                "comparisonStatus",
                CharacterFactComparisonStatus.FAILED
        );

        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId))
                .thenReturn(Optional.of(candidate));
        when(characterSettingSchemaRepository.findAllActiveForWork(workId)).thenReturn(List.of(
                schema("stats.mental", null, CharacterFactType.STAT, SettingValueType.NUMBER)
        ));

        assertThatThrownBy(() -> service.retryComparison(memberId, workId, candidateId))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_VALUE_FORMAT_INVALID));

        assertThat(candidate.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.FAILED);
        verify(analysisJobRepository, never()).save(any(AnalysisJob.class));
        verifyNoInteractions(aiTokenService);
    }

    @Test
    @DisplayName("완료된 캐릭터 비교는 retry API로 다시 요청할 수 없다")
    void retryComparisonRejectsCompletedCandidate() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        WorkCharacter character = character(work, UUID.randomUUID(), "아리아");
        SettingCandidate candidate = candidate(work, "아리아", "age", "17");
        candidate.matchExistingCharacter(character);
        ReflectionTestUtils.setField(
                candidate,
                "comparisonStatus",
                CharacterFactComparisonStatus.COMPLETED
        );
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId))
                .thenReturn(Optional.of(candidate));

        assertThatThrownBy(() -> service.retryComparison(memberId, workId, candidateId))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(
                                CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT
                        ));

        verify(analysisJobRepository, never()).save(any(AnalysisJob.class));
        verify(aiTokenService, never()).ensureAnalysisCanStart(anyLong());
    }

    @Test
    @DisplayName("검토 대기 후보를 무시 상태로 전환한다")
    void dismissSettingCandidateDismissesPendingCandidate() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(work, "아리아", "age", "17");
        SettingCandidateReviewStatusResponse response = reviewStatusResponse(
                candidateId,
                SettingCandidateReviewStatus.DISMISSED
        );
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(settingCandidateMapper.toReviewStatusResponse(candidate)).thenReturn(response);

        SettingCandidateReviewStatusResponse result =
                service.dismissSettingCandidate(memberId, workId, candidateId);

        assertThat(result).isSameAs(response);
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.DISMISSED);
        verify(settingCandidatePromotionService, never()).promote(
                any(SettingCandidate.class),
                any(CharacterFactConfirmApplicationMode.class)
        );
    }

    @Test
    @DisplayName("이미 같은 검토 상태인 후보 전이는 성공으로 처리한다")
    void transitionReviewStatusAllowsSameStatus() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(work, "아리아", "age", "17");
        candidate.confirm();
        SettingCandidateReviewStatusResponse response = reviewStatusResponse(
                candidateId,
                SettingCandidateReviewStatus.CONFIRMED
        );
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(settingCandidateMapper.toReviewStatusResponse(candidate)).thenReturn(response);

        SettingCandidateConfirmResult result =
                service.confirmSettingCandidate(memberId, workId, candidateId, null);

        assertThat(result.response()).isSameAs(response);
        assertThat(result.recomparisonRequired()).isFalse();
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
        verify(settingCandidatePromotionService, never()).promote(
                any(SettingCandidate.class),
                any(CharacterFactConfirmApplicationMode.class)
        );
    }

    @Test
    @DisplayName("확정 또는 무시된 후보의 반대 검토 상태 전이는 거절한다")
    void transitionReviewStatusRejectsOppositeReviewedStatus() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID confirmedId = UUID.randomUUID();
        UUID dismissedId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate confirmed = candidate(work, "아리아", "age", "17");
        confirmed.confirm();
        SettingCandidate dismissed = candidate(work, "아리아", "level", "23");
        dismissed.dismiss();
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(confirmedId, workId)).thenReturn(Optional.of(confirmed));
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(dismissedId, workId)).thenReturn(Optional.of(dismissed));

        assertThatThrownBy(() -> service.dismissSettingCandidate(memberId, workId, confirmedId))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT));
        assertThatThrownBy(() -> service.confirmSettingCandidate(memberId, workId, dismissedId, null))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT));

        verify(settingCandidateMapper, never()).toReviewStatusResponse(any(SettingCandidate.class));
        verify(settingCandidatePromotionService, never()).promote(
                any(SettingCandidate.class),
                any(CharacterFactConfirmApplicationMode.class)
        );
    }

    @Test
    @DisplayName("작품 안에서 확정할 후보를 찾지 못하면 예외를 던진다")
    void confirmSettingCandidateRejectsMissingCandidateInWork() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmSettingCandidate(memberId, workId, candidateId, null))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_NOT_FOUND));
    }

    @Test
    @DisplayName("앞선 동일 slot 제안을 이력으로만 저장하면 그 값에 의존한 뒤 제안 적용을 거절한다")
    void confirmSettingCandidateGroupRejectsSuppressedPriorProposalDependency() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        Work work = work(workId);
        WorkCharacter character = character(work, UUID.randomUUID(), "아리아");
        SettingCandidate first = completedCandidate(
                work,
                character,
                "stats.strength",
                "10",
                CharacterFactOperation.ADD
        );
        SettingCandidate second = completedCandidate(
                work,
                character,
                "stats.strength",
                "12",
                CharacterFactOperation.ADD
        );
        ReflectionTestUtils.setField(
                first,
                "evidenceSpans",
                objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("startOffset", 1))
        );
        ReflectionTestUtils.setField(
                second,
                "evidenceSpans",
                objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("startOffset", 2))
        );
        List<SettingCandidate> candidates = List.of(first, second);
        when(workRepository.getOwnedWorkForUpdate(workId, memberId)).thenReturn(work);
        when(uploadBatchRepository.findByIdAndWorkId(batchId, workId))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(UploadBatch.class)));
        when(settingCandidateRepository.findAllByIdsAndBatchForUpdate(
                eq(workId),
                eq(batchId),
                eq(Set.of(first.getId(), second.getId()))
        )).thenReturn(candidates);
        when(settingCandidateRepository.findReviewCandidates(
                workId,
                batchId,
                SettingCandidateReviewStatus.PENDING_REVIEW,
                java.util.EnumSet.allOf(SettingCandidateMatchStatus.class)
        )).thenReturn(candidates);
        when(characterFactComparisonWorkerService.hasCurrentContext(any(SettingCandidate.class)))
                .thenReturn(true);
        when(characterSettingSchemaRepository.findAllActiveForWork(workId)).thenReturn(List.of(
                schema("stats.strength", null, CharacterFactType.STAT, SettingValueType.NUMBER)
        ));
        SettingCandidateGroupConfirmRequest request = new SettingCandidateGroupConfirmRequest(
                batchId,
                List.of(
                        new SettingCandidateGroupConfirmDecision(
                                first.getId(),
                                CharacterFactConfirmApplicationMode.HISTORY_ONLY,
                                0L
                        ),
                        new SettingCandidateGroupConfirmDecision(
                                second.getId(),
                                CharacterFactConfirmApplicationMode.APPLY_PROPOSAL,
                                0L
                        )
                )
        );

        assertThatThrownBy(() -> service.confirmSettingCandidateGroup(memberId, workId, request))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(
                                CharacterErrorCode.SETTING_CANDIDATE_GROUP_DECISION_DEPENDENCY_CONFLICT
                        ));
        verify(settingCandidatePromotionService, never()).promote(
                any(SettingCandidate.class),
                any(CharacterFactConfirmApplicationMode.class)
        );
    }

    private SettingCandidate candidate(
            Work work,
            String entityName,
            String attributeName,
            String attributeValue
    ) {
        return candidate(
                work,
                entityName,
                attributeName,
                attributeValue,
                SettingValueType.NUMBER,
                objectMapper.createObjectNode().put("value", new BigDecimal(attributeValue))
        );
    }

    private SettingCandidate completedCandidate(
            Work work,
            WorkCharacter character,
            String attributeName,
            String attributeValue,
            CharacterFactOperation operation
    ) {
        SettingCandidate candidate = candidate(work, character.getName(), attributeName, attributeValue);
        ReflectionTestUtils.setField(candidate, "id", UUID.randomUUID());
        candidate.matchExistingCharacter(character);
        candidate.startComparison();
        candidate.recordComparisonContext(0L, "context-hash-" + candidate.getId());
        candidate.completeComparison(
                operation,
                null,
                null,
                attributeValue,
                objectMapper.createObjectNode().put("value", Integer.parseInt(attributeValue)),
                objectMapper.createArrayNode(),
                CharacterFactTemporalScope.PRESENT,
                "현재 설정 제안",
                objectMapper.createObjectNode().put("operation", operation.name()),
                LocalDateTime.of(2026, 8, 13, 12, 0)
        );
        return candidate;
    }

    private SettingCandidate candidate(
            Work work,
            String entityName,
            String attributeName,
            String attributeValue,
            SettingValueType valueType,
            JsonNode valueJson
    ) {
        return SettingCandidate.create(
                work,
                null,
                UUID.randomUUID(),
                null,
                SettingEntityType.CHARACTER,
                entityName,
                attributeName,
                attributeValue,
                valueType,
                valueJson,
                objectMapper.createArrayNode(),
                new BigDecimal("0.8000"),
                objectMapper.createObjectNode().put("raw_value", attributeValue)
        );
    }

    private CharacterSettingSchema schema(
            String schemaKey,
            String attributePattern,
            CharacterFactType factType,
            SettingValueType valueType,
            String... aliases
    ) {
        var aliasesJson = objectMapper.createArrayNode();
        for (String alias : aliases) {
            aliasesJson.add(alias);
        }
        return CharacterSettingSchema.create(
                null,
                schemaKey,
                attributePattern,
                schemaKey,
                factType,
                valueType,
                CharacterSettingValueSemantics.BASE_VALUE,
                CharacterSettingMergePolicy.REPLACE,
                aliasesJson,
                CharacterSettingSchemaSource.SYSTEM_SEED,
                true
        );
    }

    private SettingCandidateResponse response(UUID workId) {
        return new SettingCandidateResponse(
                UUID.randomUUID(),
                workId,
                null,
                null,
                null,
                null,
                SettingCandidateKind.SETTING,
                SettingEntityType.CHARACTER,
                "아리아",
                null,
                null,
                SettingCandidateMatchStatus.UNRESOLVED,
                "age",
                false,
                null,
                "17",
                SettingValueType.NUMBER,
                objectMapper.createObjectNode().put("value", 17),
                new SettingCandidateValueValidationResponse(
                        SettingCandidateValueValidationStatus.VALID,
                        null,
                        null,
                        false
                ),
                List.of(),
                new BigDecimal("0.8000"),
                SettingCandidateReviewStatus.PENDING_REVIEW,
                Map.of("raw_value", "17"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null
        );
    }

    private WorkCharacter character(Work work, UUID id, String name) {
        WorkCharacter character = WorkCharacter.create(
                work,
                name,
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
        ReflectionTestUtils.setField(character, "id", id);
        return character;
    }

    private SettingCandidateReviewStatusResponse reviewStatusResponse(
            UUID candidateId,
            SettingCandidateReviewStatus reviewStatus
    ) {
        return new SettingCandidateReviewStatusResponse(candidateId, reviewStatus);
    }

    private Work work(UUID id) {
        Member member = Member.register("writer@example.com", "encoded-password", "01012345678", "작가");
        Work work = Work.create(member, "내 작품", WorkGenre.FANTASY, "내 설명");
        ReflectionTestUtils.setField(work, "id", id);
        return work;
    }
}
