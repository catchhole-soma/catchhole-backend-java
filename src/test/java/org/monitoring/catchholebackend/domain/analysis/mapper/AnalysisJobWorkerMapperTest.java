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
            new CharacterSnapshotAccessor()
    );

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
