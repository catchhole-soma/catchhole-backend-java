package org.monitoring.catchholebackend.domain.upload.processor;

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
public class SettingBookStorageCompensator {
    private final ObjectStorageService storage;

    /** The mutable list accumulates only new immutable objects owned by this transaction. */
    public void registerRollbackCompensation(List<String> keys) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) throw new IllegalStateException("설정집 저장 트랜잭션이 필요합니다.");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED || keys.isEmpty()) return;
                try {
                    var result = storage.purgeReplacementObjects(keys);
                    if (!result.isComplete()) log.error("설정집 저장 롤백 객체 파기 실패: target={}, failed={}", result.targetCount(), result.failedCount());
                } catch (RuntimeException exception) {
                    log.error("설정집 저장 롤백 객체 파기 실패: count={}, error={}", keys.size(), exception.getClass().getSimpleName());
                }
            }
        });
    }
}
