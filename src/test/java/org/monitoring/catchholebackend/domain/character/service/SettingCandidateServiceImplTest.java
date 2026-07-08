package org.monitoring.catchholebackend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateCharacterMatchRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateReviewStatusResponse;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.mapper.SettingCandidateMapper;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateCharacterMatchResolutionType;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("설정 후보 Service 단위 테스트")
class SettingCandidateServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private WorkRepository workRepository;

    @Mock
    private SettingCandidateRepository settingCandidateRepository;

    @Mock
    private WorkCharacterRepository workCharacterRepository;

    @Mock
    private SettingCandidateMapper settingCandidateMapper;

    @Mock
    private SettingCandidatePromotionService settingCandidatePromotionService;

    private SettingCandidateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SettingCandidateServiceImpl(
                workRepository,
                settingCandidateRepository,
                workCharacterRepository,
                settingCandidateMapper,
                settingCandidatePromotionService
        );
    }

    @Test
    @DisplayName("검토 상태와 대상 이름이 있으면 조합 조건으로 후보 목록을 조회한다")
    void getSettingCandidatesUsesEntityNameAndReviewStatusFilter() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(work, "아리아", "age", "17");
        List<SettingCandidate> candidates = List.of(candidate);
        List<SettingCandidateResponse> responses = List.of(response(workId));
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findAllByWorkIdAndEntityNameAndReviewStatusOrderByCreatedAtDesc(
                workId,
                "아리아",
                SettingCandidateReviewStatus.PENDING_REVIEW
        )).thenReturn(candidates);
        when(settingCandidateMapper.toResponseList(candidates)).thenReturn(responses);

        List<SettingCandidateResponse> result = service.getSettingCandidates(
                memberId,
                workId,
                SettingCandidateReviewStatus.PENDING_REVIEW,
                "  아리아  "
        );

        assertThat(result).isSameAs(responses);
        verify(settingCandidateRepository).findAllByWorkIdAndEntityNameAndReviewStatusOrderByCreatedAtDesc(
                workId,
                "아리아",
                SettingCandidateReviewStatus.PENDING_REVIEW
        );
    }

    @Test
    @DisplayName("대상 이름만 있으면 대상 이름 조건으로 후보 목록을 조회한다")
    void getSettingCandidatesUsesEntityNameFilter() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        Work work = work(workId);
        List<SettingCandidate> candidates = List.of();
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findAllByWorkIdAndEntityNameOrderByCreatedAtDesc(workId, "아리아"))
                .thenReturn(candidates);
        when(settingCandidateMapper.toResponseList(candidates)).thenReturn(List.of());

        service.getSettingCandidates(memberId, workId, null, "아리아");

        verify(settingCandidateRepository).findAllByWorkIdAndEntityNameOrderByCreatedAtDesc(workId, "아리아");
    }

    @Test
    @DisplayName("검토 상태만 있으면 검토 상태 조건으로 후보 목록을 조회한다")
    void getSettingCandidatesUsesReviewStatusFilter() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        Work work = work(workId);
        List<SettingCandidate> candidates = List.of();
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findAllByWorkIdAndReviewStatusOrderByCreatedAtDesc(
                workId,
                SettingCandidateReviewStatus.PENDING_REVIEW
        )).thenReturn(candidates);
        when(settingCandidateMapper.toResponseList(candidates)).thenReturn(List.of());

        service.getSettingCandidates(memberId, workId, SettingCandidateReviewStatus.PENDING_REVIEW, null);

        verify(settingCandidateRepository).findAllByWorkIdAndReviewStatusOrderByCreatedAtDesc(
                workId,
                SettingCandidateReviewStatus.PENDING_REVIEW
        );
    }

    @Test
    @DisplayName("필터가 없으면 작품 전체 후보 목록을 조회한다")
    void getSettingCandidatesUsesWorkFilterOnly() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        Work work = work(workId);
        List<SettingCandidate> candidates = List.of();
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findAllByWorkIdOrderByCreatedAtDesc(workId)).thenReturn(candidates);
        when(settingCandidateMapper.toResponseList(candidates)).thenReturn(List.of());

        service.getSettingCandidates(memberId, workId, null, null);

        verify(settingCandidateRepository).findAllByWorkIdOrderByCreatedAtDesc(workId);
    }

    @Test
    @DisplayName("작품 안에서 후보를 찾지 못하면 예외를 던진다")
    void getSettingCandidateRejectsMissingCandidateInWork() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkId(candidateId, workId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSettingCandidate(memberId, workId, candidateId))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_NOT_FOUND));
    }

    @Test
    @DisplayName("검토 대기 후보의 보정 가능 필드를 수정한다")
    void updateSettingCandidateUpdatesPendingCandidate() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        SettingCandidate candidate = candidate(work, "아리아", "age", "17");
        SettingCandidateResponse response = response(workId);
        SettingCandidateUpdateRequest request = new SettingCandidateUpdateRequest(
                "  level  ",
                "  23  ",
                SettingValueType.NUMBER,
                Map.of("value", 23, "source", "user_review"),
                List.of(Map.of("paragraph_index", 2, "quote", "아리아는 스물셋의 경지에 올랐다."))
        );
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkId(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(settingCandidateMapper.toResponse(candidate)).thenReturn(response);

        SettingCandidateResponse result = service.updateSettingCandidate(memberId, workId, candidateId, request);

        assertThat(result).isSameAs(response);
        assertThat(candidate.getEntityName()).isEqualTo("아리아");
        assertThat(candidate.getMatchedCharacterId()).isNull();
        assertThat(candidate.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.UNRESOLVED);
        assertThat(candidate.getAttributeName()).isEqualTo("level");
        assertThat(candidate.getAttributeValue()).isEqualTo("23");
        assertThat(candidate.getValueJson().get("value").asInt()).isEqualTo(23);
        assertThat(candidate.getValueJson().get("source").asText()).isEqualTo("user_review");
        assertThat(candidate.getEvidenceSpans().get(0).get("paragraph_index").asInt()).isEqualTo(2);
        assertThat(candidate.getRawAiResultJson().get("raw_value").asText()).isEqualTo("17");
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
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
                "23",
                SettingValueType.NUMBER,
                Map.of("value", 23),
                List.of()
        );
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkId(candidateId, workId)).thenReturn(Optional.of(candidate));

        assertThatThrownBy(() -> service.updateSettingCandidate(memberId, workId, candidateId, request))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_NOT_EDITABLE));

        verify(settingCandidateMapper, never()).toResponse(any(SettingCandidate.class));
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
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkId(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(workCharacterRepository.findByIdAndWorkId(characterId, workId)).thenReturn(Optional.of(character));
        when(settingCandidateMapper.toResponse(candidate)).thenReturn(response);

        SettingCandidateResponse result =
                service.updateSettingCandidateCharacterMatch(memberId, workId, candidateId, request);

        assertThat(result).isSameAs(response);
        assertThat(candidate.getEntityName()).isEqualTo("아리아");
        assertThat(candidate.getMatchedCharacterId()).isEqualTo(characterId);
        assertThat(candidate.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.MATCHED);
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("새 캐릭터로 확정하면 후보 매칭 상태를 UNRESOLVED로 갱신한다")
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
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkId(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(workCharacterRepository.findByWorkIdAndName(workId, "아리아")).thenReturn(Optional.empty());
        when(settingCandidateMapper.toResponse(candidate)).thenReturn(response);

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
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkId(candidateId, workId)).thenReturn(Optional.of(candidate));

        assertThatThrownBy(() -> service.updateSettingCandidateCharacterMatch(memberId, workId, candidateId, request))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_REQUIRED));

        verify(workCharacterRepository, never()).findByIdAndWorkId(any(UUID.class), any(UUID.class));
        verify(settingCandidateMapper, never()).toResponse(any(SettingCandidate.class));
    }

    @Test
    @DisplayName("새 캐릭터 확정 요청에 이름이 없으면 거절한다")
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
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkId(candidateId, workId)).thenReturn(Optional.of(candidate));

        assertThatThrownBy(() -> service.updateSettingCandidateCharacterMatch(memberId, workId, candidateId, request))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_NEW_CHARACTER_NAME_REQUIRED));

        verify(workCharacterRepository, never()).findByWorkIdAndName(any(UUID.class), any(String.class));
        verify(settingCandidateMapper, never()).toResponse(any(SettingCandidate.class));
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
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkId(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(workCharacterRepository.findByWorkIdAndName(workId, "아리아")).thenReturn(Optional.of(character));

        assertThatThrownBy(() -> service.updateSettingCandidateCharacterMatch(memberId, workId, candidateId, request))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED));

        verify(settingCandidateMapper, never()).toResponse(any(SettingCandidate.class));
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
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkId(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(workCharacterRepository.findByIdAndWorkId(characterId, workId)).thenReturn(Optional.of(character));

        assertThatThrownBy(() -> service.updateSettingCandidateCharacterMatch(memberId, workId, candidateId, request))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID));

        verify(settingCandidateMapper, never()).toResponse(any(SettingCandidate.class));
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
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkId(candidateId, workId)).thenReturn(Optional.of(candidate));

        assertThatThrownBy(() -> service.updateSettingCandidateCharacterMatch(memberId, workId, candidateId, request))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_NOT_EDITABLE));

        verify(workCharacterRepository, never()).findByIdAndWorkId(any(UUID.class), any(UUID.class));
        verify(settingCandidateMapper, never()).toResponse(any(SettingCandidate.class));
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
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkId(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(settingCandidateMapper.toReviewStatusResponse(candidate)).thenReturn(response);

        SettingCandidateReviewStatusResponse result =
                service.confirmSettingCandidate(memberId, workId, candidateId);

        assertThat(result).isSameAs(response);
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
        verify(settingCandidatePromotionService).promote(candidate);
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
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkId(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(settingCandidateMapper.toReviewStatusResponse(candidate)).thenReturn(response);

        SettingCandidateReviewStatusResponse result =
                service.dismissSettingCandidate(memberId, workId, candidateId);

        assertThat(result).isSameAs(response);
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.DISMISSED);
        verify(settingCandidatePromotionService, never()).promote(any(SettingCandidate.class));
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
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkId(candidateId, workId)).thenReturn(Optional.of(candidate));
        when(settingCandidateMapper.toReviewStatusResponse(candidate)).thenReturn(response);

        SettingCandidateReviewStatusResponse result =
                service.confirmSettingCandidate(memberId, workId, candidateId);

        assertThat(result).isSameAs(response);
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
        verify(settingCandidatePromotionService, never()).promote(any(SettingCandidate.class));
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
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkId(confirmedId, workId)).thenReturn(Optional.of(confirmed));
        when(settingCandidateRepository.findByIdAndWorkId(dismissedId, workId)).thenReturn(Optional.of(dismissed));

        assertThatThrownBy(() -> service.dismissSettingCandidate(memberId, workId, confirmedId))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT));
        assertThatThrownBy(() -> service.confirmSettingCandidate(memberId, workId, dismissedId))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT));

        verify(settingCandidateMapper, never()).toReviewStatusResponse(any(SettingCandidate.class));
        verify(settingCandidatePromotionService, never()).promote(any(SettingCandidate.class));
    }

    @Test
    @DisplayName("작품 안에서 확정할 후보를 찾지 못하면 예외를 던진다")
    void confirmSettingCandidateRejectsMissingCandidateInWork() {
        Long memberId = 1L;
        UUID workId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Work work = work(workId);
        when(workRepository.getOwnedWork(workId, memberId)).thenReturn(work);
        when(settingCandidateRepository.findByIdAndWorkId(candidateId, workId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmSettingCandidate(memberId, workId, candidateId))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_NOT_FOUND));
    }

    private SettingCandidate candidate(
            Work work,
            String entityName,
            String attributeName,
            String attributeValue
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
                SettingValueType.NUMBER,
                objectMapper.createObjectNode().put("value", attributeValue),
                objectMapper.createArrayNode(),
                new BigDecimal("0.8000"),
                objectMapper.createObjectNode().put("raw_value", attributeValue)
        );
    }

    private SettingCandidateResponse response(UUID workId) {
        return new SettingCandidateResponse(
                UUID.randomUUID(),
                workId,
                null,
                null,
                null,
                SettingEntityType.CHARACTER,
                "아리아",
                null,
                null,
                SettingCandidateMatchStatus.UNRESOLVED,
                "age",
                "17",
                SettingValueType.NUMBER,
                Map.of("value", 17),
                List.of(),
                new BigDecimal("0.8000"),
                SettingCandidateReviewStatus.PENDING_REVIEW,
                Map.of("raw_value", "17"),
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
        Work work = Work.create(member, "내 작품", "판타지", "내 설명");
        ReflectionTestUtils.setField(work, "id", id);
        return work;
    }
}
