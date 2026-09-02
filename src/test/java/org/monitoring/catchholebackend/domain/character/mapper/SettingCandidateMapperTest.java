package org.monitoring.catchholebackend.domain.character.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateReviewStatusResponse;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSourceManager;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateValueValidation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterSnapshotAction;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateKind;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateValueValidationStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("설정 후보 Mapper 단위 테스트")
class SettingCandidateMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SettingCandidateMapper mapper = new SettingCandidateMapper(
            new CharacterSnapshotAccessor(),
            mock(CharacterSnapshotSourceManager.class)
    );

    @Test
    @DisplayName("설정 후보 Entity를 응답 DTO로 변환한다")
    void toResponseMapsSettingCandidate() {
        UUID workId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        UUID analysisJobId = UUID.randomUUID();
        UUID sourceChunkId = UUID.randomUUID();
        Work work = work(workId);
        Episode episode = episode(work, episodeId);
        AnalysisJob analysisJob = analysisJob(work, episode, analysisJobId);
        SettingCandidate candidate = SettingCandidate.create(
                work,
                episode,
                sourceChunkId,
                analysisJob,
                SettingEntityType.CHARACTER,
                "아리아",
                "age",
                "17",
                SettingValueType.NUMBER,
                objectMapper.createObjectNode().put("value", 17),
                objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("paragraph_index", 1)
                                .put("quote", "열일곱 살의 아리아")),
                new BigDecimal("0.8000"),
                objectMapper.createObjectNode().put("raw_value", "17")
        );
        UUID candidateId = UUID.randomUUID();
        ReflectionTestUtils.setField(candidate, "id", candidateId);

        SettingCandidateResponse response = mapper.toResponse(
                candidate,
                false,
                null,
                SettingCandidateValueValidation.valid()
        );

        assertThat(response.id()).isEqualTo(candidateId);
        assertThat(response.workId()).isEqualTo(workId);
        assertThat(response.episodeId()).isEqualTo(episodeId);
        assertThat(response.episodeNo()).isEqualTo(1);
        assertThat(response.sourceChunkId()).isEqualTo(sourceChunkId);
        assertThat(response.analysisJobId()).isEqualTo(analysisJobId);
        assertThat(response.candidateKind()).isEqualTo(SettingCandidateKind.SETTING);
        assertThat(response.entityName()).isEqualTo("아리아");
        assertThat(response.rawEntityMention()).isNull();
        assertThat(response.matchedCharacterId()).isNull();
        assertThat(response.matchStatus()).isEqualTo(SettingCandidateMatchStatus.UNRESOLVED);
        assertThat(response.attributeName()).isEqualTo("age");
        assertThat(response.attributeNameEditable()).isFalse();
        assertThat(response.attributeNamePrefix()).isNull();
        assertThat(response.attributeValue()).isEqualTo("17");
        assertThat(response.valueJson()).isEqualTo(Map.of("value", 17));
        assertThat(response.valueValidation().status())
                .isEqualTo(SettingCandidateValueValidationStatus.VALID);
        assertThat(response.valueValidation().errorCode()).isNull();
        assertThat(response.valueValidation().message()).isNull();
        assertThat(response.valueValidation().repairable()).isFalse();
        assertThat(response.evidenceSpans()).isInstanceOf(List.class);
        assertThat(response.rawAiResultJson()).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("값 오류와 schema 오류의 수정 가능 여부를 구분한다")
    void toResponseMapsValidationRepairability() {
        Work work = work(UUID.randomUUID());
        SettingCandidate candidate = SettingCandidate.create(
                work,
                null,
                null,
                null,
                SettingEntityType.CHARACTER,
                "아리아",
                "stats.mental",
                "정신: 37",
                SettingValueType.NUMBER,
                objectMapper.createObjectNode().put("value", 37),
                objectMapper.createArrayNode(),
                new BigDecimal("0.8000"),
                objectMapper.createObjectNode()
        );

        SettingCandidateResponse repairable = mapper.toResponse(
                candidate,
                false,
                null,
                SettingCandidateValueValidation.invalid(
                        CharacterErrorCode.SETTING_CANDIDATE_VALUE_FORMAT_INVALID
                )
        );
        SettingCandidateResponse unrepairable = mapper.toResponse(
                candidate,
                false,
                null,
                SettingCandidateValueValidation.unrepairableInvalid(
                        CharacterErrorCode.SETTING_CANDIDATE_SCHEMA_NOT_MATCHED
                )
        );

        assertThat(repairable.valueValidation().repairable()).isTrue();
        assertThat(unrepairable.valueValidation().repairable()).isFalse();
    }

    @Test
    @DisplayName("검토 목록 응답에서는 화면에 사용하지 않는 AI 원본 payload를 제외한다")
    void toReviewListResponseOmitsRawAiPayload() {
        Work work = work(UUID.randomUUID());
        SettingCandidate candidate = SettingCandidate.create(
                work,
                null,
                null,
                null,
                SettingEntityType.CHARACTER,
                "아리아",
                "age",
                "17",
                SettingValueType.NUMBER,
                objectMapper.createObjectNode().put("value", 17),
                objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("quote", "열일곱 살의 아리아")),
                new BigDecimal("0.8000"),
                objectMapper.createObjectNode().put("large_raw_value", "목록에서는 제외")
        );

        SettingCandidateResponse response = mapper.toReviewListResponse(
                candidate,
                false,
                null,
                SettingCandidateValueValidation.valid()
        );

        assertThat(response.valueJson()).isEqualTo(Map.of("value", 17));
        assertThat(response.evidenceSpans()).isInstanceOf(List.class);
        assertThat(response.rawAiResultJson()).isNull();
    }

    @Test
    @DisplayName("캐릭터 발견 후보의 종류와 nullable 설정 필드를 응답 DTO로 변환한다")
    void toResponseMapsCharacterDiscoveryCandidate() {
        Work work = work(UUID.randomUUID());
        SettingCandidate candidate = SettingCandidate.createCharacterDiscovery(
                work,
                null,
                UUID.randomUUID(),
                null,
                "세룸",
                "케닉의 넷째 아들 세룸",
                null,
                SettingCandidateMatchStatus.UNRESOLVED,
                objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                        .put("quote", "케닉의 넷째 아들 세룸은 나와라!")),
                new BigDecimal("0.9000"),
                null
        );

        SettingCandidateResponse response = mapper.toResponse(
                candidate,
                false,
                null,
                SettingCandidateValueValidation.notApplicable()
        );

        assertThat(response.candidateKind()).isEqualTo(SettingCandidateKind.CHARACTER_DISCOVERY);
        assertThat(response.entityName()).isEqualTo("세룸");
        assertThat(response.rawEntityMention()).isEqualTo("케닉의 넷째 아들 세룸");
        assertThat(response.attributeName()).isNull();
        assertThat(response.attributeNameEditable()).isFalse();
        assertThat(response.attributeValue()).isNull();
        assertThat(response.valueType()).isNull();
        assertThat(response.valueJson()).isNull();
        assertThat(response.valueValidation().status())
                .isEqualTo(SettingCandidateValueValidationStatus.NOT_APPLICABLE);
    }

    @Test
    @DisplayName("캐릭터 매칭 필드를 응답 DTO로 변환한다")
    void toResponseMapsCharacterMatchFields() {
        UUID matchedCharacterId = UUID.randomUUID();
        Work work = work(UUID.randomUUID());
        SettingCandidate candidate = SettingCandidate.create(
                work,
                null,
                null,
                null,
                SettingEntityType.CHARACTER,
                "아이나르",
                "프넬린의 두 번째 딸 아이나르",
                matchedCharacterId,
                SettingCandidateMatchStatus.MATCHED,
                "status.야만인",
                "야만인",
                SettingValueType.JSON,
                objectMapper.createObjectNode().put("name", "야만인"),
                null,
                new BigDecimal("0.9000"),
                null
        );

        SettingCandidateResponse response = mapper.toResponse(
                candidate,
                true,
                "status.",
                SettingCandidateValueValidation.valid()
        );

        assertThat(response.entityName()).isEqualTo("아이나르");
        assertThat(response.rawEntityMention()).isEqualTo("프넬린의 두 번째 딸 아이나르");
        assertThat(response.matchedCharacterId()).isEqualTo(matchedCharacterId);
        assertThat(response.matchStatus()).isEqualTo(SettingCandidateMatchStatus.MATCHED);
        assertThat(response.attributeNameEditable()).isTrue();
        assertThat(response.attributeNamePrefix()).isEqualTo("status.");
    }

    @Test
    @DisplayName("비교 실패 응답은 원문 오류 대신 타입 코드의 안전한 메시지를 노출한다")
    void toResponseSanitizesComparisonFailure() {
        Work work = work(UUID.randomUUID());
        SettingCandidate candidate = SettingCandidate.create(
                work,
                null,
                null,
                null,
                SettingEntityType.CHARACTER,
                "아리아",
                "아리아",
                UUID.randomUUID(),
                SettingCandidateMatchStatus.MATCHED,
                "stats.strength",
                "10",
                SettingValueType.NUMBER,
                objectMapper.createObjectNode().put("value", 10),
                null,
                new BigDecimal("0.9000"),
                null
        );
        candidate.startComparison();
        candidate.failComparison(
                AnalysisFailureCode.LLM_PROVIDER_ERROR,
                "https://provider.internal/v1 stack trace"
        );

        SettingCandidateResponse response = mapper.toResponse(
                candidate,
                false,
                null,
                SettingCandidateValueValidation.valid()
        );

        assertThat(response.comparisonFailureCode()).isEqualTo(AnalysisFailureCode.LLM_PROVIDER_ERROR);
        assertThat(response.comparisonErrorMessage())
                .isEqualTo(AnalysisFailureCode.LLM_PROVIDER_ERROR.getPublicMessage());
        assertThat(response.comparisonErrorMessage()).doesNotContain("provider.internal", "stack trace");
    }

    @Test
    @DisplayName("회차와 분석 작업이 없는 설정 후보도 응답 DTO로 변환한다")
    void toResponseHandlesNullableAssociations() {
        Work work = work(UUID.randomUUID());
        SettingCandidate candidate = SettingCandidate.create(
                work,
                null,
                null,
                null,
                SettingEntityType.CHARACTER,
                "아리아",
                "items",
                "푸른 마나석",
                SettingValueType.JSON,
                null,
                null,
                null,
                null
        );

        SettingCandidateResponse response = mapper.toResponse(
                candidate,
                false,
                null,
                SettingCandidateValueValidation.valid()
        );

        assertThat(response.workId()).isEqualTo(work.getId());
        assertThat(response.episodeId()).isNull();
        assertThat(response.episodeNo()).isNull();
        assertThat(response.sourceChunkId()).isNull();
        assertThat(response.analysisJobId()).isNull();
        assertThat(response.rawEntityMention()).isNull();
        assertThat(response.matchedCharacterId()).isNull();
        assertThat(response.matchStatus()).isEqualTo(SettingCandidateMatchStatus.UNRESOLVED);
        assertThat(response.valueJson()).isNull();
        assertThat(response.evidenceSpans()).isNull();
        assertThat(response.rawAiResultJson()).isNull();
    }

    @Test
    @DisplayName("설정 후보 Entity를 검토 상태 응답 DTO로 변환한다")
    void toReviewStatusResponseMapsSettingCandidate() {
        Work work = work(UUID.randomUUID());
        SettingCandidate candidate = SettingCandidate.create(
                work,
                null,
                null,
                null,
                SettingEntityType.CHARACTER,
                "아리아",
                "age",
                "17",
                SettingValueType.NUMBER,
                null,
                null,
                null,
                null
        );
        UUID candidateId = UUID.randomUUID();
        ReflectionTestUtils.setField(candidate, "id", candidateId);
        candidate.confirm();

        SettingCandidateReviewStatusResponse response = mapper.toReviewStatusResponse(candidate);

        assertThat(response.id()).isEqualTo(candidateId);
        assertThat(response.reviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
    }

    @Test
    @DisplayName("REMOVE 미리보기는 legacy target과 제거 목록의 중복을 한 번만 보여준다")
    void removePreviewDeduplicatesLegacyTargetAndRemovalEntries() {
        Work work = work(UUID.randomUUID());
        var statuses = objectMapper.createObjectNode();
        statuses.set("status.부상", objectMapper.createObjectNode().put("value", "부상"));
        statuses.set("status.마비독", objectMapper.createObjectNode().put("value", "마비독"));
        WorkCharacter character = WorkCharacter.create(
                work,
                "아리아",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                statuses,
                null
        );
        UUID characterId = UUID.randomUUID();
        ReflectionTestUtils.setField(character, "id", characterId);
        SettingCandidate candidate = SettingCandidate.create(
                work,
                null,
                UUID.randomUUID(),
                null,
                SettingEntityType.CHARACTER,
                "아리아",
                "아리아",
                characterId,
                SettingCandidateMatchStatus.MATCHED,
                "status.회복_중",
                "회복이 확인됨",
                SettingValueType.JSON,
                objectMapper.createObjectNode().put("value", "회복"),
                objectMapper.createArrayNode(),
                new BigDecimal("0.9000"),
                objectMapper.createObjectNode()
        );
        ReflectionTestUtils.setField(candidate, "matchedCharacter", character);
        candidate.startComparison();
        candidate.recordComparisonContext(character.getSnapshotVersion(), "preview-context");
        candidate.completeComparison(
                CharacterFactOperation.REMOVE,
                CharacterFactType.STATUS,
                "status.부상",
                null,
                null,
                objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("factType", "STATUS")
                                .put("factKey", "status.부상"))
                        .add(objectMapper.createObjectNode()
                                .put("factType", "STATUS")
                                .put("factKey", "status.마비독")),
                CharacterFactTemporalScope.PRESENT,
                "두 상태를 종료",
                objectMapper.createObjectNode(),
                java.time.LocalDateTime.now()
        );
        CharacterSnapshotSourceManager sourceManager = mock(CharacterSnapshotSourceManager.class);
        when(sourceManager.findSourceFactsBySlot(character)).thenReturn(Map.of());
        SettingCandidateMapper previewMapper = new SettingCandidateMapper(
                new CharacterSnapshotAccessor(),
                sourceManager
        );

        SettingCandidateResponse response = previewMapper.toResponse(
                candidate,
                true,
                "status.",
                SettingCandidateValueValidation.valid()
        );

        assertThat(response.snapshotChanges())
                .extracting(change -> change.action(), change -> change.factKey())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                CharacterSnapshotAction.REMOVE,
                                "status.부상"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                CharacterSnapshotAction.REMOVE,
                                "status.마비독"
                        )
                );
    }

    private Work work(UUID id) {
        Member member = Member.register("writer@example.com", "encoded-password", "01012345678", "작가");
        Work work = Work.create(member, "내 작품", WorkGenre.FANTASY, "내 설명");
        ReflectionTestUtils.setField(work, "id", id);
        return work;
    }

    private Episode episode(Work work, UUID id) {
        Episode episode = Episode.create(work, null, 1, "1화", "s3-key", "version-1", "hash-1", 100);
        ReflectionTestUtils.setField(episode, "id", id);
        return episode;
    }

    private AnalysisJob analysisJob(Work work, Episode episode, UUID id) {
        AnalysisJob analysisJob = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(analysisJob, "id", id);
        return analysisJob;
    }
}
