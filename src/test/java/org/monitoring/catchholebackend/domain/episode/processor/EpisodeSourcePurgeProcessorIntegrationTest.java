package org.monitoring.catchholebackend.domain.episode.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeSourcePurgeRequestRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonBatch;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecision;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecisionSource;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingComparisonBatchRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingComparisonDecisionRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingComparisonDecisionSourceRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingConsolidationStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType;
import org.monitoring.catchholebackend.global.storage.ObjectStorage;
import org.monitoring.catchholebackend.global.storage.ObjectStoragePurgeResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("회차 원문 파기 비교 묶음 통합 테스트")
class EpisodeSourcePurgeProcessorIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private EpisodeSourcePurgeProcessor processor;

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
    private WorldSettingCandidateRepository candidateRepository;

    @Autowired
    private WorldSettingComparisonBatchRepository comparisonBatchRepository;

    @Autowired
    private WorldSettingComparisonDecisionRepository comparisonDecisionRepository;

    @Autowired
    private WorldSettingComparisonDecisionSourceRepository comparisonSourceRepository;

    @Autowired
    private EpisodeSourcePurgeRequestRepository purgeRequestRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ObjectStorage objectStorage;

    private Member member;
    private Work work;
    private Episode episode;
    private AnalysisJob analysisJob;

    @BeforeEach
    void setUp() {
        clearData();
        jdbcTemplate.execute("""
                create table if not exists episode_chunks (
                    id uuid primary key,
                    episode_id uuid not null,
                    chunk_index integer not null,
                    chunk_text clob not null
                )
                """);
        when(objectStorage.purgePrefixesExcluding(any(), any()))
                .thenReturn(new ObjectStoragePurgeResult(1, 1, 0));

        member = memberRepository.save(Member.register(
                "world-purge@example.com",
                "encoded-password",
                "01011112222",
                "원문 파기 작가"
        ));
        work = workRepository.save(Work.create(
                member,
                "원문 파기 작품",
                WorkGenre.FANTASY,
                "비교 산출물 파기 테스트"
        ));
        UploadBatch uploadBatch = uploadBatchRepository.save(UploadBatch.create(
                work,
                member,
                UploadType.SINGLE_EPISODE,
                UploadSourceType.FILE
        ));
        episode = episodeRepository.save(Episode.create(
                work,
                null,
                3,
                "3화",
                "works/old-content.txt",
                "v1",
                "hash-3",
                100
        ));
        analysisJob = analysisJobRepository.save(AnalysisJob.create(
                work,
                uploadBatch,
                episode,
                AnalysisJobType.SETTING_EXTRACTION
        ));
    }

    @AfterEach
    void tearDown() {
        clearData();
    }

    @Test
    @DisplayName("미확정 후보는 삭제하고 보존 후보와 권위 비교 묶음에서는 원문 근거만 제거한다")
    void purgeDeletesPendingCandidateAndScrubsRetainedComparisonArtifacts() {
        JsonNode evidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("quote", "고블린은 녹슨 검을 들고 있었다.")
                .put("startOffset", 10)
                .put("endOffset", 28));
        WorldSettingCandidate pending = WorldSettingCandidate.create(
                work,
                episode,
                analysisJob,
                WorldSettingCategory.MONSTER,
                "고블린 떼",
                "무기",
                "녹슨 검",
                evidence,
                new BigDecimal("0.9100"),
                objectMapper.createObjectNode().put("rawQuote", "삭제할 원문")
        );
        WorldSettingCandidate retained = WorldSettingCandidate.create(
                work,
                episode,
                analysisJob,
                WorldSettingCategory.MONSTER,
                "고블린 무리",
                "무기",
                "낡은 검",
                evidence,
                new BigDecimal("0.9200"),
                objectMapper.createObjectNode().put("rawQuote", "보존 후보의 원문")
        );
        candidateRepository.saveAllAndFlush(List.of(pending, retained));

        JsonNode resolvedTargetIds = objectMapper.createArrayNode();
        pending.resolveSubject(
                WorldSettingSubjectResolutionType.NEW,
                "NEW:고블린",
                "고블린",
                resolvedTargetIds
        );
        retained.resolveSubject(
                WorldSettingSubjectResolutionType.NEW,
                "NEW:고블린",
                "고블린",
                resolvedTargetIds
        );
        WorldSettingComparisonBatch batch = comparisonBatchRepository.saveAndFlush(
                WorldSettingComparisonBatch.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.MONSTER,
                        null,
                        WorldSettingSubjectResolutionType.NEW,
                        "NEW:고블린",
                        "고블린",
                        resolvedTargetIds,
                        2
                )
        );
        pending.startComparison(batch, "C1");
        retained.startComparison(batch, "C2");
        candidateRepository.saveAllAndFlush(List.of(pending, retained));

        WorldSettingComparisonDecision pendingDecision = comparisonDecisionRepository.saveAndFlush(
                createDecision(batch, "D1", "C1의 원문 인용을 포함한 비교 사유")
        );
        WorldSettingComparisonDecision retainedDecision = comparisonDecisionRepository.saveAndFlush(
                createDecision(batch, "D2", "C2의 원문 인용을 포함한 비교 사유")
        );
        pending.completeComparison(pendingDecision, LocalDateTime.now());
        retained.completeComparison(retainedDecision, LocalDateTime.now());
        retained.dismiss("검토 완료", member);
        candidateRepository.saveAllAndFlush(List.of(pending, retained));
        comparisonSourceRepository.saveAllAndFlush(List.of(
                WorldSettingComparisonDecisionSource.create(
                        batch,
                        pendingDecision,
                        pending,
                        "C1",
                        0
                ),
                WorldSettingComparisonDecisionSource.create(
                        batch,
                        retainedDecision,
                        retained,
                        "C2",
                        0
                )
        ));
        batch.recordContext(objectMapper.createObjectNode().put("sourceQuote", "문맥 원문"));
        batch.complete(
                "a".repeat(64),
                objectMapper.createObjectNode().put("rawCompletion", "모델 원본 응답")
        );
        comparisonBatchRepository.saveAndFlush(batch);

        UUID requestId = processor.requestDeletionPurge(episode, null);
        processor.processRequest(requestId);

        assertThat(candidateRepository.findById(pending.getId())).isEmpty();
        assertThat(candidateRepository.findById(retained.getId()))
                .get()
                .satisfies(candidate -> {
                    assertThat(candidate.getEvidenceSpans()).isEmpty();
                    assertThat(candidate.getRawExtractionJson()).isNull();
                    assertThat(candidate.getComparisonReason()).isNull();
                    assertThat(candidate.getRawComparisonJson()).isNull();
                    assertThat(candidate.getFinalSettingName()).isEqualTo("무기");
                    assertThat(candidate.getFinalValue()).isEqualTo("낡은 검");
                });
        assertThat(comparisonDecisionRepository.findAllById(
                List.of(pendingDecision.getId(), retainedDecision.getId())
        )).allSatisfy(decision -> {
            assertThat(decision.getComparisonReason()).isEqualTo("SOURCE_EVIDENCE_PURGED");
            assertThat(decision.getRawComparisonJson()).isNull();
            assertThat(decision.getProposedValue()).isEqualTo("낡은 검");
        });
        assertThat(comparisonBatchRepository.findById(batch.getId()))
                .get()
                .satisfies(purgedBatch -> {
                    assertThat(purgedBatch.getContextSnapshotJson()).isNull();
                    assertThat(purgedBatch.getRawCompletionJson()).isNull();
                    assertThat(purgedBatch.getCanonicalSubjectName()).isEqualTo("고블린");
                });
        assertThat(comparisonSourceRepository
                .findAllByComparisonBatchIdOrderByCandidateRefAscIdAsc(batch.getId()))
                .singleElement()
                .satisfies(source -> assertThat(source.getCandidate().getId())
                        .isEqualTo(retained.getId()));
        assertThat(purgeRequestRepository.count()).isZero();
    }

    private WorldSettingComparisonDecision createDecision(
            WorldSettingComparisonBatch batch,
            String decisionRef,
            String comparisonReason
    ) {
        return WorldSettingComparisonDecision.create(
                batch,
                decisionRef,
                "고블린",
                null,
                null,
                null,
                WorldSettingConsolidationStatus.SINGLE,
                WorldSettingSuggestedOperation.ADD,
                null,
                null,
                "무기",
                null,
                "낡은 검",
                comparisonReason,
                objectMapper.createObjectNode().put("sourceQuote", "원문 응답")
        );
    }

    private void clearData() {
        purgeRequestRepository.deleteAll();
        comparisonSourceRepository.deleteAll();
        candidateRepository.deleteAll();
        comparisonDecisionRepository.deleteAll();
        comparisonBatchRepository.deleteAll();
        analysisJobRepository.deleteAll();
        episodeRepository.deleteAll();
        uploadBatchRepository.deleteAll();
        workRepository.deleteAll();
        memberRepository.deleteAll();
    }
}
