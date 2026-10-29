package org.monitoring.catchholebackend.domain.validation.event;

import java.util.UUID;

/** Work 잠금 안의 동기 이벤트. 원문/확정 설정 변경과 오류 기준 무효화를 같은 트랜잭션에 처리한다. */
public final class ValidationStagingEvents {
    private ValidationStagingEvents() {}
    public record ConfirmedSettingsChanged(UUID workId) {}
    public record EpisodeSourceChanged(UUID workId, UUID episodeId, String retainedContentKey) {
        public EpisodeSourceChanged(UUID workId, UUID episodeId) { this(workId,episodeId,null); }
    }
    public record SettingBookSourceChanged(UUID workId, UUID settingBookId) {}
    public record ReportInvalidated(UUID workId, UUID reportId) {}
}
