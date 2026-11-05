package org.monitoring.catchholebackend.domain.validation.processor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.validation.repository.ValidationJobRepository;
import org.monitoring.catchholebackend.domain.validation.repository.ValidationSettingsLockState;
import org.monitoring.catchholebackend.domain.validation.type.ValidationExecutionStatus;
import org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties;
import org.monitoring.catchholebackend.global.exception.AppException;

@DisplayName("#381 검수 접수부터 검토 종료까지 확정 설정 잠금 판정")
class ValidationSettingsLockTest {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Instant NOW = Instant.parse("2026-11-05T03:00:00Z");
    final ValidationJobRepository jobs = mock(ValidationJobRepository.class);
    final ValidationExecutionProperties properties = mock(ValidationExecutionProperties.class);
    final ValidationSettingsLock lock = new ValidationSettingsLock(jobs, properties, Clock.fixed(NOW, SEOUL));
    final UUID work = UUID.randomUUID();

    @Test
    @DisplayName("lease 비교 시각은 지금이고 QUEUED 기준 시각은 대기 잠금 시간만큼 앞선 시스템 시간대 시각이다")
    void windowUsesNowForLeasesAndQueuedTimeoutForQueuedJobs() {
        when(properties.settingsLockQueuedTimeout()).thenReturn(Duration.ofMinutes(10));
        var window = lock.window();
        assertThat(window.now()).isEqualTo(NOW);
        assertThat(window.queuedCutoff()).isEqualTo(LocalDateTime.of(2026, 11, 5, 11, 50));
        lock.find(work);
        verify(jobs).findSettingsLocks(work, NOW, LocalDateTime.of(2026, 11, 5, 11, 50));
    }

    @Test
    @DisplayName("실행이 꺼져 대기 잠금 시간이 없으면 QUEUED 기준 시각이 지금이라 대기 Job은 잠그지 않는다")
    void disabledExecutionNeverLocksQueuedJobs() {
        when(properties.settingsLockQueuedTimeout()).thenReturn(null);
        assertThat(lock.window().queuedCutoff()).isEqualTo(LocalDateTime.of(2026, 11, 5, 12, 0));
    }

    @Test
    @DisplayName("잠금이 없으면 쓰기를 허용하고 아무것도 던지지 않는다")
    void noLockAllowsWrites() {
        when(jobs.findSettingsLocks(eq(work), any(), any())).thenReturn(List.of());
        assertThat(lock.find(work)).isEmpty();
        assertThatCode(() -> lock.assertUnlocked(work)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("실행 중 잠금은 409와 report·Job·회차·실행 상태·lease 만료 시각을 context로 준다")
    void runningLockRejectsWithFullContext() {
        var running = new ValidationSettingsLockState(UUID.randomUUID(), UUID.randomUUID(), 101, ValidationExecutionStatus.RUNNING, NOW.plusSeconds(60));
        var later = new ValidationSettingsLockState(UUID.randomUUID(), UUID.randomUUID(), 99, ValidationExecutionStatus.QUEUED, null);
        when(jobs.findSettingsLocks(eq(work), any(), any())).thenReturn(List.of(running, later));
        assertThat(lock.find(work)).contains(running);
        assertThatThrownBy(() -> lock.assertUnlocked(work)).isInstanceOfSatisfying(AppException.class, e -> {
            assertThat(e.getResultCode().getCode()).isEqualTo("VALIDATION_SETTINGS_LOCKED");
            assertThat(e.getResultCode().getStatus().value()).isEqualTo(409);
            assertThat(e.getMessage()).isEqualTo("오류 리포트 검수가 끝나지 않아 설정을 바꿀 수 없어요. 오류 리포트에서 수정하거나 검토를 마친 뒤 저장해 주세요.");
            assertThat(e.getErrorContext()).isEqualTo(Map.of("reportId", running.reportId().toString(),
                    "validationJobId", running.validationJobId().toString(), "episodeNo", 101,
                    "executionStatus", "RUNNING", "lockReason", "EXECUTION", "leaseExpiresAt", "2026-11-05T03:01:00Z"));
        });
    }

    @Test
    @DisplayName("대기 중 잠금은 lease가 없어 leaseExpiresAt 키를 생략한다")
    void queuedLockOmitsLease() {
        var queued = new ValidationSettingsLockState(UUID.randomUUID(), UUID.randomUUID(), 7, ValidationExecutionStatus.QUEUED, null);
        when(jobs.findSettingsLocks(eq(work), any(), any())).thenReturn(List.of(queued));
        assertThatThrownBy(() -> lock.assertUnlocked(work)).isInstanceOfSatisfying(AppException.class, e ->
                assertThat(e.getErrorContext()).containsEntry("executionStatus", "QUEUED").containsEntry("episodeNo", 7)
                        .containsEntry("lockReason", "EXECUTION").doesNotContainKey("leaseExpiresAt"));
    }

    @Test
    @DisplayName("검토 종료 전 COMPLETED 잠금은 lockReason REVIEW이고 남은 마지막 lease는 내보내지 않으며 일반 쓰기를 막는다")
    void reviewLockRejectsOrdinaryWrites() {
        var review = new ValidationSettingsLockState(UUID.randomUUID(), UUID.randomUUID(), 101, ValidationExecutionStatus.COMPLETED, NOW.minusSeconds(5));
        when(jobs.findSettingsLocks(eq(work), any(), any())).thenReturn(List.of(review));
        assertThatThrownBy(() -> lock.assertUnlocked(work)).isInstanceOfSatisfying(AppException.class, e ->
                assertThat(e.getErrorContext()).containsEntry("lockReason", "REVIEW").containsEntry("executionStatus", "COMPLETED")
                        .doesNotContainKey("leaseExpiresAt"));
    }

    @Test
    @DisplayName("정정은 그 리포트의 검토 단계 잠금만 통과하고 그 리포트의 실행 단계·다른 리포트의 잠금은 막는다")
    void correctionPassesOnlyItsOwnReviewLock() {
        UUID report = UUID.randomUUID();
        var ownReview = new ValidationSettingsLockState(report, UUID.randomUUID(), 101, ValidationExecutionStatus.COMPLETED, null);
        var ownRunning = new ValidationSettingsLockState(report, UUID.randomUUID(), 101, ValidationExecutionStatus.RUNNING, NOW.plusSeconds(60));
        var otherReview = new ValidationSettingsLockState(UUID.randomUUID(), UUID.randomUUID(), 100, ValidationExecutionStatus.COMPLETED, null);
        var otherQueued = new ValidationSettingsLockState(UUID.randomUUID(), UUID.randomUUID(), 100, ValidationExecutionStatus.QUEUED, null);

        when(jobs.findSettingsLocks(eq(work), any(), any())).thenReturn(List.of());
        assertThatCode(() -> lock.assertUnlockedForCorrection(work, report)).as("잠금 없음").doesNotThrowAnyException();
        when(jobs.findSettingsLocks(eq(work), any(), any())).thenReturn(List.of(ownReview));
        assertThatCode(() -> lock.assertUnlockedForCorrection(work, report)).as("자기 검토 단계").doesNotThrowAnyException();
        for (var blocking : List.of(ownRunning, otherReview, otherQueued)) {
            when(jobs.findSettingsLocks(eq(work), any(), any())).thenReturn(blocking == otherQueued ? List.of(ownReview, otherQueued) : List.of(blocking));
            assertThatThrownBy(() -> lock.assertUnlockedForCorrection(work, report)).as(blocking.toString())
                    .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getErrorContext())
                            .containsEntry("reportId", blocking.reportId().toString()).containsEntry("lockReason", blocking.reason().name()));
        }
    }
}
