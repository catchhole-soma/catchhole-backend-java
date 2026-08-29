package org.monitoring.catchholebackend.domain.worldsetting.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.exception.WorldSettingErrorCode;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation;
import org.monitoring.catchholebackend.global.exception.AppException;

@DisplayName("세계관 설정 후보 Entity 단위 테스트")
class WorldSettingCandidateTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("1차 추출 후보는 비교 대기와 검토 대기 상태로 생성된다")
    void createInitializesPendingStatuses() {
        Fixture fixture = fixture();

        WorldSettingCandidate candidate = candidate(fixture);

        assertThat(candidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.PENDING);
        assertThat(candidate.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.PENDING_REVIEW);
        assertThat(candidate.getSubjectName()).isEqualTo("바바리안");
        assertThat(candidate.getSettingName()).isEqualTo("서식지");
    }

    @Test
    @DisplayName("2차 비교 결과에 대상 버전과 제안 내용을 보존한다")
    void completeComparisonStoresProposal() {
        Fixture fixture = fixture();
        WorldSetting setting = WorldSetting.create(
                fixture.work(),
                WorldSettingCategory.RACE,
                "바바리안",
                "특징",
                "전투 종족"
        );
        WorldSettingCandidate candidate = candidate(fixture);

        candidate.startComparison();
        candidate.completeComparison(
                setting,
                WorldSettingOperation.ADD,
                "서식지",
                null,
                "혹한 지역",
                "기존 대상의 새 속성",
                objectMapper.createObjectNode().put("operation", "ADD"),
                LocalDateTime.of(2026, 8, 6, 12, 0)
        );

        assertThat(candidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.COMPLETED);
        assertThat(candidate.getTargetWorldSetting()).isSameAs(setting);
        assertThat(candidate.getSuggestedOperation()).isEqualTo(WorldSettingSuggestedOperation.ADD);
        assertThat(candidate.getBaseWorldSettingVersion()).isZero();
        assertThat(candidate.getBeforeValue()).isNull();
    }

    @Test
    @DisplayName("완료된 세계관 비교는 retry로 다시 대기 상태가 될 수 없다")
    void completedComparisonCannotBeRetried() {
        Fixture fixture = fixture();
        WorldSettingCandidate candidate = candidate(fixture);
        candidate.startComparison();
        candidate.completeComparison(
                null,
                WorldSettingOperation.ADD,
                "서식지",
                null,
                "혹한 지역",
                "기존 대상의 새 속성",
                objectMapper.createObjectNode().put("operation", "ADD"),
                LocalDateTime.of(2026, 8, 6, 12, 0)
        );

        assertThatThrownBy(candidate::requestRecomparison)
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(
                                WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT
                        ));
        assertThat(candidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.COMPLETED);
    }

    @Test
    @DisplayName("비교가 완료되지 않은 후보는 확정할 수 없다")
    void confirmRequiresCompletedComparison() {
        Fixture fixture = fixture();
        WorldSettingCandidate candidate = candidate(fixture);
        WorldSetting setting = WorldSetting.create(
                fixture.work(),
                WorldSettingCategory.RACE,
                "바바리안",
                "서식지",
                "혹한 지역"
        );

        assertThatThrownBy(() -> candidate.confirm(
                WorldSettingOperation.ADD,
                WorldSettingCategory.RACE,
                "바바리안",
                "서식지",
                "혹한 지역",
                null,
                fixture.member(),
                setting
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_NOT_READY));
    }

    @Test
    @DisplayName("제외는 확정본을 연결하지 않고 최종 제외 결정을 기록한다")
    void dismissRecordsFinalDecision() {
        Fixture fixture = fixture();
        WorldSettingCandidate candidate = candidate(fixture);

        boolean changed = candidate.dismiss("일시적 사건", fixture.member());

        assertThat(changed).isTrue();
        assertThat(candidate.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.DISMISSED);
        assertThat(candidate.getFinalOperation()).isEqualTo(WorldSettingOperation.EXCLUDE);
        assertThat(candidate.getFinalSubjectName()).isEqualTo("바바리안");
        assertThat(candidate.getAppliedWorldSettingVersion()).isNull();
        assertThat(candidate.dismiss("다시 제외", fixture.member())).isFalse();
    }

    @Test
    @DisplayName("검토가 끝난 후보는 최종 결정은 유지하면서 파기된 원문의 근거만 제거한다")
    void purgeSourceEvidenceKeepsReviewedDecisionOnly() {
        Fixture fixture = fixture();
        WorldSettingCandidate candidate = candidate(fixture);
        candidate.dismiss("일시적 사건", fixture.member());

        candidate.purgeSourceEvidence();

        assertThat(candidate.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.DISMISSED);
        assertThat(candidate.getFinalOperation()).isEqualTo(WorldSettingOperation.EXCLUDE);
        assertThat(candidate.getFinalValue()).isEqualTo("혹한 지역");
        assertThat(candidate.getEvidenceSpans()).isEmpty();
        assertThat(candidate.getRawExtractionJson()).isNull();
        assertThat(candidate.getRawComparisonJson()).isNull();
    }

    private WorldSettingCandidate candidate(Fixture fixture) {
        return WorldSettingCandidate.create(
                fixture.work(),
                fixture.episode(),
                fixture.analysisJob(),
                WorldSettingCategory.RACE,
                "  바바리안  ",
                "  서식지  ",
                "  혹한 지역  ",
                objectMapper.createArrayNode().add(
                        objectMapper.createObjectNode()
                                .put("quote", "바바리안은 혹한 지역에 산다.")
                                .put("startOffset", 0)
                                .put("endOffset", 18)
                ),
                new BigDecimal("0.9500"),
                objectMapper.createObjectNode().put("category", "RACE")
        );
    }

    private Fixture fixture() {
        Member member = Member.register(
                "writer@example.com",
                "encoded-password",
                "01012345678",
                "작가"
        );
        Work work = Work.create(member, "설원 전기", WorkGenre.FANTASY, "세계관 설정 테스트");
        UploadBatch batch = UploadBatch.create(
                work,
                member,
                UploadType.SINGLE_EPISODE,
                UploadSourceType.FILE
        );
        Episode episode = Episode.create(
                work,
                null,
                1,
                "1화",
                "works/test/episodes/1.txt",
                "version-1",
                "hash-1",
                100
        );
        AnalysisJob analysisJob = AnalysisJob.create(
                work,
                batch,
                episode,
                AnalysisJobType.SETTING_EXTRACTION
        );
        return new Fixture(member, work, episode, analysisJob);
    }

    private record Fixture(Member member, Work work, Episode episode, AnalysisJob analysisJob) {
    }
}
