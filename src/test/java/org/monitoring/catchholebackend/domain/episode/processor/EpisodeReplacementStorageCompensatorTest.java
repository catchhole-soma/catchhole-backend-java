package org.monitoring.catchholebackend.domain.episode.processor;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.monitoring.catchholebackend.global.storage.ObjectStoragePurgeResult;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
@DisplayName("회차 교체 저장소 롤백 보상 단위 테스트")
class EpisodeReplacementStorageCompensatorTest {

    @Mock
    private ObjectStorageService objectStorageService;

    private EpisodeReplacementStorageCompensator compensator;

    @BeforeEach
    void setUp() {
        compensator = new EpisodeReplacementStorageCompensator(objectStorageService);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("DB 트랜잭션이 롤백되면 새 업로드 원본과 새 원문의 모든 version을 보상 파기한다")
    void purgesNewObjectsAfterRollback() {
        List<String> newObjectKeys = new ArrayList<>(List.of(
                "upload-batches/new/original.txt",
                "works/work/episodes/1/new/1.txt"
        ));
        when(objectStorageService.purgeReplacementObjects(newObjectKeys))
                .thenReturn(new ObjectStoragePurgeResult(2, 2, 0));

        compensator.registerRollbackCompensation(newObjectKeys);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCompletion(
                        TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(objectStorageService).purgeReplacementObjects(newObjectKeys);
    }

    @Test
    @DisplayName("DB 트랜잭션이 커밋되면 새 객체를 지우지 않는다")
    void keepsNewObjectsAfterCommit() {
        List<String> newObjectKeys = new ArrayList<>(List.of("works/work/episodes/1/new/1.txt"));

        compensator.registerRollbackCompensation(newObjectKeys);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCompletion(
                        TransactionSynchronization.STATUS_COMMITTED));

        verify(objectStorageService, never()).purgeReplacementObjects(newObjectKeys);
    }
}
