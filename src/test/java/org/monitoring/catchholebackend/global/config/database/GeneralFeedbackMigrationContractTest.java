package org.monitoring.catchholebackend.global.config.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

@DisplayName("일반 피드백 보상 migration 계약")
class GeneralFeedbackMigrationContractTest {

    @Test
    @DisplayName("의견 저장 테이블과 회원당 한 번인 일반 피드백 보상 요청 제약을 함께 만든다")
    void createsFeedbackStorageAndLifetimeRewardConstraint() throws IOException {
        String sql = new ClassPathResource(
                "db/migration/V34__add_general_feedback_rewards.sql"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE feedbacks")
                .contains("request_source = 'GENERAL_FEEDBACK_REWARD'")
                .contains("CREATE UNIQUE INDEX uk_ai_token_extension_requests_member_feedback_reward")
                .contains("ON DELETE SET NULL");
    }
}
