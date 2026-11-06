package org.monitoring.catchholebackend.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisLiveStateReader;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;

/**
 * #387 실제 claim을 거친 자동 누적 Job(format 1)에서, live 읽기가 claim 때 고정한 입력과 같은 값을 주는지 확인한다.
 * 실행 중에는 GH201이 확정 설정 쓰기를 막으므로 지금 DB를 읽은 값이 claim 입력과 같아야 한다. 값은 hash와 같은 canonical JSON으로 비교한다.
 */
public final class AnalysisLiveStateEquivalence {
    private static final List<String> DOMAINS = List.of("characters", "worldSettings");

    private AnalysisLiveStateEquivalence() {
    }

    /** 비교한 실제 대상 수를 돌려준다. 호출자는 의미 있는 fixture였는지 이 값으로 확인한다. */
    public static int assertLiveMatchesClaimInput(AnalysisLiveStateReader reader, AnalysisStateJournal journal,
            AnalysisRunStateService states, AnalysisJob job) {
        assertThat(job.isAutomaticReview()).as("자동 누적 Job만 claim 때 입력을 고정한다").isTrue();
        JsonNode input = states.getInputState(job);
        ObjectNode current = reader.currentSubjects(job.getWork());
        int compared = 0;
        for (String domain : DOMAINS) {
            List<String> refs = new ArrayList<>();
            input.path(domain).fieldNames().forEachRemaining(refs::add);
            compared += refs.size();
            assertThat(journal.canonicalJson(reader.subjects(job.getWork(), domain, refs))).as("subjects %s", domain)
                    .isEqualTo(journal.canonicalJson(input.path(domain)));
            for (String ref : refs) {
                assertThat(journal.canonicalJson(reader.subjects(job.getWork(), domain, List.of(ref)).path(ref))).as("subject %s/%s", domain, ref)
                        .isEqualTo(journal.canonicalJson(input.path(domain).path(ref)));
            }
            ObjectNode expected = JsonNodeFactory.instance.objectNode();
            input.path(domain).properties().forEach(entry -> expected.set(entry.getKey(), reader.toCurrentValues(domain, (ObjectNode) entry.getValue())));
            assertThat(journal.canonicalJson(current.path(domain))).as("current %s", domain).isEqualTo(journal.canonicalJson(expected));
        }
        Set<String> categories = new LinkedHashSet<>();
        input.path("worldSettings").forEach(subject -> categories.add(subject.path("category").asText()));
        for (String category : categories) {
            List<String> expected = new ArrayList<>();
            input.path("worldSettings").properties().forEach(entry -> {
                if (category.equals(entry.getValue().path("category").asText())) expected.add(entry.getKey());
            });
            assertThat(reader.worldSubjectRefs(job.getWork().getId(), category)).as("category %s", category)
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
        assertThat(reader.subjects(job.getWork(), "characters", List.of("character:" + UUID.randomUUID())).isEmpty()).isTrue();
        return compared;
    }
}
