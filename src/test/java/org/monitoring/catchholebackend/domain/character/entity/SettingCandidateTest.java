package org.monitoring.catchholebackend.domain.character.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;

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
    @DisplayName("검토용 설정 후보 내용을 수정한다")
    void updateReviewContentChangesEditableFields() {
        SettingCandidate candidate = candidate("age", "17");
        JsonNode valueJson = objectMapper.createObjectNode()
                .put("value", 23)
                .put("source", "user_review");
        JsonNode evidenceSpans = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                        .put("paragraph_index", 2)
                        .put("quote", "아리아는 스물셋의 경지에 올랐다."));

        candidate.updateReviewContent(
                "아리아",
                "level",
                "23",
                SettingValueType.NUMBER,
                valueJson,
                evidenceSpans
        );

        assertThat(candidate.getEntityName()).isEqualTo("아리아");
        assertThat(candidate.getAttributeName()).isEqualTo("level");
        assertThat(candidate.getAttributeValue()).isEqualTo("23");
        assertThat(candidate.getValueType()).isEqualTo(SettingValueType.NUMBER);
        assertThat(candidate.getValueJson()).isEqualTo(valueJson);
        assertThat(candidate.getEvidenceSpans()).isEqualTo(evidenceSpans);
        assertThat(candidate.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("확정 또는 무시된 후보는 검토 대기 상태가 아니다")
    void reviewedCandidateIsNotPendingReview() {
        SettingCandidate confirmed = candidate("age", "17");
        SettingCandidate dismissed = candidate("level", "23");

        confirmed.confirm();
        dismissed.dismiss();

        assertThat(confirmed.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
        assertThat(confirmed.isPendingReview()).isFalse();
        assertThat(dismissed.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.DISMISSED);
        assertThat(dismissed.isPendingReview()).isFalse();
    }

    private SettingCandidate candidate(String attributeName, String attributeValue) {
        return SettingCandidate.create(
                work(),
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

    private Work work() {
        Member member = Member.register("writer@example.com", "encoded-password", "01012345678", "작가");
        return Work.create(member, "내 작품", "판타지", "내 설명");
    }
}
