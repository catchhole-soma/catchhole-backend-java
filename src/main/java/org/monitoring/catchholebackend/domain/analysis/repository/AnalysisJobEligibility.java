package org.monitoring.catchholebackend.domain.analysis.repository;

/** Worker 능력 협상과 분리한 실제 도메인 실행 조건. claim과 DB snapshot이 공유한다. */
final class AnalysisJobEligibility {
    private AnalysisJobEligibility() {}
    static final String DOMAIN = """
            job.work.lifecycleStatus = org.monitoring.catchholebackend.domain.work.type.WorkLifecycleStatus.ACTIVE
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
}
