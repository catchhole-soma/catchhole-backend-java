package org.monitoring.catchholebackend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@Transactional
@DisplayName("부모 진행 보고와 사용자 재시도의 캐릭터 그룹 인계")
class CharacterComparisonHandoffIntegrationTest {
    @Autowired EntityManager entities;
    @Autowired AnalysisJobRepository jobs;
    @Autowired CharacterFactComparisonJobCoordinator coordinator;
    @MockitoBean AiTokenService tokens;

    @ParameterizedTest
    @EnumSource(value = AnalysisJobStatus.class, names = {"SUCCEEDED", "FAILED"})
    @DisplayName("완료·실패 뒤 같은 입력의 진행 보고를 반복해도 검토 결과와 종료 상태를 보존한다")
    void repeatedProgressPreservesFinishedComparison(AnalysisJobStatus terminalStatus) {
        Fixture fixture = fixture();
        coordinator.handoffIfInputComplete(fixture.source());
        AnalysisJob comparison = comparisons(fixture).getFirst();
        ReflectionTestUtils.setField(comparison, "status", terminalStatus);
        var candidateStatus = terminalStatus == AnalysisJobStatus.SUCCEEDED
                ? CharacterFactComparisonStatus.COMPLETED : CharacterFactComparisonStatus.FAILED;
        ReflectionTestUtils.setField(fixture.candidate(), "comparisonStatus", candidateStatus);
        var approvedResult = JsonNodeFactory.instance.objectNode().put("result", "보존할 비교 결과");
        ReflectionTestUtils.setField(fixture.candidate(), "rawComparisonJson", approvedResult);
        entities.flush();

        coordinator.handoffIfInputComplete(fixture.source());
        coordinator.handoffIfInputComplete(fixture.source());

        assertThat(comparisons(fixture)).containsExactly(comparison);
        assertThat(comparison.getStatus()).isEqualTo(terminalStatus);
        assertThat(fixture.candidate().getComparisonStatus()).isEqualTo(candidateStatus);
        assertThat(fixture.candidate().getRawComparisonJson()).isEqualTo(approvedResult);
    }

    @Test
    @DisplayName("실패 작업에 아직 대기 중인 후보가 남아도 진행 보고로 같은 입력을 재시작하지 않는다")
    void partialFailureDoesNotRestartPendingRemainderOnProgress() {
        Fixture fixture = fixture();
        var second = candidate(fixture.source(), "profile.직책");
        coordinator.handoffIfInputComplete(fixture.source());
        ReflectionTestUtils.setField(comparisons(fixture).getFirst(), "status", AnalysisJobStatus.FAILED);
        ReflectionTestUtils.setField(fixture.candidate(), "comparisonStatus", CharacterFactComparisonStatus.FAILED);
        entities.flush();

        coordinator.handoffIfInputComplete(fixture.source());

        assertThat(comparisons(fixture)).hasSize(1);
        assertThat(fixture.candidate().getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.FAILED);
        assertThat(second.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PENDING);
    }

    @Test
    @DisplayName("사용자가 재시도하면 같은 입력의 실패 이력이 있어도 새 비교를 예약한다")
    void explicitRetryStartsNewComparisonForSameInput() {
        Fixture fixture = fixture();
        coordinator.handoffIfInputComplete(fixture.source());
        AnalysisJob failed = comparisons(fixture).getFirst();
        ReflectionTestUtils.setField(failed, "status", AnalysisJobStatus.FAILED);
        ReflectionTestUtils.setField(fixture.candidate(), "comparisonStatus", CharacterFactComparisonStatus.FAILED);
        entities.flush();

        fixture.candidate().requestComparison();
        coordinator.enqueueIfNeeded(fixture.work().getMember().getId(), fixture.candidate());

        var created = comparisons(fixture);
        assertThat(created).hasSize(2);
        assertThat(created.stream().filter(job -> job.getStatus() == AnalysisJobStatus.PENDING)).singleElement()
                .extracting(AnalysisJob::getCharacterComparisonInputHash).isEqualTo(failed.getCharacterComparisonInputHash());
        verify(tokens).ensureComparisonCanStart(fixture.work().getMember().getId());
    }

    @Test
    @DisplayName("후속 원본 회차 후보가 추가되어 입력이 달라지면 완료 이력과 무관하게 비교를 예약한다")
    void newSourceCandidateCanReopenFinishedGroup() {
        Fixture fixture = fixture();
        coordinator.handoffIfInputComplete(fixture.source());
        AnalysisJob finished = comparisons(fixture).getFirst();
        ReflectionTestUtils.setField(finished, "status", AnalysisJobStatus.SUCCEEDED);
        ReflectionTestUtils.setField(fixture.candidate(), "comparisonStatus", CharacterFactComparisonStatus.COMPLETED);
        var nextSource = source(fixture.work(), fixture.batch(), 7);
        var next = candidate(nextSource, "profile.직책");
        entities.flush();

        coordinator.handoffIfInputComplete(nextSource);

        var created = comparisons(fixture);
        assertThat(created).hasSize(2);
        assertThat(created.stream().filter(job -> job.getStatus() == AnalysisJobStatus.PENDING)).singleElement()
                .extracting(AnalysisJob::getCharacterComparisonInputHash).isNotEqualTo(finished.getCharacterComparisonInputHash());
        assertThat(next.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PENDING);
    }

