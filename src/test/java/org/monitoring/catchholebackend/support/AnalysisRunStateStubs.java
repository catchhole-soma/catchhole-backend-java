package org.monitoring.catchholebackend.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collection;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateSlices;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;

/**
 * #387 AnalysisRunStateService mock의 대상별 읽기를 같은 mock의 전체 상태 stub(getInputState·getProjectedState)에서
 * 잘라 돌려주게 한다. 운영 구현과 같은 AnalysisStateSlices 규칙을 쓰므로 기존 전체 상태 fixture를 그대로 유지할 수 있다.
 */
public final class AnalysisRunStateStubs {
    private AnalysisRunStateStubs() {
    }

    public static void sliceWholeState(AnalysisRunStateService states) {
        when(states.getInputTarget(any(), anyString(), anyString())).thenAnswer(call ->
                AnalysisStateSlices.target(states.getInputState(job(call.getArgument(0))), call.getArgument(1), call.getArgument(2)).deepCopy());
        when(states.getProjectedTarget(any(), anyString(), anyString())).thenAnswer(call ->
                AnalysisStateSlices.target(states.getProjectedState(job(call.getArgument(0))), call.getArgument(1), call.getArgument(2)).deepCopy());
        when(states.getProjectedReferences(any())).thenAnswer(call ->
                AnalysisStateSlices.references(states.getProjectedState(job(call.getArgument(0)))).deepCopy());
        when(states.getProjectedSubset(any(), anyString(), any())).thenAnswer(call ->
                AnalysisStateSlices.subset(states.getProjectedState(job(call.getArgument(0))), call.getArgument(1),
                        call.<Collection<String>>getArgument(2)).deepCopy());
        when(states.getProjectedWorldSubjects(any(), anyString())).thenAnswer(call ->
                AnalysisStateSlices.worldSubjects(states.getProjectedState(job(call.getArgument(0))), call.getArgument(1)).stream()
                        .<JsonNode>map(JsonNode::deepCopy).toList());
        when(states.getInputCharacterIdentities(any())).thenAnswer(call ->
                AnalysisStateSlices.characterIdentities(states.getInputState(job(call.getArgument(0)))).deepCopy());
        when(states.getInputCurrentSubjects(any())).thenAnswer(call -> states.getInputState(job(call.getArgument(0))));
    }

    private static AnalysisJob job(Object argument) {
        return (AnalysisJob) argument;
    }
}
