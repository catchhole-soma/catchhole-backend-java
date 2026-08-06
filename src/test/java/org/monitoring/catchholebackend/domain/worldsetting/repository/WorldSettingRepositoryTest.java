package org.monitoring.catchholebackend.domain.worldsetting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("세계관 설정 Repository 통합 테스트")
class WorldSettingRepositoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WorkRepository workRepository;

    @Autowired
    private UploadBatchRepository uploadBatchRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private WorldSettingRepository worldSettingRepository;

    @Autowired
    private WorldSettingCandidateRepository candidateRepository;

    @Autowired
    private EntityManager entityManager;

    private Member member;
    private Work work;
    private Episode episode;
    private AnalysisJob analysisJob;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        member = memberRepository.save(Member.register(
                "writer-%s@example.com".formatted(suffix),
                "encoded-password",
                "010%s".formatted(suffix),
                "작가"
        ));
        work = workRepository.save(Work.create(member, "설원 전기", WorkGenre.FANTASY, "세계관 설정 테스트"));
        UploadBatch batch = uploadBatchRepository.save(UploadBatch.create(
                work,
                member,
                UploadType.SINGLE_EPISODE,
                UploadSourceType.FILE
        ));
        episode = episodeRepository.save(Episode.create(
                work,
                null,
                1,
                "1화",
                "works/%s/episodes/1.txt".formatted(work.getId()),
                "version-1",
                "hash-1",
                100
        ));
        analysisJob = analysisJobRepository.save(AnalysisJob.create(
                work,
                batch,
                episode,
                AnalysisJobType.SETTING_EXTRACTION
        ));
    }

    @Test
    @DisplayName("확정본과 후보의 JSONB, enum, 비교 연결을 저장하고 조회한다")
    void saveAndFindWorldSettingAndCandidate() {
        WorldSetting worldSetting = worldSettingRepository.save(WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "바바리안",
                "서식지",
                "혹한 지역"
        ));
        WorldSettingCandidate candidate = WorldSettingCandidate.create(
                work,
                episode,
                analysisJob,
                WorldSettingCategory.RACE,
                "바바리안",
                "특징",
                "전투에 특화된 종족",
                objectMapper.createArrayNode().add(
                        objectMapper.createObjectNode()
                                .put("quote", "바바리안은 전투에 특화된 종족이다.")
                                .put("startOffset", 10)
                                .put("endOffset", 31)
                ),
                new BigDecimal("0.9300"),
                objectMapper.createObjectNode().put("category", "RACE")
        );
        candidate.completeComparison(
                worldSetting,
                WorldSettingOperation.ADD,
                "특징",
                null,
                "전투에 특화된 종족",
                "기존 대상에 없는 속성",
                objectMapper.createObjectNode().put("operation", "ADD"),
                LocalDateTime.of(2026, 8, 6, 13, 0)
        );
        candidateRepository.save(candidate);
        entityManager.flush();
        entityManager.clear();

        WorldSetting foundSetting = worldSettingRepository.findByIdAndWorkIdForUpdate(
                worldSetting.getId(),
                work.getId()
        ).orElseThrow();
        WorldSettingCandidate foundCandidate = candidateRepository.findByIdAndWorkIdForUpdate(
                candidate.getId(),
                work.getId()
        ).orElseThrow();

        assertThat(foundSetting.getPropertyValue("서식지")).isEqualTo("혹한 지역");
        assertThat(foundSetting.getVersion()).isZero();
        assertThat(foundCandidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.COMPLETED);
        assertThat(foundCandidate.getTargetWorldSetting().getId()).isEqualTo(foundSetting.getId());
        assertThat(foundCandidate.getEvidenceSpans().get(0).get("quote").asText())
                .isEqualTo("바바리안은 전투에 특화된 종족이다.");
    }

    @Test
    @DisplayName("작품·분류·정규화 대상명이 같은 확정본은 DB 유일 제약으로 막는다")
    void uniqueConstraintRejectsDuplicateSubject() {
        worldSettingRepository.saveAndFlush(WorldSetting.create(
                work,
                WorldSettingCategory.LOCATION,
                "North",
                "기후",
                "한랭"
        ));

        assertThatThrownBy(() -> worldSettingRepository.saveAndFlush(WorldSetting.create(
                work,
                WorldSettingCategory.LOCATION,
                "  north  ",
                "지형",
                "설원"
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }
}
