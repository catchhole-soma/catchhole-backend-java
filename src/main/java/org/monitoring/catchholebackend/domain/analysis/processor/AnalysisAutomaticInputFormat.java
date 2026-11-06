package org.monitoring.catchholebackend.domain.analysis.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;

/**
 * #387 자동 누적 claim 입력(automatic_input_state)의 저장 형식.
 *
 * <ul>
 *   <li>format 1(formatVersion 없음): claim 때의 캐릭터·세계관 전체 사본과 references. 플래그를 켜기 전에 claim한 Job과 그 재시도가 쓴다.</li>
 *   <li>format 2: {@code {formatVersion:2, fingerprintVersion, fingerprint, references}}. 캐릭터·세계관은 실행 중 지금 DB에서
 *       대상별로 읽고({@link AnalysisLiveStateReader}), claim 때의 지문이 그 동안 설정이 바뀌지 않았음을 확인한다. references는 format 1과 같이 고정한다.</li>
 * </ul>
 * input_state_hash는 두 형식 모두 저장 객체의 hash다.
 */
public final class AnalysisAutomaticInputFormat {
    public static final int LIVE_FORMAT_VERSION = 2;

    private AnalysisAutomaticInputFormat() {
    }

    /** 자동 누적 Job의 저장 입력이 format 2인지. 입력이 아직 없거나 수동 누적이면 false다. */
    public static boolean isLive(AnalysisJob job) {
        if (!job.isAutomaticReview()) return false;
        JsonNode input = job.getAutomaticInputState();
        return input != null && input.path("formatVersion").isIntegralNumber()
                && input.path("formatVersion").asInt() == LIVE_FORMAT_VERSION;
    }

    /** format 2 저장 입력의 지문. format 2가 아니면 null이다. */
    public static String fingerprint(AnalysisJob job) {
        return isLive(job) && job.getAutomaticInputState().path("fingerprint").isTextual()
                ? job.getAutomaticInputState().path("fingerprint").asText() : null;
    }

    public static ObjectNode create(String fingerprint, ObjectNode references) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("formatVersion", LIVE_FORMAT_VERSION);
        input.put("fingerprintVersion", AnalysisLiveStateReader.FINGERPRINT_VERSION);
        input.put("fingerprint", fingerprint);
        input.set("references", references);
        return input;
    }
}
