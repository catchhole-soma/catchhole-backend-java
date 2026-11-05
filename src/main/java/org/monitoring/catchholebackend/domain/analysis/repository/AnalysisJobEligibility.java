package org.monitoring.catchholebackend.domain.analysis.repository;

/**
 * Worker 능력 협상과 분리한 실제 도메인 실행 조건. claim과 DB snapshot이 공유한다.
 * GH312: 직접·target-only 회차의 현재 검수 report에 검토 종료 receipt가 없으면 claim하지 않는다
 * ({@code AnalysisJobRepository.existsValidationStagingForEpisodes}와 같은 규칙).
 */
final class AnalysisJobEligibility {
    private AnalysisJobEligibility() {}
    static final String DOMAIN = """
            job.work.lifecycleStatus = org.monitoring.catchholebackend.domain.work.type.WorkLifecycleStatus.ACTIVE
            and not exists (select report.id from ValidationReport report where report.work.id = job.work.id
                and report.current = true
                and (report.episode.id = job.episode.id or exists (
                    select target.id from AnalysisJob owner join owner.targetEpisodes target
                    where owner.id = job.id and target.id = report.episode.id))
                and not exists (select completion.id from ValidationReviewCompletion completion
                    where completion.report.id = report.id))
            and (job.analysisMode = org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode.CONFIRMED_ONLY
                or (job.journalStatus = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus.PENDING
                    and (job.runSequence = 0 or exists (
                        select predecessor.id from AnalysisJob predecessor
                        where predecessor.id = job.predecessorJobId
                          and predecessor.analysisRunId = job.analysisRunId
                          and predecessor.runGeneration = job.runGeneration
                          and predecessor.work.id = job.work.id
                          and predecessor.runSequence = job.runSequence - 1
                          and predecessor.status = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.SUCCEEDED
                          and predecessor.journalStatus = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus.SEALED
                          and (predecessor.reviewMode = org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.MANUAL
                               or predecessor.automaticAppliedAt is not null)
                    ))))
            and (job.jobType <> org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION
                or not exists (
                    select running.id from AnalysisJob running where running.work.id = job.work.id
                      and running.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION
                      and running.status = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING
                ))

            """;

    /**
     * #381 claim 전용 조건. 같은 작품의 검수 실행이 확정 설정을 잠근 동안 확정 설정을 쓸 수 있는 자동 작업(추출·캐릭터 비교·세계관 비교)은 claim하지 않는다.
     * 메트릭 snapshot의 DOMAIN에는 넣지 않는다. 파라미터 {@code :lockNow}·{@code :lockQueuedCutoff}는 {@code ValidationSettingsLock.window()}로 채운다.
     */
    static final String VALIDATION_LOCK = """
            (job.jobType not in (org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION,
                    org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.CHARACTER_FACT_COMPARISON,
                    org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.WORLD_SETTING_COMPARISON)
                or not exists (select lockJob.id from ValidationJob lockJob join lockJob.report lockReport
                    where lockReport.work.id = job.work.id and
            """ + org.monitoring.catchholebackend.domain.validation.repository.ValidationJobRepository.SETTINGS_LOCK + """
                ))
            """;
}
