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

        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
        assertThat(candidate.isPendingReview()).isTrue();
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
    @DisplayName("같은 이름 자동 연결은 별도 상태로 남고 사용자가 다시 연결하면 일반 연결로 바뀐다")
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
    @DisplayName("확정 반영이 결정한 캐릭터는 후보를 다시 편집 가능하게 만들지 않고 연결한다")
    void matchPromotedCharacterConnectsConfirmedCandidate() {
        Work work = work();
        SettingCandidate candidate = candidate(work, "age", "17");
        WorkCharacter character = character(work, "아리아");
        candidate.confirm();

        candidate.matchPromotedCharacter(character);

        assertThat(candidate.getEntityName()).isEqualTo("아리아");
        assertThat(candidate.getMatchedCharacterId()).isEqualTo(character.getId());
        assertThat(candidate.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.MATCHED);
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
        assertThat(candidate.isPendingReview()).isFalse();
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
