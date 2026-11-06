package org.monitoring.catchholebackend.domain.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateSlices;

public interface AnalysisRunStateService {
    void initializeRun(List<AnalysisJob> jobs);

    /** 입력 전체. 분석 도메인의 읽기는 아래 대상별 읽기를 쓴다(#387). 수동 replay·검증·테스트용으로 남긴다. */
    JsonNode getInputState(AnalysisJob job);

    /** 이 Job의 변경까지 반영한 전체 상태. 분석 도메인의 읽기는 아래 대상별 읽기를 쓴다(#387). */
    JsonNode getProjectedState(AnalysisJob job);

    /*
     * #387 대상별 읽기. 분석 도메인(캐릭터·세계관·관계·claim)은 실행 상태를 이 메서드로만 읽는다. 지금은 모두 저장된 전체
     * 입력에서 AnalysisStateSlices로 잘라내며, 검증과 반환 값은 getInputState·getProjectedState에서 같은 경로를 읽은 것과
     * 같다. 반환 값은 호출자 소유 복사본이고 domain은 characters 또는 worldSettings다. 대상이 없으면 MissingNode다.
     */

    /** claim 입력(자동: claim 때 고정한 입력, 수동: S0와 앞 회차 변경)의 한 대상. getInputState와 같은 실행 검증을 한다. */
    JsonNode getInputTarget(AnalysisJob job, String domain, String ref);

    /** 이 Job의 변경까지 반영한 한 대상. getProjectedState와 같은 입력·journal 검증을 한다. */
    JsonNode getProjectedTarget(AnalysisJob job, String domain, String ref);

    /** 이 Job의 변경까지 반영한 references. getProjectedState와 같은 검증을 한다. */
    JsonNode getProjectedReferences(AnalysisJob job);

    /** 이 Job의 변경까지 반영한 domain의 refs 대상과 references만 담은 부분 상태. 없는 ref는 담지 않는다. */
    ObjectNode getProjectedSubset(AnalysisJob job, String domain, Collection<String> refs);

    /** 이 Job의 변경까지 반영한, 분류(WorldSettingCategory 이름)가 같은 세계관 대상. 정렬은 호출자가 정한다. */
    List<JsonNode> getProjectedWorldSubjects(AnalysisJob job, String category);

    /** claim 입력의 인물 식별 색인: ref → {actualCharacterId, provisionalSubjectKey, name, aliases}. getInputState와 같은 검증. */
    ObjectNode getInputCharacterIdentities(AnalysisJob job);

    /**
     * claim 응답(알려진·임시 인물, semantic 현재 문맥)이 읽는 claim 입력의 모든 대상. 호출자는 characters·worldSettings의
     * 현재값(식별자·이름·별칭·slots·식별 근거·서사 시점·보호 slot, 세계관 분류·이름·propertiesJson)만 읽고 이력과
     * references는 읽지 않는다. getInputState와 같은 검증을 한다.
     */
    JsonNode getInputCurrentSubjects(AnalysisJob job);

    /** Job 잠금을 보유한 한 번의 처리에서만 재사용한다. 반환 상태는 읽기 전용이다. */
    PreparedState prepareState(AnalysisJob job);

    interface PreparedState {
        /** 전체 상태. 분석 도메인의 읽기는 아래 대상별 읽기를 쓴다(#387). */
        JsonNode projectedState();

        void append(List<AnalysisStateChange> changes);

        /** 한 대상의 읽기 전용 view. 없으면 MissingNode다. */
        default JsonNode projectedTarget(String domain, String ref) {
            return AnalysisStateSlices.target(projectedState(), domain, ref);
        }

        /** references의 읽기 전용 view. */
        default JsonNode projectedReferences() {
            return AnalysisStateSlices.references(projectedState());
        }

        /** domain의 refs 대상과 references만 담은 읽기 전용 부분 상태. refs가 비면 references만 담는다. */
        default ObjectNode projectedSubset(String domain, Collection<String> refs) {
            return AnalysisStateSlices.subset(projectedState(), domain, refs);
        }
    }

    boolean prepareInput(AnalysisJob job);

    void assertValidInput(AnalysisJob job);

    /**
     * #387 Worker 내부 API의 lease 확인(AnalysisJobLeaseService)에서만 쓴다. 자동 누적은 claim 때 고정한 입력을 다시
     * hash하지 않고 scalar 불변식(journal PENDING·현재 원문 버전·입력 hash 존재·고정 입력 존재·선행 회차)만 본다.
     * 입력 내용을 읽는 소비자(getProjectedState·prepareState·appendValidatedChanges·seal·validateResume)는 각자 hash를
     * 확인하고, lease 아래에서 getInputState나 getAutomaticInputState()만 읽는 경로는 assertValidInput을 직접 부른다.
     * 수동 누적과 그 밖의 Job은 assertValidInput과 같다.
     */
    void assertValidLeaseInput(AnalysisJob job);

    void validateResume(AnalysisJob job);

    void appendValidatedChanges(AnalysisJob job, String expectedInputStateHash, List<AnalysisStateChange> changes);

    void seal(AnalysisJob job);

    void invalidateFrom(AnalysisJob job, String reason);

    // 작품 잠금 아래 의미 있는 정식 설정 변경 전에 호출한다. 완료 기록은 무효화하지 않는다.
    void assertSettingMutationAllowed(UUID workId);

    /**
     * 사람이 검수가 읽는 확정 설정(캐릭터·세계관)을 실제로 바꾸기 전에 호출한다. assertSettingMutationAllowed에 더해
     * 같은 작품의 검수가 확정 설정을 잠근 동안(접수부터 검토 종료까지) 409 VALIDATION_SETTINGS_LOCKED(#381). 관계·후보 제외·종료처럼 확정 설정을 바꾸지 않는 쓰기는 쓰지 않는다.
     */
    void assertConfirmedSettingMutationAllowed(UUID workId);

    /**
     * 검수 리포트 자신의 정정(ValidationCorrectionService)이 확정 설정을 바꾸기 전에 호출한다. assertConfirmedSettingMutationAllowed와 같지만
     * 그 리포트의 검토 단계 잠금만 통과시킨다(#381). 실행 중인 그 리포트와 다른 리포트의 잠금은 그대로 409다.
     */
    void assertReportCorrectionAllowed(UUID workId, UUID reportId);

    void confirmedSettingsChanged(UUID workId);

    // 작품 잠금을 먼저 획득하고 후보·정식 설정을 잠그기 전에 호출한다.
    void invalidateRunsForEpisodeChangeForUpdate(UUID workId, UUID episodeId, int firstAffectedEpisodeNo, String reason);

    void invalidateRunsForSettingBookChangeForUpdate(UUID workId, UUID settingBookId, String reason);

    void purgeSourceEvidenceForWorkForUpdate(UUID workId, UUID episodeId, int sourceEpisodeNo);
    void purgeSourceEvidenceForWorkForUpdate(UUID workId, UUID episodeId, int sourceEpisodeNo, String retainedContentKey);
}
