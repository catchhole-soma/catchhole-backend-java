package org.monitoring.catchholebackend.domain.settingtool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactConfirmApplicationMode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse.ApplicationAction;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse.SourceKind;
import org.monitoring.catchholebackend.domain.settingtool.mapper.SettingToolMapper;

@DisplayName("인물 설정 출처는 원자료·내용 편집·확정 정보를 구분한다")
class SettingToolProvenanceTest {
    private final SettingToolMapper mapper = new SettingToolMapper();
    private final WorkCharacter character = mock(WorkCharacter.class);
    private final LocalDateTime recorded = LocalDateTime.of(2026, 1, 2, 3, 4, 5);

    @Test @DisplayName("설정집 후보 수정값은 설정집 출처와 내용 편집을 함께 보존한다")
    void editedBookCandidateKeepsItsOriginalSourceKind() {
        var candidate = candidate(AnalysisSourceKind.SETTING_BOOK, true);
        when(candidate.getReviewedApplicationMode()).thenReturn(CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
        when(candidate.getRawComparisonJson()).thenReturn(JsonNodeFactory.instance.objectNode().put("origin", "USER_EDIT"));
        var fact = fact(candidate);
        var item = mapper.toFactResponse(fact, "성별", true, null);
        var provenance = item.provenance().sources().getFirst();
        assertThat(provenance.sourceKind()).isEqualTo(SourceKind.SETTING_BOOK);
        assertThat(provenance.contentEdited()).isTrue();
        assertThat(provenance.applicationAction()).isEqualTo(ApplicationAction.USER_CONFIRM);
        assertThat(provenance.recordedAt()).isEqualTo(recorded);
        assertThat(item.origin()).isEqualTo(SettingToolResponse.Origin.CONFIRMED_ANALYSIS);
    }

    @Test @DisplayName("내용을 바꾸지 않은 사용자 현재 반영을 내용 편집으로 간주하지 않는다")
    void explicitReviewDoesNotMeanContentWasEdited() {
        var candidate = candidate(AnalysisSourceKind.SETTING_BOOK, false);
        when(candidate.isUserModified()).thenReturn(true);
        when(candidate.getReviewedApplicationMode()).thenReturn(CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
        when(candidate.getRawComparisonJson()).thenReturn(JsonNodeFactory.instance.objectNode().put("origin", "USER_EDIT"));
        var provenance = mapper.toFactResponse(fact(candidate), "성별", true, null).provenance().sources().getFirst();
        assertThat(provenance.contentEdited()).isFalse();
        assertThat(provenance.applicationAction()).isEqualTo(ApplicationAction.USER_CONFIRM);
    }

    @Test @DisplayName("수동 보호 표지와 후보 없는 Fact만으로 레거시를 직접 입력으로 단정하지 않는다")
    void candidateLessAndLegacyEditsRemainUnknown() {
        when(character.isManuallyEditedSlot(CharacterFactType.PROFILE, "profile.gender")).thenReturn(true);
        var current = current(List.of(fact(null)));
        var provenance = current.provenance().sources().getFirst();
        assertThat(provenance.sourceKind()).isEqualTo(SourceKind.UNKNOWN);
        assertThat(provenance.contentEdited()).isNull();
        assertThat(provenance.applicationAction()).isEqualTo(ApplicationAction.UNKNOWN);
        var legacy = candidate(AnalysisSourceKind.EPISODE, null);
        when(legacy.isUserModified()).thenReturn(true);
        when(legacy.getRawComparisonJson()).thenReturn(JsonNodeFactory.instance.objectNode().put("origin", "USER_EDIT"));
        provenance = mapper.toFactResponse(fact(legacy), "성별", false, null).provenance().sources().getFirst();
        assertThat(provenance.contentEdited()).isNull();
        assertThat(provenance.applicationAction()).isEqualTo(ApplicationAction.UNKNOWN);
    }

    @Test @DisplayName("MERGE의 서로 다른 원자료와 편집 여부를 출처별로 보존한다")
    void mergedCurrentDoesNotCollapseDifferentProvenance() {
        var original = candidate(AnalysisSourceKind.EPISODE, false);
        when(original.isReviewedAutomatically()).thenReturn(true);
        var edited = candidate(AnalysisSourceKind.SETTING_BOOK, true);
        when(edited.getReviewedApplicationMode()).thenReturn(CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
        when(edited.getRawComparisonJson()).thenReturn(JsonNodeFactory.instance.objectNode().put("origin", "USER_EDIT"));
        var first = fact(original);
        var second = fact(edited);
        var item = current(List.of(first, second));
        assertThat(item.provenance().sources()).extracting(row -> row.reference().targetId())
                .containsExactly(second.getId(), first.getId());
        assertThat(item.provenance().sources()).extracting(row -> row.sourceKind())
                .containsExactly(SourceKind.SETTING_BOOK, SourceKind.EPISODE);
        assertThat(item.provenance().sources()).extracting(row -> row.contentEdited()).containsExactly(true, false);
        assertThat(item.provenance().sources()).extracting(row -> row.applicationAction())
                .containsExactly(ApplicationAction.USER_CONFIRM, ApplicationAction.AUTOMATIC_CONFIRM);
        assertThat(item.provenance().sourcesTruncated()).isFalse();
    }

    @Test @DisplayName("출처 상한을 넘으면 참조와 상세 출처를 같은 순서와 길이로 자른다")
    void provenanceTruncationMatchesReferences() {
        var facts = new ArrayList<CharacterFact>();
        for (int i = 0; i < 21; i++) facts.add(fact(null));
        var item = current(facts);
        assertThat(item.provenance().sources()).hasSize(20);
        assertThat(item.provenance().sources()).extracting(row -> row.reference()).containsExactlyElementsOf(item.sources());
        assertThat(item.provenance().sourcesTruncated()).isTrue();
        assertThat(item.sourcesTruncated()).isTrue();
    }

    @Test @DisplayName("출처 범위 밖 근거와 출처 없는 현재값에 상세 출처를 만들어 붙이지 않는다")
    void missingAndOutOfRangeProvenanceIsOmitted() {
        assertThat(current(List.of()).provenance()).isNull();
        var evidence = new SettingToolResponse.Evidence(SettingToolResponse.EvidenceStatus.OUT_OF_RANGE,
                null, null, 9, null, null, null, null, List.of(), false);
        var item = mapper.toFactResponse(fact(candidate(AnalysisSourceKind.EPISODE, true)), "성별", false, evidence);
        assertThat(item.provenance()).isNull();
        assertThat(item.sources()).isEmpty();
        assertThat(item.value()).isNull();
    }

    private SettingCandidate candidate(AnalysisSourceKind sourceKind, Boolean edited) {
        var candidate = mock(SettingCandidate.class);
        when(candidate.getSourceKind()).thenReturn(sourceKind);
        when(candidate.getUserContentModified()).thenReturn(edited);
        return candidate;
    }

    private CharacterFact fact(SettingCandidate candidate) {
        var fact = mock(CharacterFact.class);
        when(fact.getId()).thenReturn(UUID.randomUUID());
        when(fact.getWorkCharacter()).thenReturn(character);
        when(fact.getSettingCandidate()).thenReturn(candidate);
        when(fact.getFactType()).thenReturn(CharacterFactType.PROFILE);
        when(fact.getFactKey()).thenReturn("profile.gender");
        when(fact.getFactValue()).thenReturn("여성");
        when(fact.getCreatedAt()).thenReturn(recorded);
        when(fact.getUpdatedAt()).thenReturn(recorded.plusDays(20));
        return fact;
    }

    private SettingToolResponse.Item current(List<CharacterFact> facts) {
        return mapper.toCharacterCurrentResponse(character,
                new CharacterSnapshotEntry(new CharacterSnapshotSlot(CharacterFactType.PROFILE, "profile.gender"), "여성", null, true),
                "성별", facts);
    }
}
