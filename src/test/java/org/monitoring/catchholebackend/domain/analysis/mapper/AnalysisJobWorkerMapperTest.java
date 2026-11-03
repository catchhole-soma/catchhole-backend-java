package org.monitoring.catchholebackend.domain.analysis.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterFactEvidenceMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterFactSourceResolver;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("분석 작업 Worker Mapper 단위 테스트")
class AnalysisJobWorkerMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnalysisJobWorkerMapper mapper = new AnalysisJobWorkerMapper(
            new CharacterSnapshotAccessor(), new AnalysisRunContextMapper(new org.monitoring.catchholebackend.domain.character.mapper.CharacterFactEvidenceMapper(new org.monitoring.catchholebackend.domain.character.processor.CharacterFactSourceResolver(), org.mockito.Mockito.mock(org.monitoring.catchholebackend.domain.analysis.processor.GeneralEvidenceProjection.class))),
            new CharacterFactEvidenceMapper(new CharacterFactSourceResolver(), org.mockito.Mockito.mock(org.monitoring.catchholebackend.domain.analysis.processor.GeneralEvidenceProjection.class))
    );

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @DisplayName("누적 claim의 기존·임시 인물은 검증된 시점 인용만 전달하고 내부 기준값은 변경하지 않는다")
    void orderedClaimSeparatesModelEvidenceFromStoredCurrentTime(boolean verified) {
        Work work = Work.create(Member.register("time@example.invalid", "password123", "01012345678", "작가"),
                "시점", WorkGenre.FANTASY, "");
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        AnalysisJob job = AnalysisJob.create(work, null, null, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(job, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(job, "analysisMode", org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode.ORDERED_PROVISIONAL);
        ReflectionTestUtils.setField(job, "analysisRunId", UUID.randomUUID());
        ReflectionTestUtils.setField(job, "runGeneration", 1L);
        ReflectionTestUtils.setField(job, "inputStateHash", "a".repeat(64));
        ReflectionTestUtils.setField(job, "sourceContentHash", "b".repeat(64));
        var input = objectMapper.createObjectNode();
        var owners = input.putObject("characters");
        var current = objectMapper.createObjectNode().put("description", "장마 첫날")
                .put("sourceCandidateId", UUID.randomUUID().toString());
        current.putArray("evidenceSpans").addObject().put("quote", "시점 원문 인용")
                .put("startOffset", 0).put("endOffset", 8).put("sourceChunkId", UUID.randomUUID().toString());
        var reference = current.deepCopy();
        reference.remove("sourceCandidateId");
        var origin = current.putObject("supportingSource");
        if (verified) origin.putObject("narrativeTime").set("referenceTime", reference);
        else origin.putNull("narrativeTime");
        var known = owners.putObject("known").put("actualCharacterId", UUID.randomUUID().toString())
                .putNull("provisionalSubjectKey").put("name", "기존 인물");
        known.putObject("slots");
        known.set("currentNarrativeTime", current.deepCopy());
        var provisional = owners.putObject("provisional").putNull("actualCharacterId")
                .put("provisionalSubjectKey", "provisional-character:" + UUID.randomUUID()).put("name", "임시 인물");
        provisional.putObject("slots");
        provisional.set("currentNarrativeTime", current.deepCopy());
        var before = input.deepCopy();

        var payload = mapper.toResponse(job, null, List.of(), List.of(), List.of(), input);

        for (Object time : List.of(payload.knownCharacters().getFirst().currentNarrativeTime(),
                payload.provisionalCharacters().getFirst().currentNarrativeTime())) {
            var modelTime = objectMapper.valueToTree(time);
            assertThat(modelTime.path("description").asText()).isEqualTo("장마 첫날");
            assertThat(modelTime.path("evidenceSpans")).hasSize(verified ? 1 : 0);
            assertThat(modelTime.has("supportingSource")).isFalse();
            if (!verified) assertThat(modelTime.toString()).doesNotContain("시점 원문 인용");
        }
        assertThat(input).isEqualTo(before);
    }

    @Test
    @DisplayName("활성 STATUS를 빠짐없이 정렬하고 legacy 표시값은 provenance로 보완한다")
    void mapsEveryActiveStatusWithLegacyProvenanceFallback() {
        Member member = Member.register("worker@example.com", "password123", "01012345678", "작가");
        Work work = Work.create(member, "비교 작품", WorkGenre.FANTASY, "테스트");
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());

        var statuses = objectMapper.createObjectNode();
        statuses.set("status.고아", objectMapper.createObjectNode().put("active", true));
        statuses.set("status.종료됨", objectMapper.createObjectNode().put("active", false));
        for (int index = 31; index >= 0; index--) {
            statuses.set(
                    "status.%02d".formatted(index),
                    objectMapper.createObjectNode().put("value", "상태 %02d".formatted(index))
            );
        }
        statuses.set(
                "status.부상",
                objectMapper.createObjectNode().put("description", "legacy raw 값")
        );
        WorkCharacter character = WorkCharacter.create(
                work,
                "아리아",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                statuses,
                null
        );
        ReflectionTestUtils.setField(character, "id", UUID.randomUUID());

        CharacterFact sourceFact = CharacterFact.createManual(
                character,
                CharacterFactType.STATUS,
                "status.부상",
                "오른발을 심하게 다침",
                objectMapper.createObjectNode().put("description", "legacy raw 값")
        );
        ReflectionTestUtils.setField(sourceFact, "id", UUID.randomUUID());
        CharacterSnapshotSource source = CharacterSnapshotSource.create(
                character,
                CharacterFactType.STATUS,
                "status.부상",
                sourceFact,
                0
        );

        AnalysisJob job = AnalysisJob.create(work, null, null, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(job, "id", UUID.randomUUID());
        var payload = mapper.toResponse(job, null, List.of(), List.of(character), List.of(source));

        var activeStatuses = payload.knownCharacters().getFirst().activeStatuses();
        assertThat(activeStatuses).hasSize(34);
        assertThat(activeStatuses)
                .extracting(status -> status.factKey())
                .isSorted();
        assertThat(activeStatuses)
                .filteredOn(status -> status.factKey().equals("status.부상"))
                .extracting(status -> status.factValue())
                .containsExactly("오른발을 심하게 다침");
        assertThat(activeStatuses)
                .filteredOn(status -> status.factKey().equals("status.고아"))
                .extracting(status -> status.factValue())
                .containsExactly((String) null);
        assertThat(activeStatuses)
                .extracting(status -> status.factKey())
                .doesNotContain("status.종료됨");
    }
}
