package org.monitoring.catchholebackend.domain.analysis.processor;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisLiveSubjectSource;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.springframework.stereotype.Component;

/**
 * #387 자동 누적 format 2가 읽는 지금 확정 설정. claim 때 작품 전체를 복사하는 대신 실행 중 필요한 대상만 읽는다.
 *
 * <ul>
 *   <li>{@link #fingerprint}: 대상별·현재값 읽기가 돌려주는 값을 바꾸는 쓰기마다 달라지는 hash. scalar 조회만 한다.</li>
 *   <li>{@link #subjects}: 요청한 대상만 기존 capture와 같은 값으로 읽는다(비교 문맥·대상·기준값용).</li>
 *   <li>{@link #currentSubjects}: claim 응답이 읽는 현재값만 모든 대상에 대해 읽는다(이력·근거 사본 없음).</li>
 * </ul>
 * 값이 기존 capture와 같다는 것은 각 도메인의 {@link AnalysisLiveSubjectSource}가 같은 규칙을 공유해 보장하고
 * AnalysisLiveStateReaderIntegrationTest가 확인한다.
 */
@Component
public class AnalysisLiveStateReader {
    public static final int FINGERPRINT_VERSION = 1;
    private static final List<String> DOMAINS = List.of(AnalysisStateSlices.CHARACTERS, AnalysisStateSlices.WORLD_SETTINGS);

    private final Map<String, AnalysisLiveSubjectSource> sources = new HashMap<>();
    private final AnalysisStateJournal journal;

    public AnalysisLiveStateReader(List<AnalysisLiveSubjectSource> sources, AnalysisStateJournal journal) {
        for (AnalysisLiveSubjectSource source : sources) {
            if (!DOMAINS.contains(source.domain()) || this.sources.put(source.domain(), source) != null) {
                throw new IllegalStateException("자동 누적 live 읽기의 도메인이 중복되었거나 올바르지 않습니다.");
            }
        }
        if (!this.sources.keySet().equals(Set.copyOf(DOMAINS))) {
            throw new IllegalStateException("자동 누적 live 읽기에는 캐릭터와 세계관 도메인이 모두 필요합니다.");
        }
        this.journal = journal;
    }

    /** {"fingerprintVersion":1,"characters":[행…],"worldSettings":[행…]}의 hash. 행 모양은 각 도메인 source가 정한다. */
    public String fingerprint(UUID workId) {
        ObjectNode state = JsonNodeFactory.instance.objectNode().put("fingerprintVersion", FINGERPRINT_VERSION);
        for (String domain : DOMAINS) state.set(domain, source(domain).fingerprintRows(workId));
        return journal.hash(state);
    }

    /** domain의 refs 중 지금 있는 대상(ref → capture와 같은 state). 임시 대상(provisional-…)은 DB에 없으므로 담기지 않는다. */
    public ObjectNode subjects(Work work, String domain, Collection<String> refs) {
        return source(domain).captureSubjects(work, refs);
    }

    /** {characters, worldSettings}: claim 응답이 읽는 모든 대상의 현재값. references는 담지 않는다. */
    public ObjectNode currentSubjects(Work work) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (String domain : DOMAINS) result.set(domain, source(domain).captureCurrentValues(work));
        return result;
    }

    /** capture 결과 한 대상에서 현재값 읽기가 빼는 필드를 뺀 복사본. */
    public ObjectNode toCurrentValues(String domain, ObjectNode capturedState) {
        return source(domain).toCurrentValues(capturedState);
    }

    /** 세계관 분류(WorldSettingCategory 이름)의 지금 대상 ref. */
    public List<String> worldSubjectRefs(UUID workId, String category) {
        return source(AnalysisStateSlices.WORLD_SETTINGS).subjectRefs(workId, category);
    }

    private AnalysisLiveSubjectSource source(String domain) {
        AnalysisLiveSubjectSource source = sources.get(domain);
        if (source == null) throw new IllegalArgumentException("누적 상태의 대상 영역은 characters 또는 worldSettings여야 합니다.");
        return source;
    }
}
