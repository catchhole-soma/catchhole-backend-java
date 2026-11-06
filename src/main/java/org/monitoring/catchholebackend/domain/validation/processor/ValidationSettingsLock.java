package org.monitoring.catchholebackend.domain.validation.processor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode;
import org.monitoring.catchholebackend.domain.validation.repository.ValidationJobRepository;
import org.monitoring.catchholebackend.domain.validation.repository.ValidationSettingsLockState;
import org.monitoring.catchholebackend.domain.validation.type.ValidationExecutionStatus;
import org.monitoring.catchholebackend.domain.validation.type.ValidationSettingsLockReason;
import org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * #381 확정 설정 잠금의 유일한 판정. 확정 전 검수가 접수부터 검토 종료까지 현재 DB 설정을 기준으로 삼는 동안 사람의 확정 설정 쓰기를 막는다.
 * 조건은 {@link ValidationJobRepository#SETTINGS_LOCK}이며 분석 Job claim도 같은 식과 이 시각({@link #window()})을 쓴다.
 *
 * <p>lease가 만료된 RUNNING, 대기 잠금 시간(settings-lock-queued-timeout)을 넘긴 QUEUED, PARTIAL·FAILED·CANCELED, 검토 종료 receipt가 있는 COMPLETED,
 * 무효·퇴역 report는 잠그지 않아 Worker가 죽거나 실행이 깨져도 잠금이 남지 않는다. 실행이 꺼져 대기 잠금 시간이 없으면 QUEUED는 잠그지 않는다(가져갈 Worker가 없다).
 * 검토 단계(REVIEW)에서는 그 리포트 자신의 정정만 {@link #assertUnlockedForCorrection}으로 통과한다.
 * 호출자는 Work 행을 먼저 잠근 트랜잭션에서 호출하며 이 컴포넌트는 행을 잠그지 않는다.
 */
@Component
public class ValidationSettingsLock {
    private final ValidationJobRepository jobs;
    private final ValidationExecutionProperties properties;
    private final Clock clock;

    @Autowired
    public ValidationSettingsLock(ValidationJobRepository jobs, ValidationExecutionProperties properties) {
        this(jobs, properties, Clock.systemDefaultZone());
    }

    ValidationSettingsLock(ValidationJobRepository jobs, ValidationExecutionProperties properties, Clock clock) {
        this.jobs = jobs;
        this.properties = properties;
        this.clock = clock;
    }

    /** 잠금 판정 시각. now는 RUNNING lease 비교, queuedCutoff는 QUEUED Job의 updatedAt(시스템 시간대 LocalDateTime) 비교에 쓴다. */
    public record Window(Instant now, LocalDateTime queuedCutoff) {}

    public Window window() {
        Instant now = clock.instant();
        Duration timeout = properties.settingsLockQueuedTimeout();
        return new Window(now, LocalDateTime.ofInstant(timeout == null ? now : now.minus(timeout), clock.getZone()));
    }

    /** 작품의 확정 설정을 잠그는 가장 먼저 접수된 검수 실행. 쓰기 없음. */
    public Optional<ValidationSettingsLockState> find(UUID workId) {
        return locks(workId).stream().findFirst();
    }

    /** 사람의 확정 설정 쓰기 직전에 호출한다. 잠겨 있으면 아무것도 쓰기 전에 409 VALIDATION_SETTINGS_LOCKED. */
    public void assertUnlocked(UUID workId) {
        find(workId).ifPresent(lock -> { throw toException(lock); });
    }

    /**
     * 검수 리포트 자신의 정정(ValidationCorrectionService) 전용. 그 리포트의 검토 단계(REVIEW) 잠금만 통과하고, 그 리포트의 실행 단계와
     * 다른 리포트의 잠금은 그대로 409다. 정정이 확정 설정을 바꾸면 기존 무효화 규칙으로 리포트가 INVALIDATED가 되어 잠금도 끝난다.
     */
    public void assertUnlockedForCorrection(UUID workId, UUID reportId) {
        locks(workId).stream()
                .filter(lock -> !(lock.reportId().equals(reportId) && lock.reason() == ValidationSettingsLockReason.REVIEW))
                .findFirst().ifPresent(lock -> { throw toException(lock); });
    }

    private List<ValidationSettingsLockState> locks(UUID workId) {
        Window window = window();
        return jobs.findSettingsLocks(workId, window.now(), window.queuedCutoff());
    }

    static AppException toException(ValidationSettingsLockState lock) {
        var context = new LinkedHashMap<String, Object>();
        context.put("reportId", lock.reportId().toString());
        context.put("validationJobId", lock.validationJobId().toString());
        context.put("episodeNo", lock.episodeNo());
        context.put("executionStatus", lock.executionStatus().name());
        context.put("lockReason", lock.reason().name());
        // lease는 실행 중에만 의미가 있다. 종료된 Job은 마지막 lease가 남아 있어도 내보내지 않는다(AppException context는 null 값을 담지 못해 키를 생략).
        if (lock.executionStatus() == ValidationExecutionStatus.RUNNING && lock.leaseExpiresAt() != null)
            context.put("leaseExpiresAt", lock.leaseExpiresAt().toString());
        return new AppException(ValidationErrorCode.VALIDATION_SETTINGS_LOCKED, context);
    }
}
