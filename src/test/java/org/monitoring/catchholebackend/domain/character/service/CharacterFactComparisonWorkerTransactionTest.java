package org.monitoring.catchholebackend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("캐릭터 Fact batch 비교 트랜잭션 계약 테스트")
class CharacterFactComparisonWorkerTransactionTest {

    @Test
    @DisplayName("batch 비교 진입점은 클래스의 readOnly 트랜잭션을 쓰기 트랜잭션으로 덮는다")
    void batchComparisonEntrypointsUseWriteTransactions() {
        List<String> writeEntrypoints = List.of(
                "claimNextCharacterFactComparisonBatch",
                "getCharacterFactComparisonBatchContext",
                "completeCharacterFactComparisonBatch",
                "failCharacterFactComparisonBatch"
        );

        for (String methodName : writeEntrypoints) {
            Transactional transaction = Arrays.stream(
                            CharacterFactComparisonWorkerServiceImpl.class.getDeclaredMethods()
                    )
                    .filter(method -> method.getName().equals(methodName))
                    .findFirst()
                    .map(method -> method.getAnnotation(Transactional.class))
                    .orElse(null);

            assertThat(transaction)
                    .as("%s must override the class-level readOnly transaction", methodName)
                    .isNotNull();
            assertThat(transaction.readOnly()).isFalse();
        }
    }
}
