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

@DisplayName("#381 검수 실행 중 확정 설정 잠금 판정")
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
            assertThat(e.getMessage()).isEqualTo("오류 리포트 검사가 진행 중이에요. 검사가 끝난 뒤 다시 저장해 주세요.");
            assertThat(e.getErrorContext()).isEqualTo(Map.of("reportId", running.reportId().toString(),
                    "validationJobId", running.validationJobId().toString(), "episodeNo", 101,
                    "executionStatus", "RUNNING", "leaseExpiresAt", "2026-11-05T03:01:00Z"));
        });
    }

    @Test
    @DisplayName("대기 중 잠금은 lease가 없어 leaseExpiresAt 키를 생략한다")
    void queuedLockOmitsLease() {
        var queued = new ValidationSettingsLockState(UUID.randomUUID(), UUID.randomUUID(), 7, ValidationExecutionStatus.QUEUED, null);
        when(jobs.findSettingsLocks(eq(work), any(), any())).thenReturn(List.of(queued));
        assertThatThrownBy(() -> lock.assertUnlocked(work)).isInstanceOfSatisfying(AppException.class, e ->
                assertThat(e.getErrorContext()).containsEntry("executionStatus", "QUEUED").containsEntry("episodeNo", 7)
                        .doesNotContainKey("leaseExpiresAt"));
    }
}
