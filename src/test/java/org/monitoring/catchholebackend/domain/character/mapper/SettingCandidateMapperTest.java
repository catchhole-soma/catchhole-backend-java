package org.monitoring.catchholebackend.domain.character.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateResponse;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("설정 후보 Mapper 단위 테스트")
class SettingCandidateMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SettingCandidateMapper mapper = new SettingCandidateMapper();

    @Test
    @DisplayName("설정 후보 Entity를 응답 DTO로 변환한다")
    void toResponseMapsSettingCandidate() {
        UUID workId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        UUID analysisJobId = UUID.randomUUID();
        UUID sourceChunkId = UUID.randomUUID();
        Work work = work(workId);
        Episode episode = episode(work, episodeId);
        AnalysisJob analysisJob = analysisJob(work, episode, analysisJobId);
        SettingCandidate candidate = SettingCandidate.create(
                work,
                episode,
                sourceChunkId,
                analysisJob,
                SettingEntityType.CHARACTER,
                "아리아",
                "age",
                "17",
                SettingValueType.NUMBER,
                objectMapper.createObjectNode().put("value", 17),
                objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("paragraph_index", 1)
                                .put("quote", "열일곱 살의 아리아")),
                new BigDecimal("0.8000"),
                objectMapper.createObjectNode().put("raw_value", "17")
        );
        UUID candidateId = UUID.randomUUID();
        ReflectionTestUtils.setField(candidate, "id", candidateId);

        SettingCandidateResponse response = mapper.toResponse(candidate);

        assertThat(response.id()).isEqualTo(candidateId);
        assertThat(response.workId()).isEqualTo(workId);
        assertThat(response.episodeId()).isEqualTo(episodeId);
        assertThat(response.sourceChunkId()).isEqualTo(sourceChunkId);
        assertThat(response.analysisJobId()).isEqualTo(analysisJobId);
        assertThat(response.entityName()).isEqualTo("아리아");
        assertThat(response.attributeName()).isEqualTo("age");
        assertThat(response.attributeValue()).isEqualTo("17");
        assertThat(response.valueJson()).isInstanceOf(Map.class);
        assertThat(response.evidenceSpans()).isInstanceOf(List.class);
        assertThat(response.rawAiResultJson()).isInstanceOf(Map.class);
        assertThat(response.valueJson()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("value", 17);
    }

    @Test
    @DisplayName("회차와 분석 작업이 없는 설정 후보도 응답 DTO로 변환한다")
    void toResponseHandlesNullableAssociations() {
        Work work = work(UUID.randomUUID());
        SettingCandidate candidate = SettingCandidate.create(
                work,
                null,
                null,
                null,
                SettingEntityType.CHARACTER,
                "아리아",
                "items",
                "푸른 마나석",
                SettingValueType.JSON,
                null,
                null,
                null,
                null
        );

        SettingCandidateResponse response = mapper.toResponse(candidate);

        assertThat(response.workId()).isEqualTo(work.getId());
        assertThat(response.episodeId()).isNull();
        assertThat(response.sourceChunkId()).isNull();
        assertThat(response.analysisJobId()).isNull();
        assertThat(response.valueJson()).isNull();
        assertThat(response.evidenceSpans()).isNull();
        assertThat(response.rawAiResultJson()).isNull();
    }

    private Work work(UUID id) {
        Member member = Member.register("writer@example.com", "encoded-password", "01012345678", "작가");
        Work work = Work.create(member, "내 작품", "판타지", "내 설명");
        ReflectionTestUtils.setField(work, "id", id);
        return work;
    }

    private Episode episode(Work work, UUID id) {
        Episode episode = Episode.create(work, null, 1, "1화", "s3-key", "version-1", "hash-1", 100);
        ReflectionTestUtils.setField(episode, "id", id);
        return episode;
    }

    private AnalysisJob analysisJob(Work work, Episode episode, UUID id) {
        AnalysisJob analysisJob = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(analysisJob, "id", id);
        return analysisJob;
    }
}
