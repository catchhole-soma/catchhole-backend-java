package org.monitoring.catchholebackend.domain.analysis.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("사용자에게 보여 주는 분석 판단 문장")
class AnalysisExplanationTextTest {
    @Test
    @DisplayName("범위가 없는 위치를 뜻하는 개발 표현을 자연어로 안내한다")
    void translatesStorageLocationProse() {
        assertThat(AnalysisExplanationText.forReader("기존 속성이 없으므로 root에 포함합니다."))
                .isEqualTo("기존 속성이 없으므로 별도 구분 없이 정리합니다.");
        assertThat(AnalysisExplanationText.forReader("기존 snapshot의 slot에 MERGE합니다."))
                .isEqualTo("기존 설정의 설정 항목에 병합합니다.");
        assertThat(AnalysisExplanationText.forReader("별도 범위가 없으므로 루트에 포함합니다."))
                .isEqualTo("별도 범위가 없으므로 별도 구분 없이 정리합니다.");
    }

    @Test
    @DisplayName("작품의 실제 이름과 인용한 설정값은 개발 단어와 같아도 바꾸지 않는다")
    void preservesActualNamesAndQuotedValues() {
        assertThat(AnalysisExplanationText.forReader("Root 길드의 ‘MERGE’ 능력은 root에 포함합니다.",
                "Root 길드", "MERGE"))
                .isEqualTo("Root 길드의 ‘MERGE’ 능력은 별도 구분 없이 정리합니다.");
        assertThat(AnalysisExplanationText.forReader("T1의 직업을 유지합니다.", "T1"))
                .isEqualTo("T1의 직업을 유지합니다.");
    }

    @Test
    @DisplayName("해석할 수 없는 내부 식별자는 임의 대상명을 만들지 않고 중립적으로 안내한다")
    void doesNotInventMeaningForInternalReferences() {
        assertThat(AnalysisExplanationText.forReader("T1.P2에서 CANONICAL_SLOT_ALREADY_EXISTS로 거절됐습니다."))
                .isEqualTo("추출된 내용과 기존 설정을 비교한 결과입니다. 제안된 내용을 확인해 주세요.");
        for (String internalName : java.util.List.of("target_ref", "targetRef", "snapshot_version", "matchedCharacterId")) {
            assertThat(AnalysisExplanationText.forReader(internalName + "가 일치합니다."))
                    .isEqualTo("추출된 내용과 기존 설정을 비교한 결과입니다. 제안된 내용을 확인해 주세요.");
        }
        assertThat(AnalysisExplanationText.forReader("targetRef 길드를 소개합니다.", "targetRef 길드"))
                .isEqualTo("targetRef 길드를 소개합니다.");
    }

    @Test
    @DisplayName("자연어 판단과 비어 있는 과거 사유는 그대로 유지한다")
    void retainsPlainExplanation() {
        String text = "정신 수치가 35에서 36으로 증가하여 최신 수치로 갱신합니다.";
        assertThat(AnalysisExplanationText.forReader(text)).isEqualTo(text);
        assertThat(AnalysisExplanationText.forReader(null)).isNull();
        assertThat(AnalysisExplanationText.forReader("T1의 설정입니다.", "1"))
                .doesNotContain("T1");
    }
}
