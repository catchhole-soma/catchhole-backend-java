package org.monitoring.catchholebackend.domain.character.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SettingCandidateRepositoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WorkRepository workRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private SettingCandidateRepository settingCandidateRepository;

    private Work work;
    private Episode episode;
    private AnalysisJob analysisJob;

    @BeforeEach
    void setUp() {
        Member member = memberRepository.save(Member.register(
                uniqueEmail("writer"),
                "encoded-password",
                uniquePhoneNumber(),
                "작가"
        ));
        work = workRepository.save(Work.create(member, "은빛 검사", "판타지", "검사 성장물"));
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
                null,
                episode,
                AnalysisJobType.SETTING_EXTRACTION
        ));
    }

    @Test
    void saveAndFindSettingCandidateWithJsonPayloads() {
        UUID sourceChunkId = UUID.randomUUID();
        JsonNode valueJson = objectMapper.createObjectNode()
                .put("value", 17)
                .put("unit", "years")
                .put("status", "explicit")
                .put("narrative_context", "PRESENT")
                .put("applies_to_current_timeline", true);
        JsonNode evidenceSpans = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                        .put("source_episode_id", episode.getId().toString())
                        .put("source_chunk_id", sourceChunkId.toString())
                        .put("paragraph_index", 1)
                        .put("start_offset", 0)
                        .put("end_offset", 31)
                        .put("offset_base", "CHUNK_TEXT")
                        .put("quote", "열일곱 살의 아리아는 북부 기사단의 훈련장을 빠져나왔다."));
        JsonNode rawAiResultJson = objectMapper.createObjectNode()
                .put("raw_value", "17")
                .put("raw_attribute_name", "age")
                .put("raw_confidence_reason", "원문에 나이가 직접 명시됨");

        SettingCandidate candidate = settingCandidateRepository.save(SettingCandidate.create(
                work,
                episode,
                sourceChunkId,
                analysisJob,
                SettingEntityType.CHARACTER,
                "아리아",
                "age",
                "17",
                SettingValueType.NUMBER,
                valueJson,
                evidenceSpans,
                new BigDecimal("0.9500"),
                rawAiResultJson
        ));

        SettingCandidate found = settingCandidateRepository.findById(candidate.getId()).orElseThrow();

        assertThat(found.getWork().getId()).isEqualTo(work.getId());
        assertThat(found.getEpisode().getId()).isEqualTo(episode.getId());
        assertThat(found.getSourceChunkId()).isEqualTo(sourceChunkId);
        assertThat(found.getAnalysisJob().getId()).isEqualTo(analysisJob.getId());
        assertThat(found.getEntityType()).isEqualTo(SettingEntityType.CHARACTER);
        assertThat(found.getEntityName()).isEqualTo("아리아");
        assertThat(found.getAttributeName()).isEqualTo("age");
        assertThat(found.getAttributeValue()).isEqualTo("17");
        assertThat(found.getValueType()).isEqualTo(SettingValueType.NUMBER);
        assertThat(found.getValueJson()).isEqualTo(valueJson);
        assertThat(found.getEvidenceSpans()).isEqualTo(evidenceSpans);
        assertThat(found.getConfidence()).isEqualByComparingTo("0.9500");
        assertThat(found.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
        assertThat(found.getRawAiResultJson()).isEqualTo(rawAiResultJson);
    }

    @Test
    void findAllByWorkIdAndEntityNameAndReviewStatus() {
        settingCandidateRepository.save(candidate("아리아", "age"));
        settingCandidateRepository.save(candidate("세이라", "level"));

        List<SettingCandidate> candidates =
                settingCandidateRepository.findAllByWorkIdAndEntityNameAndReviewStatusOrderByCreatedAtDesc(
                        work.getId(),
                        "아리아",
                        SettingCandidateReviewStatus.PENDING_REVIEW
                );

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().getEntityName()).isEqualTo("아리아");
        assertThat(candidates.getFirst().getAttributeName()).isEqualTo("age");
    }

    @Test
    void confirmAndDismissCandidatesAreFilteredByReviewStatus() {
        SettingCandidate confirmed = candidate("아리아", "age");
        confirmed.confirm();
        settingCandidateRepository.save(confirmed);

        SettingCandidate dismissed = candidate("아리아", "level");
        dismissed.dismiss();
        settingCandidateRepository.save(dismissed);

        settingCandidateRepository.save(candidate("아리아", "items"));

        List<SettingCandidate> confirmedCandidates =
                settingCandidateRepository.findAllByWorkIdAndEntityNameAndReviewStatusOrderByCreatedAtDesc(
                        work.getId(),
                        "아리아",
                        SettingCandidateReviewStatus.CONFIRMED
                );
        List<SettingCandidate> dismissedCandidates =
                settingCandidateRepository.findAllByWorkIdAndEntityNameAndReviewStatusOrderByCreatedAtDesc(
                        work.getId(),
                        "아리아",
                        SettingCandidateReviewStatus.DISMISSED
                );
        List<SettingCandidate> pendingCandidates =
                settingCandidateRepository.findAllByWorkIdAndEntityNameAndReviewStatusOrderByCreatedAtDesc(
                        work.getId(),
                        "아리아",
                        SettingCandidateReviewStatus.PENDING_REVIEW
                );

        assertThat(confirmedCandidates).hasSize(1);
        assertThat(confirmedCandidates.getFirst().getAttributeName()).isEqualTo("age");
        assertThat(confirmedCandidates.getFirst().getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
        assertThat(dismissedCandidates).hasSize(1);
        assertThat(dismissedCandidates.getFirst().getAttributeName()).isEqualTo("level");
        assertThat(dismissedCandidates.getFirst().getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.DISMISSED);
        assertThat(pendingCandidates).hasSize(1);
        assertThat(pendingCandidates.getFirst().getAttributeName()).isEqualTo("items");
        assertThat(pendingCandidates.getFirst().getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
    }

    @Test
    void saveCandidateWithoutEpisodeAnalysisJobAndSourceChunk() {
        SettingCandidate candidate = settingCandidateRepository.save(SettingCandidate.create(
                work,
                null,
                null,
                null,
                SettingEntityType.CHARACTER,
                "아리아",
                "items",
                "푸른 마나석 x2",
                SettingValueType.JSON,
                objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("name", "푸른 마나석")
                                .put("quantity", 2)),
                null,
                null,
                objectMapper.createObjectNode().put("source", "manual-ai-payload")
        ));

        SettingCandidate found = settingCandidateRepository.findById(candidate.getId()).orElseThrow();

        assertThat(found.getEpisode()).isNull();
        assertThat(found.getAnalysisJob()).isNull();
        assertThat(found.getSourceChunkId()).isNull();
        assertThat(found.getValueType()).isEqualTo(SettingValueType.JSON);
        assertThat(found.getRawAiResultJson().get("source").asText()).isEqualTo("manual-ai-payload");
    }

    private SettingCandidate candidate(String entityName, String attributeName) {
        return SettingCandidate.create(
                work,
                episode,
                null,
                analysisJob,
                SettingEntityType.CHARACTER,
                entityName,
                attributeName,
                "value",
                SettingValueType.STRING,
                objectMapper.createObjectNode().put("value", "value"),
                objectMapper.createArrayNode(),
                new BigDecimal("0.8000"),
                objectMapper.createObjectNode().put("raw_value", "value")
        );
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private String uniquePhoneNumber() {
        return "010" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
