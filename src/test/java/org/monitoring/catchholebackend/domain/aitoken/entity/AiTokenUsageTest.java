package org.monitoring.catchholebackend.domain.aitoken.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenPurpose;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageStatus;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;

class AiTokenUsageTest {

    @Test
    void releaseAcceptsWorkPurgeCancellation() {
        AnalysisJob job = mock(AnalysisJob.class);
        Work work = mock(Work.class);
        Member member = mock(Member.class);
        when(job.getWork()).thenReturn(work);
        when(work.getMember()).thenReturn(member);
        AiTokenUsage usage = AiTokenUsage.reserve(
                UUID.randomUUID(),
                job,
                AiTokenPurpose.SETTING_EXTRACTION,
                1,
                "test-model",
                100
        );

        usage.release(AiTokenUsageOutcome.WORK_PURGE_CANCELED);

        assertThat(usage.getStatus()).isEqualTo(AiTokenUsageStatus.RELEASED);
        assertThat(usage.getOutcome()).isEqualTo(AiTokenUsageOutcome.WORK_PURGE_CANCELED);
    }
}
