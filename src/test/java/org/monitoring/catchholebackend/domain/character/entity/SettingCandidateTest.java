package org.monitoring.catchholebackend.domain.character.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateKind;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("설정 후보 Entity 단위 테스트")
class SettingCandidateTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("설정 후보 생성 시 검토 대기 상태가 된다")
    void createInitializesPendingReviewStatus() {
        SettingCandidate candidate = candidate("age", "17");

        assertThat(candidate.getCandidateKind()).isEqualTo(SettingCandidateKind.SETTING);
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
        assertThat(candidate.isPendingReview()).isTrue();
    }

    @Test
    @DisplayName("캐릭터 발견 후보는 설정 값 없이 이름과 근거만 보관한다")
    void createCharacterDiscoveryKeepsOnlyCharacterEvidence() {
        SettingCandidate candidate = characterDiscovery(work(), "세룸");

        assertThat(candidate.getCandidateKind()).isEqualTo(SettingCandidateKind.CHARACTER_DISCOVERY);
        assertThat(candidate.isCharacterDiscovery()).isTrue();
        assertThat(candidate.getEntityType()).isEqualTo(SettingEntityType.CHARACTER);
        assertThat(candidate.getEntityName()).isEqualTo("세룸");
        assertThat(candidate.getRawEntityMention()).isEqualTo("케닉의 넷째 아들 세룸");
        assertThat(candidate.getAttributeName()).isNull();
        assertThat(candidate.getAttributeValue()).isNull();
        assertThat(candidate.getValueType()).isNull();
        assertThat(candidate.getValueJson()).isNull();
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("캐릭터 발견 후보는 설정 내용 수정 대상이 아니다")
    void updateReviewContentRejectsCharacterDiscovery() {
        SettingCandidate candidate = characterDiscovery(work(), "세룸");

        assertThatThrownBy(() -> candidate.updateReviewContent(
                "profile.family_relation",
                "케닉의 넷째 아들",
                objectMapper.createObjectNode().put("value", "케닉의 넷째 아들")
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_CONTENT_NOT_EDITABLE));
    }

    @Test
    @DisplayName("검토용 설정 후보 내용만 수정하고 캐릭터 연결 정보는 유지한다")
    void updateReviewContentChangesEditableFields() {
        Work work = work();
        SettingCandidate candidate = candidate(work, "age", "17");
        WorkCharacter character = character(work, "이안");
        candidate.matchExistingCharacter(character);
        JsonNode originalEvidenceSpans = candidate.getEvidenceSpans();
        JsonNode valueJson = objectMapper.createObjectNode()
                .put("value", 23)
                .put("source", "user_review");

        candidate.updateReviewContent(
                "level",
                "23",
                valueJson
        );

        assertThat(candidate.getEntityName()).isEqualTo("이안");
        assertThat(candidate.getMatchedCharacterId()).isEqualTo(character.getId());
        assertThat(candidate.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.MATCHED);
        assertThat(candidate.getAttributeName()).isEqualTo("level");
        assertThat(candidate.getAttributeValue()).isEqualTo("23");
        assertThat(candidate.getValueType()).isEqualTo(SettingValueType.NUMBER);
        assertThat(candidate.getValueJson()).isEqualTo(valueJson);
        assertThat(candidate.getEvidenceSpans()).isSameAs(originalEvidenceSpans);
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("기존 캐릭터에 연결하면 대상 이름과 매칭 상태를 갱신한다")
    void matchExistingCharacterChangesCharacterMatchState() {
        Work work = work();
        SettingCandidate candidate = candidate(work, "age", "17");
        WorkCharacter character = character(work, "이안");

        candidate.matchExistingCharacter(character);

        assertThat(candidate.getEntityName()).isEqualTo("이안");
        assertThat(candidate.getMatchedCharacterId()).isEqualTo(character.getId());
        assertThat(candidate.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.MATCHED);
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("같은 캐릭터를 다시 선택하면 완료된 비교 결과를 유지한다")
    void matchingSameCharacterKeepsCompletedComparison() {
        Work work = work();
        SettingCandidate candidate = candidate(work, "age", "17");
        WorkCharacter character = character(work, "이안");
        candidate.autoMatchSameNameCharacter(character);
        ReflectionTestUtils.setField(
                candidate,
                "comparisonStatus",
                CharacterFactComparisonStatus.COMPLETED
        );

        candidate.matchExistingCharacter(character);

        assertThat(candidate.getMatchedCharacterId()).isEqualTo(character.getId());
        assertThat(candidate.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.MATCHED);
        assertThat(candidate.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.COMPLETED);
    }

    @Test
    @DisplayName("신규 캐릭터 자동 연결은 별도 상태로 남고 사용자가 기존 캐릭터를 선택하면 일반 연결로 바뀐다")
    void autoMatchSameNameCharacterTracksAutomaticMatchUntilUserChangesTarget() {
        Work work = work();
        SettingCandidate candidate = candidate(work, "age", "17");
        WorkCharacter automaticallyMatched = character(work, "이안");
        WorkCharacter manuallyMatched = character(work, "아리아");

        candidate.autoMatchSameNameCharacter(automaticallyMatched);

        assertThat(candidate.getEntityName()).isEqualTo("이안");
        assertThat(candidate.getMatchedCharacterId()).isEqualTo(automaticallyMatched.getId());
        assertThat(candidate.getMatchStatus())
                .isEqualTo(SettingCandidateMatchStatus.AUTO_MATCHED_BY_NAME);

        candidate.matchExistingCharacter(manuallyMatched);

        assertThat(candidate.getEntityName()).isEqualTo("아리아");
        assertThat(candidate.getMatchedCharacterId()).isEqualTo(manuallyMatched.getId());
        assertThat(candidate.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.MATCHED);
    }

    @Test
    @DisplayName("확정 반영이 새로 생성한 캐릭터는 확정 후보에도 신규 연결 상태로 기록한다")
    void matchPromotedNewCharacterConnectsConfirmedCandidate() {
        Work work = work();
        SettingCandidate candidate = candidate(work, "age", "17");
        WorkCharacter character = character(work, "아리아");
        candidate.confirm();

        candidate.matchPromotedNewCharacter(character);

        assertThat(candidate.getEntityName()).isEqualTo("아리아");
        assertThat(candidate.getMatchedCharacterId()).isEqualTo(character.getId());
        assertThat(candidate.getMatchStatus())
                .isEqualTo(SettingCandidateMatchStatus.AUTO_MATCHED_BY_NAME);
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
        assertThat(candidate.isPendingReview()).isFalse();
    }

    @Test
    @DisplayName("확정 반영이 기존 캐릭터를 재사용하면 확정 후보에 기존 연결 상태로 기록한다")
    void matchPromotedExistingCharacterConnectsConfirmedCandidate() {
        Work work = work();
        SettingCandidate candidate = candidate(work, "age", "17");
        WorkCharacter character = character(work, "아리아");
        candidate.confirm();

        candidate.matchPromotedExistingCharacter(character);

        assertThat(candidate.getMatchedCharacterId()).isEqualTo(character.getId());
        assertThat(candidate.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.MATCHED);
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
    }

    @Test
    @DisplayName("confirm 전 새 캐릭터 등록 예정으로 지정하면 기존 연결을 제거하고 미해소 상태로 둔다")
    void markAsNewCharacterClearsMatchedCharacter() {
        Work work = work();
        SettingCandidate candidate = candidate(work, "age", "17");
        WorkCharacter character = character(work, "이안");
        candidate.matchExistingCharacter(character);

        candidate.markAsNewCharacter("아리아");

        assertThat(candidate.getEntityName()).isEqualTo("아리아");
        assertThat(candidate.getMatchedCharacterId()).isNull();
        assertThat(candidate.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.UNRESOLVED);
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("확정 또는 무시된 후보는 검토 대기 상태가 아니다")
    void reviewedCandidateIsNotPendingReview() {
        SettingCandidate confirmed = candidate("age", "17");
        SettingCandidate dismissed = candidate("level", "23");

        boolean newlyConfirmed = confirmed.confirm();
        boolean newlyDismissed = dismissed.dismiss();

        assertThat(newlyConfirmed).isTrue();
        assertThat(newlyDismissed).isTrue();
        assertThat(confirmed.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
        assertThat(confirmed.isPendingReview()).isFalse();
        assertThat(dismissed.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.DISMISSED);
        assertThat(dismissed.isPendingReview()).isFalse();
    }

    @Test
    @DisplayName("이미 같은 검토 상태로 전이하면 상태를 그대로 유지한다")
    void sameReviewStatusTransitionKeepsCurrentStatus() {
        SettingCandidate confirmed = candidate("age", "17");
        SettingCandidate dismissed = candidate("level", "23");

        boolean firstConfirm = confirmed.confirm();
        boolean secondConfirm = confirmed.confirm();
        boolean firstDismiss = dismissed.dismiss();
        boolean secondDismiss = dismissed.dismiss();

        assertThat(firstConfirm).isTrue();
        assertThat(secondConfirm).isFalse();
        assertThat(firstDismiss).isTrue();
        assertThat(secondDismiss).isFalse();
        assertThat(confirmed.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
        assertThat(dismissed.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.DISMISSED);
    }

    @Test
    @DisplayName("확정 또는 무시된 후보의 반대 검토 상태 전이는 거절한다")
    void oppositeReviewedStatusTransitionIsRejected() {
        SettingCandidate confirmed = candidate("age", "17");
        SettingCandidate dismissed = candidate("level", "23");
        confirmed.confirm();
        dismissed.dismiss();

        assertThatThrownBy(confirmed::dismiss)
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT))
                .hasMessageContaining("현재 검토 상태가 CONFIRMED(확정됨)인 설정 후보는 DISMISSED(무시됨)로 전환할 수 없습니다.");
        assertThatThrownBy(dismissed::confirm)
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT))
                .hasMessageContaining("현재 검토 상태가 DISMISSED(무시됨)인 설정 후보는 CONFIRMED(확정됨)로 전환할 수 없습니다.");
    }

    @Test
    @DisplayName("확정 또는 무시된 후보의 검토용 내용 수정은 거절한다")
    void reviewedCandidateUpdateReviewContentIsRejected() {
        SettingCandidate candidate = candidate("age", "17");
        candidate.confirm();

        assertThatThrownBy(() -> candidate.updateReviewContent(
                "level",
                "23",
                objectMapper.createObjectNode().put("value", 23)
        ))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_NOT_EDITABLE));
    }

    @Test
    @DisplayName("확정 후보는 값과 결정은 유지하면서 파기된 원문의 근거만 제거한다")
    void purgeSourceEvidenceKeepsConfirmedDecisionOnly() {
        SettingCandidate candidate = candidate("age", "17");
        ReflectionTestUtils.setField(candidate, "sourceChunkId", UUID.randomUUID());
        ReflectionTestUtils.setField(candidate, "sourceContentS3Key", "works/test/episodes/1/content.txt");
        ReflectionTestUtils.setField(candidate, "rawEntityMention", "원문 속 실제 표현");
        ReflectionTestUtils.setField(candidate, "comparisonReason", "원문을 인용한 비교 사유");
        ReflectionTestUtils.setField(candidate, "rawComparisonJson", objectMapper.createObjectNode().put("result", "raw"));
        candidate.confirm();

        candidate.purgeSourceEvidence();

        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
        assertThat(candidate.getAttributeName()).isEqualTo("age");
        assertThat(candidate.getAttributeValue()).isEqualTo("17");
        assertThat(candidate.getSourceChunkId()).isNull();
        assertThat(candidate.getSourceContentS3Key()).isNull();
        assertThat(candidate.getRawEntityMention()).isNull();
        assertThat(candidate.getEvidenceSpans()).isNull();
        assertThat(candidate.getRawAiResultJson()).isNull();
        assertThat(candidate.getComparisonReason()).isNull();
        assertThat(candidate.getRawComparisonJson()).isNull();
    }

    private SettingCandidate candidate(String attributeName, String attributeValue) {
        return candidate(work(), attributeName, attributeValue);
    }

    private SettingCandidate candidate(Work work, String attributeName, String attributeValue) {
        return SettingCandidate.create(
                work,
                null,
                null,
                null,
                SettingEntityType.CHARACTER,
                "아리아",
                attributeName,
                attributeValue,
                SettingValueType.NUMBER,
                objectMapper.createObjectNode().put("value", attributeValue),
                objectMapper.createArrayNode(),
                new BigDecimal("0.8000"),
                objectMapper.createObjectNode().put("raw_value", attributeValue)
        );
    }

    private SettingCandidate characterDiscovery(Work work, String entityName) {
        return SettingCandidate.createCharacterDiscovery(
                work,
                null,
                UUID.randomUUID(),
                null,
                entityName,
                "케닉의 넷째 아들 " + entityName,
                null,
                SettingCandidateMatchStatus.UNRESOLVED,
                objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                        .put("quote", "케닉의 넷째 아들 세룸은 나와라!")),
                new BigDecimal("0.9000"),
                objectMapper.createObjectNode().put("candidate_kind", "CHARACTER_DISCOVERY")
        );
    }

    private WorkCharacter character(Work work, String name) {
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
        ReflectionTestUtils.setField(character, "id", UUID.randomUUID());
        return character;
    }

    private Work work() {
        Member member = Member.register("writer@example.com", "encoded-password", "01012345678", "작가");
        return Work.create(member, "내 작품", WorkGenre.FANTASY, "내 설명");
    }
}
