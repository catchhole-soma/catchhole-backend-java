package org.monitoring.catchholebackend.domain.paymentreview;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.global.config.paymentreview.PaymentReviewConfig;
import org.monitoring.catchholebackend.global.config.paymentreview.PaymentReviewProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("결제 심사 설정의 기본 비공개와 회원 목록 바인딩")
class PaymentReviewConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PaymentReviewConfig.class);

    @Test
    @DisplayName("설정이 없으면 기능은 꺼지고 허용 목록은 비어 있다")
    void unsetConfigurationIsClosed() {
        runner.run(context -> {
            var properties = context.getBean(PaymentReviewProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getMemberIds()).isEmpty();
        });
    }

    @Test
    @DisplayName("빈 환경변수는 빈 목록으로 바인딩하고 쉼표 목록은 회원 번호로 읽는다")
    void blankAndCommaSeparatedIdsBind() {
        runner.withPropertyValues("payment-review.enabled=true", "payment-review.member-ids=")
                .run(context -> assertThat(context.getBean(PaymentReviewProperties.class).getMemberIds()).isEmpty());
        runner.withPropertyValues("payment-review.member-ids=441,442")
                .run(context -> assertThat(context.getBean(PaymentReviewProperties.class).getMemberIds())
                        .containsExactlyInAnyOrder(441L, 442L));
    }
}
