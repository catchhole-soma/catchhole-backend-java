package org.monitoring.catchholebackend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisCandidateSourceGuard;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.character.entity.*;
import org.monitoring.catchholebackend.domain.character.processor.*;
import org.monitoring.catchholebackend.domain.character.repository.*;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.test.util.ReflectionTestUtils;

class CharacterPastHistoryAutomaticHoldTest {
    static Stream<Arguments> sources() {
        return Stream.of(false, true).flatMap(incoming -> Stream.of(false, true)
                .flatMap(current -> Stream.of(false, true).map(manual -> Arguments.of(incoming, current, manual))));
    }

    @ParameterizedTest @MethodSource("sources")
    void completedPastHistoryDoesNotConsultOrMutateCurrentSlot(boolean incomingBook, boolean currentBook, boolean manualCurrent) {
        var json = JsonNodeFactory.instance;
        var work = Work.create(Member.register("history@example.invalid", "test", null, "검증"), "검증", WorkGenre.FANTASY, null);
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        var character = WorkCharacter.create(work, "류정우", null, null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(character, "id", UUID.randomUUID());
        if (manualCurrent) character.protectManualSlot(CharacterFactType.STATUS, "status.부상");
        var job = AnalysisJob.create(work, null, null, AnalysisJobType.SETTING_EXTRACTION);
        job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        ReflectionTestUtils.setField(job, "sourceKind", incomingBook ? AnalysisSourceKind.SETTING_BOOK : AnalysisSourceKind.EPISODE);
        if (incomingBook) attachBook(job, work);
        var candidate = SettingCandidate.create(work, null, null, job, SettingEntityType.CHARACTER, "류정우", "류정우",
                character.getId(), SettingCandidateMatchStatus.MATCHED, "status.부상", "끝난 부상", SettingValueType.JSON,
                json.objectNode().put("name", "부상").put("active", false), json.arrayNode(), BigDecimal.ONE, null);
        ReflectionTestUtils.setField(candidate, "comparisonStatus", CharacterFactComparisonStatus.COMPLETED);
        ReflectionTestUtils.setField(candidate, "suggestedOperation", CharacterFactOperation.HISTORY_ONLY);
        ReflectionTestUtils.setField(candidate, "temporalScope", CharacterFactTemporalScope.PAST);
        var characters = mock(WorkCharacterRepository.class);
        when(characters.findByIdAndWorkIdForUpdate(character.getId(), work.getId())).thenReturn(Optional.of(character));
        var schemas = mock(CharacterSettingSchemaRepository.class);
        when(schemas.findAllActiveForWork(work.getId())).thenReturn(List.of(CharacterSettingSchema.create(work, "statuses", "status.*", "상태",
                CharacterFactType.STATUS, SettingValueType.JSON, CharacterSettingValueSemantics.BASE_VALUE,
                CharacterSettingMergePolicy.UPSERT_BY_NAME, json.arrayNode(), CharacterSettingSchemaSource.SYSTEM_SEED, true)));
        var accessor = mock(CharacterSnapshotAccessor.class);
        var sources = mock(CharacterSnapshotSourceRepository.class);
        var facts = mock(CharacterFactRepository.class);
        var currentJob = AnalysisJob.create(work, null, null, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(currentJob, "sourceKind", currentBook ? AnalysisSourceKind.SETTING_BOOK : AnalysisSourceKind.EPISODE);
        var currentCandidate = mock(SettingCandidate.class);
        when(currentCandidate.getAnalysisJob()).thenReturn(currentJob);
        var currentFact = mock(CharacterFact.class);
        when(currentFact.getSettingCandidate()).thenReturn(currentCandidate);
        var currentSource = mock(CharacterSnapshotSource.class);
        when(currentSource.getSourceFact()).thenReturn(currentFact);
        when(sources.findAllByWorkCharacterIdAndFactTypeAndFactKeyOrderBySourceOrderAsc(character.getId(), CharacterFactType.STATUS, "status.부상"))
                .thenReturn(List.of(currentSource));
        // A protected current slot and either source kind must be irrelevant to a history append.
        var confirmation = new CharacterAnalysisConfirmation(characters, mock(SettingCandidateRepository.class), sources,
                accessor, facts, schemas, new SettingCandidateSchemaResolver(), mock(AnalysisCandidateSourceGuard.class));
        assertThat(confirmation.automaticHold(candidate)).isNull();
        verifyNoInteractions(accessor, sources, facts);
        assertThat(character.isManuallyEditedSlot(CharacterFactType.STATUS, "status.부상")).isEqualTo(manualCurrent);
        ReflectionTestUtils.setField(character, "status", CharacterStatus.ARCHIVED);
        assertThat(confirmation.automaticHold(candidate)).isEqualTo(AutomaticReviewHoldReason.SUBJECT_CONFIRMATION_REQUIRED);
        ReflectionTestUtils.setField(character, "status", CharacterStatus.ACTIVE);
        for (var scope : List.of(CharacterFactTemporalScope.PRESENT, CharacterFactTemporalScope.UNKNOWN, CharacterFactTemporalScope.HYPOTHETICAL)) {
            ReflectionTestUtils.setField(candidate, "temporalScope", scope);
            assertThat(candidate.isAutomaticPastHistoryOnly()).isFalse();
        }
        ReflectionTestUtils.setField(candidate, "temporalScope", CharacterFactTemporalScope.PAST);
        ReflectionTestUtils.setField(candidate, "suggestedOperation", CharacterFactOperation.REVIEW_REQUIRED);
        assertThat(candidate.isAutomaticPastHistoryOnly()).isFalse();
        ReflectionTestUtils.setField(candidate, "suggestedOperation", CharacterFactOperation.HISTORY_ONLY);
        ReflectionTestUtils.setField(candidate, "comparisonStatus", CharacterFactComparisonStatus.FAILED);
        assertThat(candidate.isAutomaticPastHistoryOnly()).isFalse();
        ReflectionTestUtils.setField(candidate, "comparisonStatus", CharacterFactComparisonStatus.COMPLETED);
        job.configureReviewMode(AnalysisReviewMode.MANUAL);
        assertThat(candidate.isAutomaticPastHistoryOnly()).isFalse();
        job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        ReflectionTestUtils.setField(candidate, "temporalScope", CharacterFactTemporalScope.UNKNOWN);
        if (incomingBook) assertThat(confirmation.automaticHold(candidate)).isEqualTo(AutomaticReviewHoldReason.SETTING_TIME_CONFIRMATION_REQUIRED);
    }
    static Stream<Arguments> priorApplications() {
        return Stream.of(
                Arguments.of(CharacterFactOperation.HISTORY_ONLY, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, false, null),
                Arguments.of(CharacterFactOperation.ADD, CharacterFactConfirmApplicationMode.HISTORY_ONLY, false, null),
                Arguments.of(CharacterFactOperation.ADD, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, false, AutomaticReviewHoldReason.SETTING_TIME_CONFIRMATION_REQUIRED),
                Arguments.of(CharacterFactOperation.REMOVE, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL, false, AutomaticReviewHoldReason.SETTING_TIME_CONFIRMATION_REQUIRED),
                Arguments.of(null, null, true, AutomaticReviewHoldReason.SETTING_TIME_CONFIRMATION_REQUIRED));
    }

    @org.junit.jupiter.api.DisplayName("빈 현재 슬롯은 실제 적용 이력만 보호하며 이력 전용 값은 새 현재 관측을 막지 않는다")
    @ParameterizedTest @MethodSource("priorApplications")
    void absentSlotOnlyProtectsActualPriorApplications(CharacterFactOperation operation,
            CharacterFactConfirmApplicationMode mode, boolean legacy, AutomaticReviewHoldReason expected) {
        var json = JsonNodeFactory.instance;
        var work = Work.create(Member.register("rod-history@example.invalid", "test", null, "검증"), "검증", WorkGenre.FANTASY, null);
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        var character = WorkCharacter.create(work, "류정우", null, null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(character, "id", UUID.randomUUID());
        var job = AnalysisJob.create(work, null, null, AnalysisJobType.SETTING_EXTRACTION);
        job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        attachBook(job, work);
        ReflectionTestUtils.setField(job, "sourceKind", AnalysisSourceKind.SETTING_BOOK);
        var candidate = SettingCandidate.create(work, null, null, job, SettingEntityType.CHARACTER, "류정우", "류정우",
                character.getId(), SettingCandidateMatchStatus.MATCHED, "status.측정봉", "측정봉", SettingValueType.JSON,
                json.objectNode().put("name", "측정봉").put("active", true), json.arrayNode(), BigDecimal.ONE, null);
        ReflectionTestUtils.setField(candidate, "comparisonStatus", CharacterFactComparisonStatus.COMPLETED);
        ReflectionTestUtils.setField(candidate, "suggestedOperation", CharacterFactOperation.ADD);
        ReflectionTestUtils.setField(candidate, "temporalScope", CharacterFactTemporalScope.PRESENT);
        var prior = mock(SettingCandidate.class);
        when(prior.getSuggestedOperation()).thenReturn(operation);
        when(prior.getConfirmedApplicationMode()).thenReturn(mode);
        var fact = mock(CharacterFact.class);
        when(fact.getSettingCandidate()).thenReturn(legacy ? null : prior);
        var characters = mock(WorkCharacterRepository.class);
        when(characters.findByIdAndWorkIdForUpdate(character.getId(), work.getId())).thenReturn(Optional.of(character));
        var schemas = mock(CharacterSettingSchemaRepository.class);
        when(schemas.findAllActiveForWork(work.getId())).thenReturn(List.of(CharacterSettingSchema.create(work, "statuses", "status.*", "상태",
                CharacterFactType.STATUS, SettingValueType.JSON, CharacterSettingValueSemantics.BASE_VALUE,
                CharacterSettingMergePolicy.UPSERT_BY_NAME, json.arrayNode(), CharacterSettingSchemaSource.SYSTEM_SEED, true)));
        var facts = mock(CharacterFactRepository.class);
        when(facts.findAllByWorkCharacterIdAndFactTypeAndFactKeyOrderByEffectiveFromEpisodeNoDescCreatedAtDesc(
                character.getId(), CharacterFactType.STATUS, "status.측정봉")).thenReturn(List.of(fact));
        var confirmation = new CharacterAnalysisConfirmation(characters, mock(SettingCandidateRepository.class),
                mock(CharacterSnapshotSourceRepository.class), new CharacterSnapshotAccessor(), facts, schemas,
                new SettingCandidateSchemaResolver(), mock(AnalysisCandidateSourceGuard.class));

        assertThat(confirmation.automaticHold(candidate)).isEqualTo(expected);
        assertThat(confirmation.keepLateReviewInHistory(candidate, CharacterFactType.STATUS, "status.측정봉"))
                .isEqualTo(expected != null);
    }

    static void attachBook(AnalysisJob job, Work work) {
        var batch = org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.create(work, work.getMember(),
                org.monitoring.catchholebackend.domain.upload.type.UploadType.SETTING_BOOK,
                org.monitoring.catchholebackend.domain.upload.type.UploadSourceType.FILE);
        var book = org.monitoring.catchholebackend.domain.upload.entity.UploadFile.create(batch,
                org.monitoring.catchholebackend.domain.upload.type.UploadFileRole.SETTING_BOOK,
                "검증", "text/plain", "history-test", 10);
        ReflectionTestUtils.setField(book, "id", UUID.randomUUID());
        var revision = org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision.create(book, 1,
                new org.monitoring.catchholebackend.global.storage.StoredTextObject("history-test", null, "a".repeat(64), 10));
        ReflectionTestUtils.setField(revision, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(job, "sourceSettingBook", book);
        ReflectionTestUtils.setField(job, "sourceSettingBookRevision", revision);
    }

}
