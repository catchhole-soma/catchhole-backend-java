package org.monitoring.catchholebackend.domain.episode.processor;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
@Slf4j
public class EpisodeReplacementStorageCompensator {

    private final ObjectStorageService objectStorageService;

    /** 새 S3 객체를 만든 DB 트랜잭션이 롤백되면 해당 객체의 모든 version을 보상 파기한다. */
    public void registerRollbackCompensation(List<String> replacementObjectKeys) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("회차 교체 트랜잭션 동기화가 활성화되지 않았습니다.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED || replacementObjectKeys.isEmpty()) {
                    return;
                }
                try {
                    var result = objectStorageService.purgeReplacementObjects(replacementObjectKeys);
                    if (!result.isComplete()) {
                        log.error(
                                "롤백된 회차 교체 객체 일부 보상 파기 실패: target={}, deleted={}, failed={}",
                                result.targetCount(),
                                result.deletedCount(),
                                result.failedCount()
                        );
                    }
                } catch (RuntimeException exception) {
                    log.error("롤백된 회차 교체 객체 보상 파기 실패: keys={}", replacementObjectKeys, exception);
                }
            }
        });
    }
}
