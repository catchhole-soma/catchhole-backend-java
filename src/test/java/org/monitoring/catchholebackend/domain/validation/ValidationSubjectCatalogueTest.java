package org.monitoring.catchholebackend.domain.validation;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper;

/** 합성 기준 항목만 쓴다. SUBJECTS·SETTINGS 투영이 AI projection(subject_catalogue·current_settings)과 같은 공통 vector를 만드는지 본다. */
@DisplayName("검수 주체 목록과 현재값 설정 투영 (#349 grid v4)")
class ValidationSubjectCatalogueTest {
    final ObjectMapper json=new ObjectMapper();final ValidationDataMapper mapper=new ValidationDataMapper(new AnalysisStateJournal());
    JsonNode vector() throws Exception {return json.readTree(Path.of("docs/contracts/validation-execution-v1.json").toFile()).path("execution").path("subjectCatalogueVector");}

    @Test @DisplayName("주체마다 한 행, S 번호 순, 첫 항목의 domain·name과 aliases의 순서 있는 합집합이다")
    void catalogueMatchesTheSharedVector() throws Exception {
        var vector=vector();
        assertThat(mapper.toValidationSubjectCatalogue(vector.path("entries"),Map.of())).isEqualTo(vector.path("subjects"));
    }
    @Test @DisplayName("SETTINGS는 요청 주체의 characters·worldSettings 행만 B 순서로 주고 worldHistory는 주지 않는다")
    void currentSettingsMatchTheSharedVector() throws Exception {
        var vector=vector();
        for(var request:vector.path("settingsRequests")) {
            var refs=new ArrayList<String>();request.path("subjectRefs").forEach(ref->refs.add(ref.asText()));
            var rows=mapper.toValidationCurrentSettings(vector.path("entries"),Map.of(),refs);
            assertThat(rows.stream().map(row->row.path("entry_ref").asText()).toList()).as(refs.toString())
                    .isEqualTo(json.convertValue(request.path("entryRefs"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}));
            assertThat(rows).allSatisfy(row->assertThat(ValidationDataMapper.CURRENT_DOMAINS).contains(row.path("domain").asText()));
        }
    }
    @Test @DisplayName("10개를 넘는 주체도 문자열이 아니라 번호 순으로 정렬한다")
    void subjectsAreNumericallyOrdered() {
        var entries=json.createArrayNode();for(int i=1;i<=11;i++)entries.addObject().put("domain","characters").put("subjectRef","synthetic-"+i).putObject("state").put("name","인물"+i);
        var refs=new ArrayList<String>();mapper.toValidationSubjectCatalogue(entries,Map.of()).forEach(row->refs.add(row.path("subject_ref").asText()));
        assertThat(refs).containsExactly("S1","S2","S3","S4","S5","S6","S7","S8","S9","S10","S11");
    }
}
