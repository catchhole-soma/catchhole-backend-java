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
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;

/**
 * #387 대상별 읽기가 기존 전체 상태 읽기(getInputState·getProjectedState·PreparedState.projectedState)에서 같은 경로를
 * 읽은 값과 같은지 확인한다. 기대값은 운영의 AnalysisStateSlices를 거치지 않고 여기서 직접 만든다.
 */
public final class AnalysisRunStateEquivalence {
    private static final List<String> DOMAINS = List.of("characters", "worldSettings");
    private static final List<String> IDENTITY_FIELDS = List.of("actualCharacterId", "provisionalSubjectKey", "name", "aliases");

    private AnalysisRunStateEquivalence() {
    }

    /** 비교한 실제 대상 수(없는 대상 제외)를 돌려준다. 호출자는 의미 있는 fixture였는지 이 값으로 확인한다. */
    public static int assertMatchesWholeState(AnalysisRunStateService states, AnalysisJob job) {
        JsonNode input = states.getInputState(job);
        JsonNode projected = states.getProjectedState(job);
        var prepared = states.prepareState(job);
        assertThat(prepared.projectedState()).isEqualTo(projected);
        int compared = 0;
        for (String domain : DOMAINS) {
            Set<String> refs = new LinkedHashSet<>();
            input.path(domain).fieldNames().forEachRemaining(refs::add);
            projected.path(domain).fieldNames().forEachRemaining(refs::add);
            compared += refs.size();
            String absent = ("characters".equals(domain) ? "character:" : "world:") + UUID.randomUUID();
            refs.add(absent);
            for (String ref : refs) {
                assertThat(states.getInputTarget(job, domain, ref)).as("input %s/%s", domain, ref)
                        .isEqualTo(input.path(domain).path(ref));
                assertThat(states.getProjectedTarget(job, domain, ref)).as("projected %s/%s", domain, ref)
                        .isEqualTo(projected.path(domain).path(ref));
                assertThat(prepared.projectedTarget(domain, ref)).as("prepared %s/%s", domain, ref)
                        .isEqualTo(projected.path(domain).path(ref));
            }
            ObjectNode expected = JsonNodeFactory.instance.objectNode();
            ObjectNode targets = expected.putObject(domain);
            refs.stream().filter(ref -> projected.path(domain).has(ref)).forEach(ref -> targets.set(ref, projected.path(domain).get(ref)));
            if (projected.has("references")) expected.set("references", projected.get("references"));
            assertThat(states.getProjectedSubset(job, domain, refs)).isEqualTo(expected);
            assertThat(prepared.projectedSubset(domain, refs)).isEqualTo(expected);
            ObjectNode referencesOnly = JsonNodeFactory.instance.objectNode();
            referencesOnly.putObject(domain);
            if (projected.has("references")) referencesOnly.set("references", projected.get("references"));
            assertThat(prepared.projectedSubset(domain, List.of())).isEqualTo(referencesOnly);
        }
        assertThat(states.getProjectedReferences(job)).isEqualTo(projected.path("references"));
        assertThat(prepared.projectedReferences()).isEqualTo(projected.path("references"));
        for (WorldSettingCategory category : WorldSettingCategory.values()) {
            List<JsonNode> expected = new ArrayList<>();
            projected.path("worldSettings").forEach(target -> {
                if (category.name().equals(target.path("category").asText())) expected.add(target);
            });
            assertThat(states.getProjectedWorldSubjects(job, category.name())).as("world subjects %s", category).isEqualTo(expected);
        }
        ObjectNode identities = JsonNodeFactory.instance.objectNode();
        input.path("characters").fieldNames().forEachRemaining(ref -> {
            ObjectNode identity = identities.putObject(ref);
            IDENTITY_FIELDS.forEach(field -> {
                if (input.path("characters").path(ref).has(field)) identity.set(field, input.path("characters").path(ref).get(field));
            });
        });
        assertThat(states.getInputCharacterIdentities(job)).isEqualTo(identities);
        assertThat(states.getInputCurrentSubjects(job)).isEqualTo(
                org.monitoring.catchholebackend.domain.analysis.processor.AnalysisAutomaticInputFormat.isLive(job) ? currentValues(input) : input);
        assertDetachedCopies(states, job, input, projected);
        return compared;
    }

    /**
     * #387 format 2의 claim 현재값 읽기는 이력·근거 사본을 만들지 않는다(AnalysisLiveSubjectSource.toCurrentValues와 같은 규칙,
     * AnalysisLiveStateReaderIntegrationTest가 그 규칙과 reader의 동등성을 확인한다). format 1에서는 claim 입력 전체를 읽는다.
     */
    private static JsonNode currentValues(JsonNode input) {
        ObjectNode result = input.deepCopy();
        result.path("characters").forEach(character -> {
            ((ObjectNode) character).remove("historyFacts");
            character.path("slots").forEach(slot -> ((ObjectNode) slot).remove("supportingSources"));
        });
        result.path("worldSettings").forEach(world -> ((ObjectNode) world).remove(List.of("provenance", "provenanceByPath")));
        return result;
    }

    /** 반환 값을 바꿔도 다음 읽기와 저장 입력이 바뀌지 않는다. */
    private static void assertDetachedCopies(AnalysisRunStateService states, AnalysisJob job, JsonNode input, JsonNode projected) {
        for (String domain : DOMAINS) {
            var refs = projected.path(domain).fieldNames();
            if (!refs.hasNext()) continue;
            String ref = refs.next();
            if (states.getProjectedTarget(job, domain, ref) instanceof ObjectNode target) target.put("detachedCopyProbe", true);
            if (states.getInputTarget(job, domain, ref) instanceof ObjectNode target) target.put("detachedCopyProbe", true);
            ((ObjectNode) states.getProjectedSubset(job, domain, List.of(ref)).path(domain)).put("detachedCopyProbe", true);
        }
        states.getInputCharacterIdentities(job).put("detachedCopyProbe", true);
        assertThat(states.getInputState(job)).isEqualTo(input);
        assertThat(states.getProjectedState(job)).isEqualTo(projected);
    }
}
