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
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateResponse;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.mapper.SettingCandidateMapper;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
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
    private SettingCandidateMapper settingCandidateMapper;

    private SettingCandidateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SettingCandidateServiceImpl(workRepository, settingCandidateRepository, settingCandidateMapper);
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
                "아리아",
                "level",
                "23",
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
                "아리아",
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

    private Work work(UUID id) {
        Member member = Member.register("writer@example.com", "encoded-password", "01012345678", "작가");
        Work work = Work.create(member, "내 작품", "판타지", "내 설명");
        ReflectionTestUtils.setField(work, "id", id);
        return work;
    }
}
