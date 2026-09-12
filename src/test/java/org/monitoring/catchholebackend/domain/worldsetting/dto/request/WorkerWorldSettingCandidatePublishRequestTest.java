package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;

@DisplayName("세계관 추출 신뢰도는 유효한 확률값을 원래 값으로 보존한다")
class WorkerWorldSettingCandidatePublishRequestTest {
    @ParameterizedTest
    @ValueSource(strings = {"0", "0.1", "0.65", "0.72", "0.8", "0.95", "1"})
    @DisplayName("0부터 1 사이 신뢰도는 특정 대표 숫자가 아니어도 허용한다")
    void acceptsFiniteProbabilityWithoutQuantizing(String probability) {
        var candidate = candidate(new BigDecimal(probability));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(candidate)).isEmpty();
        }
        assertThat(candidate.extractionConfidence()).isEqualByComparingTo(probability);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "1.01"})
    @DisplayName("신뢰도 범위를 벗어난 응답은 계속 거절한다")
    void rejectsOutsideProbabilityRange(String probability) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(candidate(new BigDecimal(probability)))).isNotEmpty();
        }
    }

    private WorkerWorldSettingCandidatePublishRequest.Candidate candidate(BigDecimal confidence) {
        return new WorkerWorldSettingCandidatePublishRequest.Candidate(WorldSettingCategory.RACE,
                "설인", "서식지", "북부", List.of(new WorkerWorldSettingCandidatePublishRequest.EvidenceSpan("설인은 북부에 산다.", 0, 11)),
                confidence, Map.of());
    }
}
