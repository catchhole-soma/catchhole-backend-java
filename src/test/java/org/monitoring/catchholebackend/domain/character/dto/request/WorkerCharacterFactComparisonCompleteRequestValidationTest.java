package org.monitoring.catchholebackend.domain.character.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;

@DisplayName("Worker 캐릭터 설정 비교 완료 요청 검증")
class WorkerCharacterFactComparisonCompleteRequestValidationTest {

    @Test
    @DisplayName("제거 대상 목록의 null 항목을 요청 검증 단계에서 거절한다")
    void rejectsNullRemovedSnapshotEntry() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        var request = new WorkerCharacterFactComparisonCompleteRequest(
                CharacterFactOperation.HISTORY_ONLY,
                null,
                null,
                null,
                null,
                java.util.Collections.singletonList(null),
                CharacterFactTemporalScope.PAST,
                "과거 회상으로 현재값에는 반영하지 않음",
                "a".repeat(64),
                null
        );

        assertThat(validator.validate(request))
                .anyMatch(violation -> violation.getPropertyPath().toString()
                        .startsWith("removedSnapshotEntries"));
    }
}