    @Test
    @DisplayName("원문 재분석으로 그룹이 과거 입력 조합으로 돌아와도 명시적으로 무효화한 비교는 다시 시작한다")
    void reopenedSourceCanReturnToPreviouslyFinishedInput() {
        Fixture fixture = fixture();
        coordinator.handoffIfInputComplete(fixture.source());
        var first = comparisons(fixture).getFirst();
        ReflectionTestUtils.setField(first, "status", AnalysisJobStatus.SUCCEEDED);
        ReflectionTestUtils.setField(fixture.candidate(), "comparisonStatus", CharacterFactComparisonStatus.COMPLETED);
        var laterSource = source(fixture.work(), fixture.batch(), 7);
        var later = candidate(laterSource, "profile.직책");
        entities.flush();
        coordinator.handoffIfInputComplete(laterSource);
        var second = comparisons(fixture).stream().filter(job -> job.getStatus() == AnalysisJobStatus.PENDING)
                .findFirst().orElseThrow();
        ReflectionTestUtils.setField(second, "status", AnalysisJobStatus.SUCCEEDED);
        ReflectionTestUtils.setField(fixture.candidate(), "comparisonStatus", CharacterFactComparisonStatus.COMPLETED);
        ReflectionTestUtils.setField(later, "comparisonStatus", CharacterFactComparisonStatus.COMPLETED);
        coordinator.invalidateReopenedInputScopes(coordinator.scopeRefs(List.of(later)), java.util.Set.of(later.getId()));
        entities.remove(later);
        entities.flush();
        assertThat(fixture.candidate().getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.RECOMPARISON_REQUIRED);

        coordinator.handoffIfInputComplete(laterSource);

        var created = comparisons(fixture);
        assertThat(created).hasSize(3);
        assertThat(created.stream().filter(job -> job.getStatus() == AnalysisJobStatus.PENDING)).singleElement()
                .extracting(AnalysisJob::getCharacterComparisonInputHash).isEqualTo(first.getCharacterComparisonInputHash());
        assertThat(fixture.candidate().getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PENDING);
    }

    @Test
    @DisplayName("같은 입력의 대기·실행 작업이 있으면 반복 인계는 새 작업을 만들지 않는다")
    void pendingAndRunningComparisonRemainUnique() {
        Fixture fixture = fixture();
        coordinator.handoffIfInputComplete(fixture.source());
        coordinator.handoffIfInputComplete(fixture.source());
        var comparison = comparisons(fixture).getFirst();
        ReflectionTestUtils.setField(comparison, "status", AnalysisJobStatus.RUNNING);
        entities.flush();
        coordinator.handoffIfInputComplete(fixture.source());
        assertThat(comparisons(fixture)).containsExactly(comparison);
    }

    private Fixture fixture() {
        var member = Member.register(UUID.randomUUID() + "@example.invalid", "test-only", null, "검증 작가");
        entities.persist(member);
        var work = Work.create(member, "인계 검증", WorkGenre.FANTASY, "합성 입력");
        entities.persist(work);
        var batch = UploadBatch.create(work, member, UploadType.MULTI_EPISODE_MULTI_FILE, UploadSourceType.FILE);
        entities.persist(batch);
        var source = source(work, batch, 6);
        var candidate = candidate(source, "profile.소속");
        entities.flush();
        return new Fixture(work, batch, source, candidate);
    }

    private AnalysisJob source(Work work, UploadBatch batch, int number) {
        var episode = Episode.create(work, null, number, number + "화", "synthetic/" + UUID.randomUUID(), "v1", "a".repeat(64), 8);
        entities.persist(episode);
        var source = AnalysisJob.create(work, batch, episode, AnalysisJobType.SETTING_EXTRACTION);
        source.updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_HANDED_OFF);
        entities.persist(source);
        return source;
    }

    private SettingCandidate candidate(AnalysisJob source, String attribute) {
        var candidate = SettingCandidate.create(source.getWork(), source.getEpisode(), UUID.randomUUID(), source,
                SettingEntityType.CHARACTER, "합성 인물", attribute, "기사단", SettingValueType.STRING,
                JsonNodeFactory.instance.objectNode().put("value", "기사단"), JsonNodeFactory.instance.arrayNode(),
                null, JsonNodeFactory.instance.objectNode());
        entities.persist(candidate);
        return candidate;
    }

    private List<AnalysisJob> comparisons(Fixture fixture) {
        return jobs.findAllByBatchIdAndJobTypeOrderByCreatedAtAsc(fixture.batch().getId(), AnalysisJobType.CHARACTER_FACT_COMPARISON);
    }

    private record Fixture(Work work, UploadBatch batch, AnalysisJob source, SettingCandidate candidate) {}
}
