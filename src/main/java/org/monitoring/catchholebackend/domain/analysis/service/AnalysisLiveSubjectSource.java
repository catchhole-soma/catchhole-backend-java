package org.monitoring.catchholebackend.domain.analysis.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.work.entity.Work;

/**
 * #387 자동 누적 입력을 claim 때 작품 전체로 복사하지 않고 실행 중 필요한 대상만 지금 DB에서 읽는다. 실행 중 확정 설정은
 * GH201이 막으므로 지금 값이 claim 때 값과 같고, {@link #fingerprintRows}가 그 전제가 깨진 것을 잡는다.
 * 같은 도메인의 {@link AnalysisStateSource#capture}와 같은 규칙으로 만들며 같은 대상의 값이 같아야 한다.
 */
public interface AnalysisLiveSubjectSource {
    /** characters 또는 worldSettings. */
    String domain();

    /**
     * 지문 행(대상 id 문자열 순). 대상별 읽기와 현재값 읽기가 돌려주는 값을 바꾸는 쓰기마다 달라진다. scalar 조회만 하고
     * 엔티티·JSON 열을 읽지 않는다.
     */
    ArrayNode fingerprintRows(UUID workId);

    /** refs 중 지금 있는 대상만 capture와 같은 상태로 담는다(ref → state). 없는·보관된·형식이 틀린 ref는 담지 않는다. */
    ObjectNode captureSubjects(Work work, Collection<String> refs);

    /**
     * claim 응답(알려진 인물·semantic 현재 문맥)이 읽는 현재값만 담은 모든 대상(ref → state). capture에서 이력과 근거 사본을 뺀
     * 값과 같고 순서도 capture와 같다. 무엇을 빼는지는 도메인마다 {@link #toCurrentValues}가 정한다.
     */
    ObjectNode captureCurrentValues(Work work);

    /** capture 결과 하나에서 현재값 읽기가 빼는 필드를 뺀 복사본. 현재값 읽기와 capture의 동등성 기준이다. */
    ObjectNode toCurrentValues(ObjectNode capturedState);

    /** 이 분류(세계관 WorldSettingCategory 이름)의 지금 대상 ref. 분류가 없는 도메인은 지원하지 않는다. */
    default List<String> subjectRefs(UUID workId, String category) {
        throw new UnsupportedOperationException(domain() + "는 분류별 대상 목록을 제공하지 않습니다.");
    }
}
